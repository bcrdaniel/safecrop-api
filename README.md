# SafeCrop API

Fundação do backend B2B de gerenciamento de cultivo. Monólito modular REST, preparado
para evolução e futura integração com o frontend Angular. Esta rodada não implementa
usuários, organizações, autenticação, multi-tenancy ou módulos de cultivo.

## Tecnologias

Java 25 LTS, Spring Boot **3.5.16**, Maven **3.9.16** e Maven Wrapper oficial **3.3.4**
(distribuição `only-script`, sem necessidade de JAR do wrapper).
O parent Spring Boot gerencia as versões das dependências, sem overrides:

| Componente | Versão resolvida |
| --- | --- |
| Hibernate ORM | 6.6.53.Final |
| PostgreSQL JDBC | 42.7.11 |
| Flyway core e PostgreSQL | 11.7.2 |
| JUnit Jupiter | 5.12.2 |
| Mockito | 5.17.0 |
| Testcontainers | 1.21.4 |

Inclui Web MVC, JPA, Bean Validation, Actuator e Starter Test. Spring Boot 3.5 foi
escolhido para preservar JUnit 5 e suporta Java 25 oficialmente:
[requisitos oficiais](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
PostgreSQL 18 é o alvo local e dos testes de integração. A validação real dessa
combinação requer execução dos testes de integração. O Flyway 11.7.2 pode emitir
aviso de versão do PostgreSQL mais recente que a faixa testada por ele; não suprima
avisos nem force baseline para contorná-los.

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
   | `DB_USERNAME` | `postgres` |
   | `DB_PASSWORD` | preencher apenas localmente, com a senha existente |
   | `PORT` | `8080` (opcional) |

7. Antes da primeira execução, faça a inspeção de banco descrita abaixo.
8. Execute `SafecropApplication`. Aguarde a mensagem `Started SafecropApplication`.

Não compartilhe uma run configuration contendo senha. `.env.example` é apenas um
modelo: Spring e IntelliJ **não carregam `.env` automaticamente**. O profile local
fornece defaults de host/porta/banco/usuário, mas nunca uma senha. Fora dele, todas
as variáveis `DB_*` são obrigatórias. Nenhum profile local é ativado implicitamente.

## Inspecionar o banco antes da primeira inicialização

Não é necessário criar usuários ou alterar permissões nesta rodada. No pgAdmin,
abra o Query Tool conectado especificamente a `localhost:5432/safecrop_db` e confira
o destino e os objetos existentes com consultas somente de leitura:

```sql
SELECT current_database(), current_user, inet_server_addr(), inet_server_port();
SELECT n.nspname AS schema, c.relname AS object, c.relkind
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY 1, 2;
SELECT to_regclass('public.flyway_schema_history');
```

O banco deve ser `safecrop_db`. Se existir histórico Flyway, inspecione-o antes de
iniciar. Se houver objetos desconhecidos, interrompa e avalie a compatibilidade;
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
$env:DB_USERNAME = 'postgres'
$dbCredential = Get-Credential -UserName postgres -Message 'Senha PostgreSQL local'
$env:DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
.\mvnw.cmd spring-boot:run
```

Alternativamente, após `package`, execute com as mesmas variáveis:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar target/safecrop-api-1.0-SNAPSHOT.jar
```

Pare com `Ctrl+C`. Ao terminar, remova a senha da sessão com
`Remove-Item Env:DB_PASSWORD` e feche o terminal quando não precisar mais dele.

## Endpoints

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

`PostgresFoundationIT` utiliza `postgres:18-alpine`, banco descartável
`safecrop_test` com porta e credenciais geradas pelo Testcontainers. Confere JDBC,
consulta via EntityManager, `ddl-auto=validate`, inicialização/reexecução do Flyway
e health integrado. Nunca usa `safecrop_db`. Sem runtime acessível, execute somente
`test`; a execução explícita de `-Pintegration verify` falha se Docker não estiver
disponível, em vez de apresentar um sucesso com testes silenciosamente ignorados.

O Mockito pode emitir aviso de autoanexação do agente no Java 25. Isso não representa
falha de teste nesta versão; não foi adicionada configuração para ocultar o aviso.

## Flyway e Hibernate

Scripts futuros ficam em `src/main/resources/db/migration`, com nomes como
`V1__descricao.sql`. Não há scripts SQL nesta rodada: não há necessidade de criar
objetos de negócio. O README desse diretório não é uma migração.

Em banco vazio, o Flyway cria sua tabela técnica `flyway_schema_history`, sem
aplicar migrações de negócio. O histórico será usado nas próximas rodadas.
O baseline automático está desativado, `clean` bloqueado e criação automática de
schemas desativada. Migrações desconhecidas no histórico não são ignoradas
automaticamente. Scripts aplicados não devem ser editados.

Hibernate usa `ddl-auto=validate` e `open-in-view=false`; não cria nem altera tabelas.
Como ainda não existem entidades, a validação de tabelas de domínio começa nas
próximas rodadas. Mudanças de esquema devem passar exclusivamente pelo Flyway.

## Docker futuro

O Dockerfile possui build multi-stage, Maven via Wrapper, Java 25 e runtime Temurin
JRE com usuário não root. `package` executa os testes sem banco durante o build.
Nenhum deploy é realizado por esses arquivos.

```powershell
docker build -t safecrop-api:local .
```

Para execução futura, forneça `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD` por ambiente/secret da plataforma e `PORT` quando necessário.
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
- **Testcontainers sem Docker:** confira `docker version`; daemon indisponível não é
  um teste aprovado. Não é necessário Docker para executar pelo IntelliJ.

## Validação desta rodada

Os resultados efetivos e as limitações ficam em `docs/validacao-rodada-1a.md`.
Não houve commit, push ou alteração de configuração global.
