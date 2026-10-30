-- Dados iniciais (os mesmos cartoes que estavam fixos no Arduino)

INSERT INTO salas (codigo, descricao) VALUES
  ('SALA-1', 'Sala de testes')
ON CONFLICT (codigo) DO NOTHING;

INSERT INTO professores (nome, uid_cartao) VALUES
  ('Professor Azul',  'D5FFDFB5'),
  ('Professor Verde', '11223344')
ON CONFLICT (uid_cartao) DO NOTHING;
