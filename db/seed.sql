-- Dados iniciais para testes. MUDAR as palavras-passe e a chave do dispositivo!

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- para gerar o hash bcrypt das palavras-passe

INSERT INTO utilizadores (nome, email, password_hash, perfil) VALUES
  ('Administrador',    'admin@smartaccess.pt', crypt('admin123', gen_salt('bf', 10)), 'admin'),
  ('Utilizador Teste', 'user@smartaccess.pt',  crypt('user123',  gen_salt('bf', 10)), 'utilizador')
ON CONFLICT DO NOTHING;

-- A chave em texto tem de ser a mesma que esta em API_KEY no arduino_secrets.h
INSERT INTO dispositivos (nome, api_key_hash) VALUES
  ('PORTA-1', encode(sha256(convert_to('troca-isto-por-uma-chave-longa-e-aleatoria', 'UTF8')), 'hex'))
ON CONFLICT DO NOTHING;

-- Os mesmos cartoes que estavam no Arduino: o do admin entra sempre,
-- o do utilizador so de segunda a sexta, das 08:00 as 20:00
INSERT INTO cartoes (uid, utilizador_id, estado)
SELECT 'D5FFDFB5', id, 'ativo' FROM utilizadores WHERE email = 'admin@smartaccess.pt'
ON CONFLICT (uid) DO NOTHING;

INSERT INTO cartoes (uid, utilizador_id, estado)
SELECT '11223344', id, 'ativo' FROM utilizadores WHERE email = 'user@smartaccess.pt'
ON CONFLICT (uid) DO NOTHING;

INSERT INTO permissoes (cartao_id, dia_semana, hora_inicio, hora_fim)
SELECT c.id, dia, '08:00', '20:00'
FROM cartoes c, generate_series(1, 5) AS dia
WHERE c.uid = '11223344'
  AND NOT EXISTS (SELECT 1 FROM permissoes p WHERE p.cartao_id = c.id);
