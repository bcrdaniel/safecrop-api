# Migrações

Ainda não há objetos de negócio nem migrações SQL nesta rodada.
O Flyway inicializa sua tabela de histórico em um banco vazio, sem criar tabelas artificiais.
Este arquivo preserva o diretório no Git e não é executado pelo Flyway.

Nas próximas rodadas, adicionar scripts `V1__descricao.sql`, `V2__descricao.sql` etc.
Nunca editar uma migração já aplicada. Não habilitar baseline automático para contornar
um banco com objetos desconhecidos; inspecionar o destino antes da primeira inicialização.
