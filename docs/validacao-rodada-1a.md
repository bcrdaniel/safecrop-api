# Validação da Rodada 1A

Executada em 08/11/2026, Windows 11, Oracle JDK 25.0.3.

## Resultados

| Verificação | Resultado |
| --- | --- |
| Estado inicial | Conferido; igual à auditoria, sem Spring Boot e com alterações já no índice |
| Wrapper oficial | Maven Wrapper 3.3.4, distribuição `only-script` |
| `mvnw.cmd --version` | Aprovado; Maven 3.9.16 e Java 25.0.3, inclusive sem definir JAVA_HOME |
| Compilação de aplicação e testes | Aprovada com `release 25` |
| `mvnw.cmd test` | 5 testes, 0 falhas, 0 erros, 0 ignorados |
| Empacotamento | Aprovado; JAR executável `target/safecrop-api-1.0-SNAPSHOT.jar` |
| Execução temporária do JAR | Aprovada somente com datasource/JPA/Flyway excluídos por argumentos de smoke test |
| GET `/api/v1/status` por HTTP real | HTTP 200, `{"application":"SafeCrop API","status":"UP"}` |
| GET `/actuator/health` por HTTP real | HTTP 200, corpo `{"status":"UP"}`; nesta execução, sem health de banco |
| Testcontainers | Não executado: Docker CLI instalado, daemon Docker Desktop indisponível |
| PostgreSQL local | Não conectado: DB_PASSWORD ausente na sessão |
| Objetos/histórico do banco local | Não inspecionados; dependem de credencial disponível |
| Flyway no banco local | Não executado; nenhum SQL ou migração aplicado |
| Build/execução da imagem Docker | Não executados; sem daemon disponível |
| Execução pelo IntelliJ | Documentada; interface da IDE não foi utilizada para validação |
| `git diff --check` | Aprovado, sem erros de whitespace; Git emitiu avisos de normalização LF/CRLF |

## Escopo dos testes aprovados

`FoundationTest` inicializa o contexto sem persistência e valida o contrato JSON de
status, health sem informações internas, endpoint administrativo não exposto,
ProblemDetail genérico para erro inesperado e preservação de HTTP 405.

`PostgresFoundationIT` foi compilado, mas seus três testes ainda precisam ser
executados com `mvnw.cmd -Pintegration verify`. Nenhum resultado de integração foi
contabilizado como aprovado. São necessários PostgreSQL real no container e
inicialização completa de JPA/Flyway para fechar essa validação.

## Compatibilidade e avisos

Spring Boot 3.5.16 declara suporte a Java 25. As versões foram resolvidas pelo parent:
Hibernate 6.6.53.Final, JDBC PostgreSQL 42.7.11, Flyway 11.7.2, JUnit Jupiter 5.12.2,
Mockito 5.17.0 e Testcontainers 1.21.4. Nenhuma versão gerenciada foi sobrescrita.

O código do Flyway 11.7.2 recomenda atualização para versões de PostgreSQL acima
de 17. PostgreSQL 18 não é recusado por esse teste de versão, mas está além da
faixa testada indicada por essa versão do Flyway. A compatibilidade operacional
com PostgreSQL 18 ainda precisa de validação real; não foi afirmada como comprovada.

Referências verificadas:

- [Spring Boot: requisitos](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
- [Dependências gerenciadas](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html).
- [Código do suporte PostgreSQL no Flyway 11.7.2](https://github.com/flyway/flyway/blob/flyway-11.7.2/flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLDatabase.java).
- [Maven Wrapper oficial](https://maven.apache.org/tools/wrapper/index.html).

Mockito emitiu aviso de autoanexação de agente no Java 25; os testes passaram.
O log de exceção do cenário de erro 500 é esperado e não foi exposto ao cliente.

## Pendências para validação completa

1. Disponibilizar DB_PASSWORD apenas localmente na configuração de execução.
2. Confirmar por consulta o destino `localhost:5432/safecrop_db`, objetos existentes
   e eventual histórico Flyway antes de iniciar a aplicação contra esse banco.
3. Se houver objetos desconhecidos ou histórico incompatível, interromper e avaliar.
4. Iniciar a aplicação com profile `local` e persistência ativa; verificar os dois endpoints.
5. Quando o runtime estiver acessível, executar `mvnw.cmd -Pintegration verify`.
6. Validar a imagem Docker antes de qualquer deploy futuro.

Não foram alterados banco local, frontend, configurações globais Git ou recursos
remotos. Nenhum usuário/permissão PostgreSQL foi criado ou alterado. Nenhum
container foi iniciado. Não houve `git add`, commit ou push. O índice Git original
foi preservado, inclusive a entrada de Main.java removida apenas da árvore de trabalho.
