-- SmartAccess - esquema PostgreSQL
-- Serve para criar a base de raiz E para atualizar uma base antiga: pode ser executado varias vezes.
-- (ex.: psql "$DATABASE_URL" -f db/schema.sql, ou colar no SQL Editor da Neon)

-- ======================================================
--  UTILIZADORES E HORARIOS
-- ======================================================
-- Perfis:
--   admin        docente administrador: entra a qualquer hora, fecha qualquer aula, e o UNICO com conta na app
--   funcionario  horario mais alargado (horarios_perfil), pode fechar qualquer aula
--   docente      so dentro do seu horario, so fecha a aula que abriu
-- Funcionarios e docentes sao so um nome associado a um ou mais cartoes (sem email nem palavra-passe).
CREATE TABLE IF NOT EXISTS utilizadores (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nome           TEXT        NOT NULL,
  email          TEXT,                               -- so o admin
  password_hash  TEXT,                               -- bcrypt, so o admin
  perfil         TEXT        NOT NULL DEFAULT 'docente',
  criado_em      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS utilizadores_email_idx ON utilizadores (lower(email));

-- Atualizacao: o antigo perfil 'utilizador' passa a 'docente'; so o admin precisa de email e palavra-passe
ALTER TABLE utilizadores DROP CONSTRAINT IF EXISTS perfil_valido;
UPDATE utilizadores SET perfil = 'docente' WHERE perfil = 'utilizador';
ALTER TABLE utilizadores ALTER COLUMN perfil SET DEFAULT 'docente';
ALTER TABLE utilizadores ADD CONSTRAINT perfil_valido CHECK (perfil IN ('admin', 'funcionario', 'docente'));
ALTER TABLE utilizadores ALTER COLUMN email DROP NOT NULL;
ALTER TABLE utilizadores ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE utilizadores DROP CONSTRAINT IF EXISTS admin_tem_conta;
ALTER TABLE utilizadores ADD CONSTRAINT admin_tem_conta
  CHECK (perfil <> 'admin' OR (email IS NOT NULL AND password_hash IS NOT NULL));

-- Horario por omissao de cada perfil (usado quando o cartao nao tem permissoes proprias).
-- O admin nao precisa: entra sempre.
CREATE TABLE IF NOT EXISTS horarios_perfil (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  perfil       TEXT     NOT NULL,
  dia_semana   SMALLINT NOT NULL,                   -- 1 = segunda ... 7 = domingo
  hora_inicio  TIME     NOT NULL,
  hora_fim     TIME     NOT NULL,
  CONSTRAINT horario_perfil_valido CHECK (perfil IN ('funcionario', 'docente')),
  CONSTRAINT horario_dia_valido CHECK (dia_semana BETWEEN 1 AND 7),
  CONSTRAINT horario_horas_coerentes CHECK (hora_fim > hora_inicio)
);

CREATE INDEX IF NOT EXISTS horarios_perfil_idx ON horarios_perfil (perfil, dia_semana);

-- ======================================================
--  CARTOES
-- ======================================================
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

-- Janelas de acesso proprias de um cartao (substituem o horario do perfil)
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

-- ======================================================
--  DISPOSITIVOS (ARDUINOS)
-- ======================================================
-- Cada Arduino regista-se sozinho pelo MAC (POST /api/device/register) e recebe uma chave unica.
-- Fica 'pendente' ate um administrador o aprovar na app. Guarda-se so o SHA-256 da chave.
CREATE TABLE IF NOT EXISTS dispositivos (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nome             TEXT        NOT NULL UNIQUE,
  mac              TEXT        UNIQUE,
  api_key_hash     TEXT        NOT NULL UNIQUE,
  estado           TEXT        NOT NULL DEFAULT 'pendente',
  ultimo_contacto  TIMESTAMPTZ,
  porta_aberta     BOOLEAN     NOT NULL DEFAULT FALSE,
  pessoas_dentro   INT         NOT NULL DEFAULT 0,
  criado_em        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Atualizacao: os dispositivos criados a mao antes desta versao ficam ativos
ALTER TABLE dispositivos ADD COLUMN IF NOT EXISTS mac TEXT UNIQUE;
ALTER TABLE dispositivos ADD COLUMN IF NOT EXISTS estado TEXT NOT NULL DEFAULT 'ativo';
ALTER TABLE dispositivos ALTER COLUMN estado SET DEFAULT 'pendente';
ALTER TABLE dispositivos DROP CONSTRAINT IF EXISTS dispositivo_estado_valido;
ALTER TABLE dispositivos ADD CONSTRAINT dispositivo_estado_valido CHECK (estado IN ('pendente', 'ativo', 'revogado'));
ALTER TABLE dispositivos DROP CONSTRAINT IF EXISTS mac_valido;
ALTER TABLE dispositivos ADD CONSTRAINT mac_valido CHECK (mac IS NULL OR mac ~ '^([0-9A-F]{2}:){5}[0-9A-F]{2}$');

-- ======================================================
--  REGISTOS DE ACESSO (imutaveis)
-- ======================================================
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

-- ======================================================
--  AULAS
-- ======================================================
-- Uma aula comeca quando um cartao abre a sala e acaba quando o dono (ou um admin/funcionario) a fecha
CREATE TABLE IF NOT EXISTS aulas (
  id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dispositivo_id          BIGINT      NOT NULL REFERENCES dispositivos(id),
  aberta_por              BIGINT      REFERENCES utilizadores(id),
  cartao_uid              TEXT,                       -- NULL se foi aberta remotamente
  registo_abertura_id     BIGINT      REFERENCES registos_acesso(id),
  inicio                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  fim                     TIMESTAMPTZ,
  fechada_por             BIGINT      REFERENCES utilizadores(id),
  motivo_fim              TEXT,
  entradas                INT         NOT NULL DEFAULT 0,
  saidas                  INT         NOT NULL DEFAULT 0,
  entradas_nao_detetadas  INT         NOT NULL DEFAULT 0,
  CONSTRAINT motivo_fim_valido CHECK (motivo_fim IN ('dono', 'admin', 'funcionario', 'offline', 'sistema')),
  CONSTRAINT fim_coerente CHECK ((fim IS NULL) = (motivo_fim IS NULL))
);

-- So pode haver uma aula aberta por porta
CREATE UNIQUE INDEX IF NOT EXISTS aulas_uma_aberta_idx ON aulas (dispositivo_id) WHERE fim IS NULL;
CREATE INDEX IF NOT EXISTS aulas_inicio_idx ON aulas (inicio DESC);

-- ======================================================
--  PASSAGENS (2 sensores: A = exterior, B = interior)
-- ======================================================
CREATE TABLE IF NOT EXISTS passagens (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dispositivo_id     BIGINT      NOT NULL REFERENCES dispositivos(id),
  sentido            TEXT        NOT NULL,
  registo_acesso_id  BIGINT      REFERENCES registos_acesso(id),   -- acesso que abriu a aula
  data_hora          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT sentido_valido CHECK (sentido IN ('entrada', 'saida'))
);

ALTER TABLE passagens ADD COLUMN IF NOT EXISTS aula_id BIGINT REFERENCES aulas(id);

CREATE INDEX IF NOT EXISTS passagens_registo_idx ON passagens (registo_acesso_id);
CREATE INDEX IF NOT EXISTS passagens_aula_idx ON passagens (aula_id);

-- ======================================================
--  ALERTAS E COMANDOS
-- ======================================================
CREATE TABLE IF NOT EXISTS alertas (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tipo            TEXT        NOT NULL,
  descricao       TEXT        NOT NULL,
  dispositivo_id  BIGINT      REFERENCES dispositivos(id),
  data_hora       TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolvido       BOOLEAN     NOT NULL DEFAULT FALSE
);

ALTER TABLE alertas DROP CONSTRAINT IF EXISTS tipo_valido;
ALTER TABLE alertas ADD CONSTRAINT tipo_valido CHECK (tipo IN (
  'tentativas_negadas', 'dispositivo_offline', 'entrada_sem_acesso', 'dispositivo_novo'));

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

-- ======================================================
--  CONFIGURACAO (enviada aos Arduinos em GET /api/device/config)
-- ======================================================
CREATE TABLE IF NOT EXISTS configuracao (
  chave      TEXT PRIMARY KEY,
  valor      TEXT NOT NULL,
  descricao  TEXT
);

INSERT INTO configuracao (chave, valor, descricao) VALUES
  ('intervalo_comandos_s', '5',                 'De quantos em quantos segundos o Arduino contacta a API'),
  ('perfis_offline',       'admin,funcionario', 'Perfis cujos cartoes abrem/fecham a sala quando nao ha rede')
ON CONFLICT (chave) DO NOTHING;
