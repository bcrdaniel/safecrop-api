# SafeCrop API

Fundação do backend B2B de gerenciamento de cultivo, com autenticação JWT e
administração central protegida. A arquitetura atual usa um único PostgreSQL
`safecrop_db`, um datasource e um pool: `public` para identidade e empresas,
`operational` para dados compartilhados com Row-Level Security.

Na RLS.4, o caminho de bancos físicos por empresa foi retirado do código ativo.
Não há CRUD de cultivo ou endpoint operacional novo. Consulte a
[arquitetura atual](docs/arquitetura-persistencia.md) e o
[registro de retirada](docs/rls-4-retirada-multibanco.md).

## Identidade central

Uma aplicação Spring Boot utiliza `safecrop_db` para identidade e operação.
A empresa é obtida da identidade autenticada e revalidada no backend.
Não há resolução dinâmica de banco ou credenciais PostgreSQL por empresa.
O cadastro de empresas será manual, por administrador da plataforma, sem cadastro público.

- `company`: `Company`, `CompanyStatus`, `CompanyRepository` e `CompanyService`.
  `CompanyAdminController` expõe criação e listagem paginada protegidas.
- `identity`: `AppUser`, `UserRole`, `UserStatus` e `AppUserRepository`.
  O construtor protege o vínculo; a FK valida a existência da empresa ao persistir.
  `AccountProvisioningService` cria administradores com BCrypt e vínculo explícito.
- `auth`: configuração de segurança e JWT, login, usuário atual e política de senha.
- `bootstrap`: processo administrativo não web, executado explicitamente.
- `operational`: `OperationalTransaction` estabelece contexto empresarial RLS
  na conexão do EntityManager antes do callback, com transação explícita.
- `shared.persistence.CentralEntity`: UUID gerado pelo JPA e auditoria em `Instant`.
  `createdAt` é preservado e `updatedAt` atualizado nos callbacks JPA.

Empresa tem nome obrigatório (até 200 caracteres), slug único (até 63), status e
auditoria. Slug é fornecido explicitamente, recebe trim e lowercase com `Locale.ROOT`;
aceita apenas ASCII minúsculo, dígitos e hífens simples entre segmentos. Não há
transliteração, substituição silenciosa de espaços nem geração pelo nome.

Usuário tem UUID, um `companyId` opcional conforme papel, nome obrigatório (até 200),
e-mail globalmente único (até 254), campo de hash, status e auditoria.
E-mail recebe trim e lowercase completo com `Locale.ROOT`, inclusive na parte local.
A política deliberadamente conservadora aceita ASCII com domínio pontuado, parte
local de até 64 caracteres e labels de domínio de até 63; não reescreve pontos ou
endereços `+tag`. Consultas `findByEmail` recebem `AppUser.normalizeEmail(...)`.

| Papel | Vínculo obrigatório |
| --- | --- |
| `PLATFORM_ADMIN` | Nenhuma empresa (`company_id IS NULL`) |
| `COMPANY_ADMIN` | Exatamente uma empresa existente |
| `EMPLOYEE` | Exatamente uma empresa existente |

O papel é explícito: ausência de empresa não concede permissões. O construtor ainda
cria usuários `INACTIVE` sem credencial; o provisionamento exige hash BCrypt válido
antes da ativação. O banco rejeita usuário ativo sem hash. Não há senha padrão,
hash fictício, dados iniciais ou criação de conta no startup normal. O hash não é
serializado e não aparece nos DTOs. Empresa e usuário precisam estar ativos;
o acesso futuro a cultivo usará a fronteira RLS. Ainda não há CRUD de cultivo.

## Tecnologias

Java 25 LTS, Spring Boot **3.5.16**, Maven **3.9.16** e Maven Wrapper oficial **3.3.4**
(distribuição `only-script`, sem necessidade de JAR do wrapper).
O parent Spring Boot gerencia as versões, com override somente do Flyway para PostgreSQL 18:

| Componente | Versão resolvida |
| --- | --- |
| Hibernate ORM | 6.6.53.Final |
| PostgreSQL JDBC | 42.7.11 |
| Flyway core e PostgreSQL | 11.13.3 |
| JUnit Jupiter | 5.12.2 |
| Mockito | 5.17.0 |
| Testcontainers | 1.21.4 |
| Spring Security | 6.5.11 |
| Nimbus JOSE JWT | 9.37.4 |

