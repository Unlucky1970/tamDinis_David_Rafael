-- SmartAccess - esquema PostgreSQL
-- Executar uma vez na base de dados (ex.: psql "$DATABASE_URL" -f db/schema.sql)

CREATE TABLE IF NOT EXISTS utilizadores (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nome           TEXT        NOT NULL,
  email          TEXT        NOT NULL,
  password_hash  TEXT        NOT NULL,               -- bcrypt
  perfil         TEXT        NOT NULL DEFAULT 'utilizador',
  criado_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT perfil_valido CHECK (perfil IN ('admin', 'utilizador'))
);

CREATE UNIQUE INDEX IF NOT EXISTS utilizadores_email_idx ON utilizadores (lower(email));

-- Um cartao lido pela primeira vez fica 'pendente' ate o administrador o aprovar
CREATE TABLE IF NOT EXISTS cartoes (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  uid            TEXT        NOT NULL UNIQUE,        -- hexadecimal maiusculo, ex.: "D5FFDFB5"
  utilizador_id  BIGINT      REFERENCES utilizadores(id),
  estado         TEXT        NOT NULL DEFAULT 'pendente',
  criado_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uid_hex CHECK (uid ~ '^[0-9A-F]{8,20}$'),
  CONSTRAINT estado_valido CHECK (estado IN ('pendente', 'ativo', 'revogado')),
  CONSTRAINT ativo_tem_dono CHECK (estado <> 'ativo' OR utilizador_id IS NOT NULL)
);

-- Janelas de acesso de cada cartao (dia_semana: 1 = segunda ... 7 = domingo)
CREATE TABLE IF NOT EXISTS permissoes (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  cartao_id    BIGINT   NOT NULL REFERENCES cartoes(id) ON DELETE CASCADE,
  dia_semana   SMALLINT NOT NULL,
  hora_inicio  TIME     NOT NULL,
  hora_fim     TIME     NOT NULL,
  CONSTRAINT dia_valido CHECK (dia_semana BETWEEN 1 AND 7),
  CONSTRAINT horas_coerentes CHECK (hora_fim > hora_inicio)
);

CREATE INDEX IF NOT EXISTS permissoes_cartao_idx ON permissoes (cartao_id);

-- Cada Arduino tem a sua chave de API (guarda-se so o SHA-256)
CREATE TABLE IF NOT EXISTS dispositivos (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nome             TEXT        NOT NULL UNIQUE,
  api_key_hash     TEXT        NOT NULL UNIQUE,
  ultimo_contacto  TIMESTAMPTZ,
  porta_aberta     BOOLEAN     NOT NULL DEFAULT FALSE,
  pessoas_dentro   INT         NOT NULL DEFAULT 0,
  criado_em        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Registo de todas as tentativas de acesso (concedidas e negadas). Apenas insercoes.
CREATE TABLE IF NOT EXISTS registos_acesso (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dispositivo_id  BIGINT      NOT NULL REFERENCES dispositivos(id),
  cartao_uid      TEXT,                               -- NULL nas aberturas remotas
  utilizador_id   BIGINT      REFERENCES utilizadores(id),
  origem          TEXT        NOT NULL,
  resultado       TEXT        NOT NULL,
  motivo          TEXT,
  data_hora       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT origem_valida CHECK (origem IN ('cartao', 'remoto')),
  CONSTRAINT resultado_valido CHECK (resultado IN ('concedido', 'negado'))
);

CREATE INDEX IF NOT EXISTS registos_data_idx ON registos_acesso (data_hora DESC);
CREATE INDEX IF NOT EXISTS registos_dispositivo_idx ON registos_acesso (dispositivo_id, data_hora DESC);

CREATE OR REPLACE FUNCTION impedir_alteracao_registos() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'registos_acesso so aceita insercoes';
END;
$$;

DROP TRIGGER IF EXISTS registos_imutaveis ON registos_acesso;
CREATE TRIGGER registos_imutaveis
  BEFORE UPDATE OR DELETE ON registos_acesso
  FOR EACH ROW EXECUTE FUNCTION impedir_alteracao_registos();

-- Cada passagem pela porta detetada pelos 2 sensores (A = exterior, B = interior).
-- Uma entrada fica ligada ao acesso concedido que a autorizou (NULL se nao houver nenhum).
CREATE TABLE IF NOT EXISTS passagens (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dispositivo_id     BIGINT      NOT NULL REFERENCES dispositivos(id),
  sentido            TEXT        NOT NULL,
  registo_acesso_id  BIGINT      REFERENCES registos_acesso(id),
  data_hora          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT sentido_valido CHECK (sentido IN ('entrada', 'saida'))
);

CREATE INDEX IF NOT EXISTS passagens_registo_idx ON passagens (registo_acesso_id);

CREATE TABLE IF NOT EXISTS alertas (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tipo            TEXT        NOT NULL,
  descricao       TEXT        NOT NULL,
  dispositivo_id  BIGINT      REFERENCES dispositivos(id),
  data_hora       TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolvido       BOOLEAN     NOT NULL DEFAULT FALSE,
  CONSTRAINT tipo_valido CHECK (tipo IN ('tentativas_negadas', 'dispositivo_offline', 'entrada_sem_acesso'))
);

CREATE INDEX IF NOT EXISTS alertas_data_idx ON alertas (resolvido, data_hora DESC);

-- Fila de comandos para o Arduino (abertura remota)
CREATE TABLE IF NOT EXISTS comandos (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dispositivo_id  BIGINT      NOT NULL REFERENCES dispositivos(id),
  tipo            TEXT        NOT NULL DEFAULT 'abrir',
  estado          TEXT        NOT NULL DEFAULT 'pendente',
  criado_por      BIGINT      NOT NULL REFERENCES utilizadores(id),
  criado_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
  executado_em    TIMESTAMPTZ,
  CONSTRAINT tipo_comando_valido CHECK (tipo IN ('abrir')),
  CONSTRAINT estado_comando_valido CHECK (estado IN ('pendente', 'executado', 'expirado'))
);

CREATE INDEX IF NOT EXISTS comandos_pendentes_idx ON comandos (dispositivo_id) WHERE estado = 'pendente';
