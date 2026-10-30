// POST /api/passages
// Corpo: { "sessao_id": 12 }
// 201 -> { total_passagens }
import pool from "../lib/db.js";
import { exigirMetodo, exigirChave, lerId } from "../lib/http.js";

export default async function handler(req, res) {
  if (!exigirMetodo(req, res, "POST") || !exigirChave(req, res)) return;

  const sessaoId = lerId(req.body?.sessao_id);
  if (!sessaoId) {
    return res.status(400).json({ erro: "Campo 'sessao_id' e obrigatorio" });
  }

  try {
    // So aceita passagens de aulas que ainda estao abertas
    const { rowCount } = await pool.query(
      `INSERT INTO passagens (sessao_id)
       SELECT id FROM sessoes WHERE id = $1 AND fim IS NULL`,
      [sessaoId]
    );
    if (rowCount === 0) {
      return res.status(404).json({ erro: "Sessao inexistente ou ja terminada" });
    }

    const { rows } = await pool.query(
      "SELECT count(*)::int AS total FROM passagens WHERE sessao_id = $1",
      [sessaoId]
    );
    return res.status(201).json({ total_passagens: rows[0].total });
  } catch (erro) {
    console.error(erro);
    return res.status(500).json({ erro: "Erro interno" });
  }
}
