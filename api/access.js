// POST /api/access
// Corpo: { "uid": "D5FFDFB5", "sala": "SALA-1" }
// 200 -> { acesso: "concedido", sessao_id, professor }
// 403 -> { acesso: "negado" }
import pool from "../lib/db.js";
import { exigirMetodo, exigirChave, normalizarUid } from "../lib/http.js";

export default async function handler(req, res) {
  if (!exigirMetodo(req, res, "POST") || !exigirChave(req, res)) return;

  const uid = normalizarUid(req.body?.uid);
  const codigoSala = String(req.body?.sala ?? "").trim();
  if (!uid || !codigoSala) {
    return res.status(400).json({ erro: "Campos 'uid' (hex) e 'sala' sao obrigatorios" });
  }

  const cliente = await pool.connect();
  try {
    await cliente.query("BEGIN");

    const { rows: salas } = await cliente.query(
      "SELECT id FROM salas WHERE codigo = $1",
      [codigoSala]
    );
    if (salas.length === 0) {
      await cliente.query("ROLLBACK");
      return res.status(404).json({ erro: "Sala desconhecida" });
    }
    const salaId = salas[0].id;

    const { rows: profs } = await cliente.query(
      "SELECT id, nome FROM professores WHERE uid_cartao = $1 AND ativo",
      [uid]
    );
    const professor = profs[0];

    await cliente.query(
      "INSERT INTO tentativas_acesso (uid, sala_id, concedido) VALUES ($1, $2, $3)",
      [uid, salaId, Boolean(professor)]
    );

    if (!professor) {
      await cliente.query("COMMIT");
      return res.status(403).json({ acesso: "negado" });
    }

    // Se ficou uma aula aberta (ex.: o Arduino reiniciou), fecha-a antes de abrir a nova
    await cliente.query(
      "UPDATE sessoes SET fim = now(), motivo_fim = 'sistema' WHERE sala_id = $1 AND fim IS NULL",
      [salaId]
    );

    const { rows: novas } = await cliente.query(
      "INSERT INTO sessoes (professor_id, sala_id) VALUES ($1, $2) RETURNING id",
      [professor.id, salaId]
    );

    await cliente.query("COMMIT");
    return res.status(200).json({
      acesso: "concedido",
      sessao_id: Number(novas[0].id),
      professor: professor.nome,
    });
  } catch (erro) {
    await cliente.query("ROLLBACK").catch(() => {});
    if (erro.code === "23505") {
      return res.status(409).json({ erro: "Ja existe uma aula a decorrer nesta sala" });
    }
    console.error(erro);
    return res.status(500).json({ erro: "Erro interno" });
  } finally {
    cliente.release();
  }
}
