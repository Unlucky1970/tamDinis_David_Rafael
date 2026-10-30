-- SmartAccess - esquema PostgreSQL
-- Executar uma vez na base de dados (ex.: psql "$DATABASE_URL" -f db/schema.sql)

CREATE TABLE IF NOT EXISTS professores (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nome        TEXT        NOT NULL,
  email       TEXT        UNIQUE,
  uid_cartao  TEXT        NOT NULL UNIQUE,   -- UID do cartao RFID em hexadecimal maiusculo, ex.: "D5FFDFB5"
  ativo       BOOLEAN     NOT NULL DEFAULT TRUE,
  criado_em   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uid_cartao_hex CHECK (uid_cartao ~ '^[0-9A-F]{8,20}$')
);

CREATE TABLE IF NOT EXISTS salas (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  codigo     TEXT        NOT NULL UNIQUE,      -- identificador enviado pelo Arduino, ex.: "SALA-1"
  descricao  TEXT,
  criado_em  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Uma sessao = uma aula (entre o cartao que abre e o fecho por cartao/automatico)
CREATE TABLE IF NOT EXISTS sessoes (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  professor_id  BIGINT      NOT NULL REFERENCES professores(id),
  sala_id       BIGINT      NOT NULL REFERENCES salas(id),
  inicio        TIMESTAMPTZ NOT NULL DEFAULT now(),
  fim           TIMESTAMPTZ,
  motivo_fim    TEXT,
  CONSTRAINT motivo_fim_valido CHECK (motivo_fim IN ('cartao', 'automatico', 'sistema')),
  CONSTRAINT fim_coerente CHECK ((fim IS NULL) = (motivo_fim IS NULL))
);

-- So pode haver uma aula aberta por sala
CREATE UNIQUE INDEX IF NOT EXISTS sessoes_uma_aberta_por_sala
  ON sessoes (sala_id) WHERE fim IS NULL;

CREATE INDEX IF NOT EXISTS sessoes_inicio_idx ON sessoes (inicio DESC);

-- Cada passagem detetada pelo sensor HC-SR04
CREATE TABLE IF NOT EXISTS passagens (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sessao_id     BIGINT      NOT NULL REFERENCES sessoes(id) ON DELETE CASCADE,
  registado_em  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS passagens_sessao_idx ON passagens (sessao_id);

-- Registo de todas as tentativas de acesso (concedidas e negadas)
CREATE TABLE IF NOT EXISTS tentativas_acesso (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  uid        TEXT        NOT NULL,
  sala_id    BIGINT      NOT NULL REFERENCES salas(id),
  concedido  BOOLEAN     NOT NULL,
  data       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS tentativas_data_idx ON tentativas_acesso (data DESC);

-- Vista com o resumo de cada aula
CREATE OR REPLACE VIEW vw_sessoes AS
SELECT s.id,
       sa.codigo AS sala,
       p.nome    AS professor,
       p.uid_cartao,
       s.inicio,
       s.fim,
       s.motivo_fim,
       (SELECT count(*) FROM passagens pa WHERE pa.sessao_id = s.id)::int AS total_passagens
FROM sessoes s
JOIN professores p ON p.id = s.professor_id
JOIN salas sa      ON sa.id = s.sala_id;