Inclui Web MVC, JPA, Bean Validation, Actuator, Spring Security, OAuth2 Resource
Server (com JOSE/Nimbus) e Security Test, gerenciados pelo parent.
Spring Boot 3.5 foi
escolhido para preservar JUnit 5 e suporta Java 25 oficialmente:
[requisitos oficiais](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
PostgreSQL 18 é o alvo local e dos testes de integração. A validação real dessa
combinação requer execução dos testes de integração. A RLS.5A atualizou apenas
Flyway core/módulo PostgreSQL para 11.13.3, reconhecendo PostgreSQL 18 sem suprimir
avisos ou alterar histórico. [Análise e implantação](docs/rls-5a-seguranca-producao.md).

## Pré-requisitos

- JDK 25 e IntelliJ IDEA com suporte a Java 25.
- PostgreSQL local, banco `safecrop_db` e usuário `postgres` já existentes.
- Acesso à internet na primeira execução para baixar Maven e dependências.
- Docker ou runtime compatível somente para Testcontainers ou uso da imagem Docker.

Não é necessário instalar Maven globalmente. Não há H2 ou Docker Compose.

## Abrir e executar pelo IntelliJ IDEA

1. Abra a pasta existente `safecrop-api` ou seu `pom.xml` como projeto Maven.
2. Em **File > Project Structure > Project**, selecione o SDK 25 e linguagem 25.
3. Em **Settings > Build, Execution, Deployment > Build Tools > Maven**, selecione
   o Maven Wrapper. Configure JDK 25 também para o importer e o runner Maven.
4. Na janela Maven, execute **Reload All Maven Projects**.
5. Em **Run > Edit Configurations**, crie uma configuração **Application**:
   - Nome: `SafeCrop local`.
   - Main class: `org.safecrop.SafecropApplication`.
   - Classpath/module: módulo `safecrop-api`.
   - JRE: JDK 25.
   - Working directory: raiz deste projeto.
6. No editor **Environment variables**, cadastre:

   | Variável | Valor local |
   | --- | --- |
   | `SPRING_PROFILES_ACTIVE` | `local` |
   | `DB_HOST` | `localhost` |
   | `DB_PORT` | `5432` |
   | `DB_NAME` | `safecrop_db` |
   | `DB_USERNAME` | `safecrop_runtime` (papel existente autorizado) |
   | `SPRING_FLYWAY_ENABLED` | `false` para runtime, após migração controlada |
   | `DB_PASSWORD` | preencher apenas localmente, com a senha existente |
   | `JWT_SECRET` | segredo Base64 privado, gerado conforme instruções abaixo |
   | `PORT` | `8080` (opcional) |

7. Antes da primeira execução, faça a inspeção de banco descrita abaixo.
8. Execute `SafecropApplication`. Aguarde a mensagem `Started SafecropApplication`.

Não compartilhe uma run configuration contendo senha. `.env.example` é apenas um
modelo: Spring e IntelliJ **não carregam `.env` automaticamente**. O profile local
fornece defaults de host/porta/banco/usuário, mas nunca uma senha. Fora dele, todas
as variáveis `DB_*` são obrigatórias. Nenhum profile local é ativado implicitamente.

## Inspecionar o banco antes da primeira inicialização

Não é necessário alterar permissões globais do PostgreSQL. No pgAdmin,
abra o Query Tool conectado especificamente a `localhost:5432/safecrop_db` e confira
o destino e os objetos existentes com consultas somente de leitura:

```sql
BEGIN READ ONLY;
SELECT version(), current_database(), current_schema(), current_user,
       inet_server_addr(), inet_server_port();
SHOW search_path;
SELECT n.nspname AS schema, c.relname AS object, c.relkind
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY 1, 2;
SELECT to_regclass('public.flyway_schema_history');
SELECT nspname FROM pg_namespace
WHERE nspname !~ '^pg_' AND nspname <> 'information_schema';
SELECT n.nspname, p.proname
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema';
SELECT extname, extversion FROM pg_extension;
ROLLBACK;
```

O banco deve ser `safecrop_db` e o schema esperado é `public`. Se existir histórico
Flyway, consulte `SELECT * FROM public.flyway_schema_history ORDER BY installed_rank`
em transação somente leitura antes de iniciar. Na fundação, o histórico pode estar
vazio. Neste projeto a V1 já foi aplicada localmente, conforme validação pelo IntelliJ.
Verifique seu checksum; não edite V1. A 1D.1 acrescenta a V2 exclusivamente central
para `operational_databases` e já foi aplicada localmente pelo operador. A 1D.2
acrescenta V3, com metadados de tentativa; a RLS.3 acrescenta V4, preparando o schema
compartilhado. Não execute migrações no banco pessoal sem autorização após a inspeção:
quando habilitado, Flyway aplica pendências
automaticamente. Esta implementação só executa migrações em Testcontainers.
Confira também se não há callbacks ou scripts externos na configuração de execução.
Se houver objetos desconhecidos ou histórico incompatível, interrompa e avalie;
não remova objetos, não use `baselineOnMigrate=true` para ocultar o problema.

## Maven Wrapper e JAR

No PowerShell, na raiz do projeto:

```powershell
# Ajuste somente para esta sessão se JAVA_HOME ainda não aponta para um JDK 25.
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.3'
.\mvnw.cmd --version
.\mvnw.cmd test
.\mvnw.cmd package
```

Para iniciar, defina as mesmas variáveis do IntelliJ na sessão atual. Para digitar a
senha sem colocá-la no histórico do terminal:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:DB_HOST = 'localhost'
$env:DB_PORT = '5432'
$env:DB_NAME = 'safecrop_db'
$env:DB_USERNAME = 'safecrop_runtime'
$env:SPRING_FLYWAY_ENABLED = 'false' # V1-V4 precisam estar aplicadas previamente.
$dbCredential = Get-Credential -UserName postgres -Message 'Senha PostgreSQL local'
$env:DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
# Configure JWT_SECRET conforme a próxima seção antes de iniciar.
.\mvnw.cmd spring-boot:run
```

Alternativamente, após `package`, execute com as mesmas variáveis:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar target/safecrop-api-1.0-SNAPSHOT.jar
```

Pare com `Ctrl+C`. Ao terminar, remova a senha da sessão com
`Remove-Item Env:DB_PASSWORD,Env:JWT_SECRET` e feche o terminal quando não precisar mais dele.

## Segurança e segredo JWT local

A API usa `SessionCreationPolicy.STATELESS`, sem formulário, Basic, sessão de
autenticação ou redirecionamentos. CSRF está desativado porque a autenticação usa
exclusivamente Bearer no header, sem cookies de autenticação. CORS não foi liberado.
`SecurityConfiguration` permite somente os métodos públicos da tabela abaixo;
`/api/v1/admin/**` exige `PLATFORM_ADMIN` e as demais rotas exigem autenticação.

Tokens são assinados com **HS256**, emissor `safecrop-api`, audiência `safecrop-web`
e validade explícita de **900 segundos** para limitar a janela de uso de um token
comprometido. Não há refresh token. `JwtDecoder` do Spring/Nimbus valida assinatura,
algoritmo, emissor, audiência, claims temporais e expiração, sem tolerância extra de
expiração. Configurações estão em `safecrop.jwt` (TTL permitido: 60–3600 segundos).
Referência: [JWT no Spring Security Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
O JWT contém UUID do usuário em `sub`, `iss`, `aud`, `iat`, `exp` e `jti`; não contém
e-mail, senha, conexão ou papel. Cada requisição autenticada carrega usuário,
papel e empresa atuais do banco central. Desativação e alteração de papel passam
a valer na próxima requisição, inclusive para tokens emitidos anteriormente.

`JWT_SECRET` deve vir do ambiente seguro e conter Base64 de pelo menos **32 bytes
aleatórios criptográficos**. Ausência, formato inválido ou chave insuficiente impedem
o startup web. Não há fallback. Gere apenas no terminal local, sem imprimir o valor:

```powershell
$jwtBytes = New-Object byte[] 32
$jwtRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$jwtRandom.GetBytes($jwtBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
$jwtRandom.Dispose()
[Array]::Clear($jwtBytes, 0, $jwtBytes.Length)
Remove-Variable jwtBytes,jwtRandom
```

Inicie a API nesse mesmo terminal. No IntelliJ, configure esse mesmo valor no editor
privado de variáveis da run configuration. Se precisar transferi-lo pela área de
transferência local, use `Set-Clipboard -Value $env:JWT_SECRET`, cole no editor e
limpe com `Set-Clipboard -Value ''`; não o imprima no console. Não compartilhe/salve
essa configuração no Git. Rotacionar a chave invalida todos os tokens existentes.
Não habilite logs de corpos HTTP, headers Authorization, SQL com parâmetros ou DEBUG
de autenticação em ambientes com credenciais. Use HTTPS fora do desenvolvimento local.

Senhas usam `BCryptPasswordEncoder` com custo **12**. Na criação são exigidos pelo
menos 12 caracteres e no máximo 72 bytes UTF-8; senhas não recebem trim e não há
truncamento. Login aceita até 72 bytes. A entidade só aceita hashes BCrypt com custo
10–16. E-mail inexistente e senha incorreta retornam a mesma mensagem; há comparação
BCrypt com hash efêmero para o caso inexistente. Não existe senha/conta de demonstração.

## Primeiro administrador: processo explícito

O startup normal nunca provisiona contas. Antes do bootstrap, faça a inspeção de
banco acima. Execute uma vez `org.safecrop.bootstrap.BootstrapMain`, processo não
web que usa os profiles `local` e `bootstrap-admin`, aplica BCrypt, cria um
`PLATFORM_ADMIN` sem empresa e encerra o contexto. Ele não requer `JWT_SECRET`.
O profile `bootstrap-admin` é proibido em startup web. Não há endpoint de bootstrap.

O comando não aceita argumentos de aplicação. Nome/e-mail podem vir do console;
senha usa `Console.readPassword`. Quando não houver console (IntelliJ/Maven), use
variáveis temporárias, nunca argumentos `-D`, arquivos, scripts com senha literal
ou o chat. Com as variáveis `DB_*` já configuradas no terminal:

```powershell
$env:SAFECROP_BOOTSTRAP_NAME = Read-Host 'Nome do administrador'
$env:SAFECROP_BOOTSTRAP_EMAIL = Read-Host 'Email do administrador'
$bootstrapPassword = Read-Host 'Senha inicial (mínimo 12 caracteres, máximo 72 bytes UTF-8)' -AsSecureString
$bootstrapCredential = New-Object System.Management.Automation.PSCredential('bootstrap', $bootstrapPassword)
try {
    $env:SAFECROP_BOOTSTRAP_PASSWORD = $bootstrapCredential.GetNetworkCredential().Password
    .\mvnw.cmd spring-boot:run '-Dspring-boot.run.main-class=org.safecrop.bootstrap.BootstrapMain'
    if ($LASTEXITCODE -ne 0) { throw 'Bootstrap não concluído; confira configuração e duplicação.' }
} finally {
    Remove-Item Env:SAFECROP_BOOTSTRAP_PASSWORD,Env:SAFECROP_BOOTSTRAP_NAME,Env:SAFECROP_BOOTSTRAP_EMAIL -ErrorAction SilentlyContinue
    Remove-Variable bootstrapPassword,bootstrapCredential -ErrorAction SilentlyContinue
}
```

No IntelliJ, duplique a configuração local, troque a main class para `BootstrapMain`,
remova argumentos e acrescente as três variáveis `SAFECROP_BOOTSTRAP_*` apenas no
editor privado. Execute e remova-as em seguida; não compartilhe a run configuration.
Depois volte à main class normal, com `JWT_SECRET`, para executar a API.

Bootstrap é transacional, serializado por advisory lock PostgreSQL, e rejeita a
existência de qualquer `PLATFORM_ADMIN` ou e-mail ocupado. Nunca redefine senha ou
modifica outras contas. Falhas retornam código de saída não zero e mensagem genérica.
Não use o processo para recuperação de senha ou criação de administradores adicionais.

## Endpoints

| Método | Rota | Permissão | Sucesso |
| --- | --- | --- | --- |
| GET | `/api/v1/status` | Público | 200 |
| GET | `/actuator/health` | Público | 200 |
| POST | `/api/v1/auth/login` | Público | 200 |
| GET | `/api/v1/auth/me` | Bearer válido e conta/empresa ativas | 200 |
| POST | `/api/v1/admin/companies` | `PLATFORM_ADMIN` | 201 |
| GET | `/api/v1/admin/companies?page=0&size=20` | `PLATFORM_ADMIN` | 200 |
| POST | `/api/v1/admin/companies/{companyId}/administrators` | `PLATFORM_ADMIN` | 201 |

Login recebe JSON com `email` e `password`, digitados pelo operador/usuário. Exemplo
de forma do contrato (placeholders não são credenciais utilizáveis):

```json
{"email":"<email do usuário>","password":"<senha informada privadamente>"}
```

```json
{"accessToken":"<JWT retornado>","tokenType":"Bearer","expiresIn":900}
```

Nas requisições protegidas envie `Authorization: Bearer <accessToken>`. Não coloque
tokens na URL. `/auth/me` retorna `id`, `name`, `email`, `role` e `company` com `id`,
`name` e `slug` (ou `null` para plataforma). Senha e hash nunca são retornados.

Para cadastrar empresa, envie `{"name":"<nome>","slug":"<slug>"}`. A resposta 201
contém `id`, `name`, `slug` normalizado e `status`. A listagem retorna
`content`, `page`, `size`, `totalElements`, `totalPages`; `size` aceita 1–100.

Para criar seu administrador, use o UUID retornado na rota `/administrators` e envie:

```json
{"name":"<nome>","email":"<email>","password":"<senha inicial fornecida privadamente>"}
```

O backend define `COMPANY_ADMIN` e o vínculo à empresa da rota. A resposta 201 tem
o mesmo formato seguro de `/auth/me`. Somente plataforma pode escolher a empresa;
usuários de empresa não possuem essa permissão. O cadastro não cria recursos PostgreSQL.

Erros utilizam `application/problem+json`, com `status`, `title` e `detail`:
400 para entrada inválida, 401 para credenciais/token inválidos ou conta/empresa
inativa, 403 para usuário sem permissão, 404 para empresa inexistente e 409 para
slug/e-mail ocupado. Erros inesperados continuam 500 genéricos; não são convertidos
em 400. Não há SQL, valores rejeitados de senha ou stack traces na resposta.

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/status
Invoke-RestMethod http://localhost:8080/actuator/health
```

Respostas esperadas:

```json
{"application":"SafeCrop API","status":"UP"}
```

```json
{"status":"UP"}
```

O status confirma que a rota REST funciona; a saúde do banco é verificada pelo
Actuator quando o datasource está ativo. Apenas `health` é exposto; componentes e
detalhes não são publicados. Uma falha de banco pode resultar em HTTP 503/DOWN.
Erros inesperados retornam ProblemDetail genérico; stack traces não são enviados ao cliente.

## Testes

```powershell
# Sem Docker, banco local ou variáveis pessoais:
.\mvnw.cmd test

# Com runtime de containers acessível: executa também os ITs no verify.
.\mvnw.cmd -Pintegration verify
```

`FoundationTest` inicializa o contexto HTTP com persistência desativada somente no
teste. Cobre status, health, restrição de endpoints Actuator, HTTP 405 e erro 500.
Não comprova acesso ao banco.

`CompanyTest` e `AppUserTest` cobrem normalização, entradas inválidas, papéis,
vínculos, duplicação no serviço e ausência de hash na serialização.

`SecurityHttpTest` cobre login, JWT real, revogação por estado/alteração de papel,
401/403, contratos administrativos e hash real sem banco. Repositórios são mocks.
`JwtConfigurationTest` valida falha segura de configuração; `AccountProvisioningTest`
e `BootstrapModeTest` verificam bootstrap, duplicação e restrição ao processo não web.
Os segredos JWT dos testes são gerados aleatoriamente e não usam o ambiente pessoal.

`PostgresFoundationIT` utiliza `postgres:18-alpine`, banco descartável
`safecrop_test` com porta e credenciais geradas pelo Testcontainers. Confere JDBC,
consulta via EntityManager, `ddl-auto=validate`, inicialização/reexecução do Flyway
e health integrado (incluindo o contributor `db`). Também verifica persistência dos
três papéis, auditoria, unicidade global, FK, bloqueio de exclusão de empresa com
usuários e constraints por SQL direto, sem depender das validações Java.
Também testa bootstrap e login persistidos, cadastro protegido de empresa e seu
administrador, autorização e bloqueio de token após desativar a empresa.
Operações de dados de teste são revertidas e existem apenas no container.
Nunca usa `safecrop_db`. Sem runtime acessível, execute somente
`test`; a execução explícita de `-Pintegration verify` falha se Docker não estiver
disponível, em vez de apresentar um sucesso com testes silenciosamente ignorados.

As provas `RlsIsolationIT`, `OperationalTransactionIT` e
`SharedOperationalMigrationIT` cobrem políticas, contexto transacional JPA/JDBC,
reutilização de conexões e permissões no PostgreSQL 18 descartável.
`OperationalRegistryMigrationIT` preserva a regressão de upgrade do histórico
central. Os testes exclusivos de bancos físicos foram retirados; os contratos
centrais de login, empresas, administradores e dados legados continuam cobertos.

## Flyway e Hibernate

`src/main/resources/db/migration/V1__central_companies_and_users.sql` cria
`companies` e `app_users`, sem inserts ou remoção de objetos. O Flyway registra V1
em `flyway_schema_history`. A configuração padrão ainda habilita Flyway no startup.
O exemplo de runtime usa `SPRING_FLYWAY_ENABLED=false`: V1–V4 devem ser aplicadas
antes por um processo autorizado com papel de migração. Não use o runtime para DDL.

As tabelas usam PK UUID, `timestamptz`, campos obrigatórios, CHECK de status,
nomes, slug, e-mail normalizado, auditoria e vínculo papel/empresa. Há unicidade
de slug e de e-mail global e FK com `ON DELETE RESTRICT`. Os índices das PKs e
UNIQUE atendem buscas por ID, slug e e-mail. `ix_app_users_company_id` atende
consultas por vínculo e verificações de FK; não há índices de status especulativos.
Auditoria de atualização é mantida pelo JPA, sem triggers para SQL externo.

O baseline automático está desativado, `clean` bloqueado e criação automática de
schemas desativada. Migrações desconhecidas no histórico não são ignoradas
automaticamente. Scripts aplicados não devem ser editados.

Hibernate usa `ddl-auto=validate` e `open-in-view=false`; não cria nem altera tabelas.
As entidades centrais são validadas contra as tabelas criadas pela V1.
A tabela legada `operational_databases` (V2/V3) permanece no banco sem entidade
JPA, repositório ou consumidores de produção. A V4 cria o schema compartilhado
`operational` e revoga seus acessos de PUBLIC, sem criar tabelas de cultivo.
Mudanças de esquema devem passar exclusivamente pelo Flyway.
A V1 foi preservada integralmente. A V2 da 1D.1 cria somente o localizador operacional
central; não altera `companies` ou `app_users`. A validade do formato BCrypt continua
sendo exigida pela entidade e autenticação. Dados anteriores não são reescritos.

## Operação compartilhada com RLS

Para produção, usar o perfil `prod`, os exemplos separados
[runtime](.env.production.example) e [migração](.env.migration.example) e o
[procedimento RLS.5A](docs/rls-5a-seguranca-producao.md). O perfil exclui Flyway da
inicialização e valida o papel restrito. `MigrationMain` é o processo separado de
migração, sem servidor, Spring/JPA ou pool da API. Os serviços centrais atuais
precisam somente de SELECT/INSERT; os grants de produção estão em
`ops/postgresql/runtime-grants.sql`, fora das migrações V1–V4.

`OperationalTransaction.execute(callback)` recarrega o usuário autenticado via
`CurrentIdentityService`, valida usuário/empresa ativos e configura
`set_config('safecrop.company_id', ?, true)` pela conexão do Hibernate antes do
callback. Não recebe empresa da requisição. Recusa transação ou EntityManager
preexistente, chamadas aninhadas e reentrada com suspensão/REQUIRES_NEW.

A variável é contexto de backend confiável, não credencial de identidade. Futuras
tabelas precisam de `company_id NOT NULL`, ENABLE/FORCE RLS, USING/WITH CHECK,
índices e constraints/FKs compostas. Não existe grant DML automático para tabelas
futuras. [Contrato de segurança e implantação](docs/rls-3-schema-e-permissoes.md).

O runtime e o job de migração usam papéis técnicos separados. A API mantém um
único datasource/pool. O exemplo de configuração indica `safecrop_runtime`, mas
não cria esse login nem altera o ambiente real. A configuração local do Flyway
continua intacta; configurar migração controlada e desabilitar Flyway no processo
runtime requer a implantação autorizada descrita na RLS.3.

As variáveis `OP_*`, o diagnóstico e o endpoint de provisionamento físico não
existem mais no caminho ativo. `READY`/`SUSPENDED` e os outros estados permanecem
somente nos registros legados; não substituem `CompanyStatus.ACTIVE/INACTIVE`.
A tabela e os bancos/papéis antigos não foram apagados.

O histórico está em [arquitetura multibanco legada](docs/legado/arquitetura-multibanco.md).
Não executar os procedimentos antigos. Uma cópia dos fontes anteriores à retirada,
inclusive arquivos não rastreados, foi preservada em `docs/legado/` com hashes.

## Integração Angular futura e limitações

Autenticação JWT, `/auth/me`, cadastro e consulta de empresas e cadastro de
administradores permanecem com os contratos existentes. O frontend não deve
chamar o endpoint retirado de provisionamento físico. O servidor revalida a
identidade e não confia em empresa ou papel enviados pelo cliente.

Ainda não há CRUD de cultivo, transações assíncronas operacionais ou limpeza do
registro legado. Essas etapas exigem autorização própria.

## Docker futuro

O Dockerfile possui build multi-stage, Maven via Wrapper, Java 25 e runtime Temurin
JRE com usuário não root. `package` executa os testes sem banco durante o build.
Nenhum deploy é realizado por esses arquivos.

```powershell
docker build -t safecrop-api:local .
```

Para execução futura, forneça `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD` e `JWT_SECRET` por ambiente/secret da plataforma e `PORT` quando necessário.
O PostgreSQL é externo. `localhost` dentro do container aponta para o próprio
container; no Docker Desktop, o host local costuma ser `host.docker.internal`.
Não embuta a senha na imagem ou na linha de comando. `EXPOSE 8080` documenta a
porta padrão; a porta efetiva é controlada por `PORT` e pelo mapeamento do runtime.
As tags das imagens recebem atualizações; builds de imagem estritamente idênticos
exigirão fixar digests após validação em um runtime disponível.

## Problemas comuns

- **Connection refused:** confirme serviço PostgreSQL, host e porta.
- **Password authentication failed:** confira usuário e senha locais; não use senha vazia.
- **Database does not exist:** confirme o nome `safecrop_db` e a instância acessada.
- **Placeholder DB_PASSWORD não resolvido:** configure a variável na run configuration
  ou no terminal que inicia o processo; `.env` sozinho não basta.
- **Flyway encontra schema não vazio sem histórico:** interrompa e inspecione;
  não habilite baseline nem apague tabelas para forçar inicialização.
- **Unsupported class version / release 25 not supported:** alinhe SDK, Maven runner,
  importer e `JAVA_HOME` ao JDK 25.
- **Porta HTTP em uso:** configure `PORT` para outra porta livre.
- **JWT_SECRET inválido/ausente:** gere e configure Base64 de 32 bytes aleatórios;
  não use valor padrão. Não ative `bootstrap-admin` para contornar validação na API.
- **Testcontainers sem Docker:** confira `docker version`; daemon indisponível não é
  um teste aprovado. Não é necessário Docker para executar pelo IntelliJ.

## Validação e histórico

Resultados atuais e pendências estão em [RLS.4](docs/rls-4-retirada-multibanco.md).
As etapas anteriores estão documentadas em [RLS.1](docs/rls-1-prova-tecnica.md),
[RLS.2](docs/rls-2-fronteira-transacional.md) e [RLS.3](docs/rls-3-schema-e-permissoes.md).
A documentação anterior à retirada está preservada em `docs/legado/`.
