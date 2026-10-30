-- Dados iniciais para testes. MUDAR as palavras-passe antes da entrega!
-- Os dispositivos (Arduinos) nao entram aqui: registam-se sozinhos e sao aprovados na app.

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- para gerar o hash bcrypt das palavras-passe

INSERT INTO utilizadores (nome, email, password_hash, perfil) VALUES
  ('Administrador',    'admin@smartaccess.pt',       crypt('admin123', gen_salt('bf', 10)), 'admin'),
  ('Funcionario Teste', 'funcionario@smartaccess.pt', crypt('func123',  gen_salt('bf', 10)), 'funcionario'),
  ('Docente Teste',    'docente@smartaccess.pt',     crypt('doc123',   gen_salt('bf', 10)), 'docente')
ON CONFLICT DO NOTHING;

-- Horarios por omissao de cada perfil (o admin entra sempre)
--   docente:     segunda a sexta, 08:00-20:00
--   funcionario: segunda a sabado, 07:00-23:00
INSERT INTO horarios_perfil (perfil, dia_semana, hora_inicio, hora_fim)
SELECT 'docente', dia, '08:00', '20:00' FROM generate_series(1, 5) AS dia
WHERE NOT EXISTS (SELECT 1 FROM horarios_perfil WHERE perfil = 'docente');

INSERT INTO horarios_perfil (perfil, dia_semana, hora_inicio, hora_fim)
SELECT 'funcionario', dia, '07:00', '23:00' FROM generate_series(1, 6) AS dia
WHERE NOT EXISTS (SELECT 1 FROM horarios_perfil WHERE perfil = 'funcionario');

-- Cartoes reais do grupo (sem permissoes proprias: usam o horario do perfil)
INSERT INTO cartoes (uid, utilizador_id, estado)
SELECT '8DF36E69', id, 'ativo' FROM utilizadores WHERE email = 'admin@smartaccess.pt'
ON CONFLICT (uid) DO NOTHING;

INSERT INTO cartoes (uid, utilizador_id, estado)
SELECT '337A93AA', id, 'ativo' FROM utilizadores WHERE email = 'docente@smartaccess.pt'
ON CONFLICT (uid) DO NOTHING;
