# Migrações

`V1__central_companies_and_users.sql` cria a identidade central sem inserir dados.
`V2__operational_database_registry.sql` acrescenta somente o registro central de
localizadores operacionais, sem criar bancos físicos ou modificar empresas/usuários.
Pré-requisitos, inspeção segura e execução estão no README da raiz.
Este arquivo não é executado pelo Flyway.

`V3__provisioning_attempt_metadata.sql` adiciona somente metadados centrais de tentativa,
sem reescrever registros V2. Não contém credenciais ou DDL de bancos físicos.
`V4__shared_operational_schema.sql` prepara o schema compartilhado `operational`
e revoga seus privilégios de `PUBLIC`, sem criar tabelas ou papéis técnicos.
Papéis, grants de implantação e padrão obrigatório para tabelas futuras estão em
[RLS.3](../../../../../docs/rls-3-schema-e-permissoes.md).
Nas próximas rodadas, adicionar scripts `V5__descricao.sql` etc., após autorização.

As versões operacionais legadas são independentes, em `db/operational-migration`; nunca
adicione essa localização ao Flyway central do application.yml.
Nunca editar uma migração já aplicada. Não habilitar baseline automático para contornar
um banco com objetos desconhecidos; inspecionar o destino antes da primeira inicialização.
