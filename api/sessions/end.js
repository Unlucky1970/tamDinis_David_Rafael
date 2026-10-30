// POST /api/sessions/end
// Corpo: { "sessao_id": 12, "motivo": "cartao" | "automatico" }
// 200 -> { sessao_id, fim, total_passagens }
import pool from "../../lib/db.js";
import { exigirMetodo, exigirChave, lerId } from "../../lib/http.js";

const MOTIVOS = ["cartao", "automatico"];

export default async function handler(req, res) {
  if (!exigirMetodo(req, res, "POST") || !exigirChave(req, res)) return;

  const sessaoId = lerId(req.body?.sessao_id);
  const motivo = req.body?.motivo;
  if (!sessaoId || !MOTIVOS.includes(motivo)) {
    return res
      .status(400)
      .json({ erro: "Campos 'sessao_id' e 'motivo' (cartao|automatico) sao obrigatorios" });
  }

  try {
    const { rows } = await pool.query(
      `UPDATE sessoes SET fim = now(), motivo_fim = $2
       WHERE id = $1 AND fim IS NULL
       RETURNING id, fim`,
      [sessaoId, motivo]
    );
    if (rows.length === 0) {
      return res.status(404).json({ erro: "Sessao inexistente ou ja terminada" });
    }

    const { rows: contagem } = await pool.query(
      "SELECT count(*)::int AS total FROM passagens WHERE sessao_id = $1",
      [sessaoId]
    );
    return res.status(200).json({
      sessao_id: Number(rows[0].id),
      fim: rows[0].fim,
      total_passagens: contagem[0].total,
    });
  } catch (erro) {
    console.error(erro);
    return res.status(500).json({ erro: "Erro interno" });
  }
}
