// GET /api/sessions?sala=SALA-1&limite=50
// Lista as aulas mais recentes (para um dashboard ou para consulta)
import pool from "../../lib/db.js";
import { exigirMetodo, exigirChave } from "../../lib/http.js";

export default async function handler(req, res) {
  if (!exigirMetodo(req, res, "GET") || !exigirChave(req, res)) return;

  const sala = req.query.sala ? String(req.query.sala) : null;
  const limite = Math.min(Math.max(Number(req.query.limite) || 50, 1), 200);

  try {
    const { rows } = await pool.query(
      `SELECT * FROM vw_sessoes
       WHERE $1::text IS NULL OR sala = $1
       ORDER BY inicio DESC
       LIMIT $2`,
      [sala, limite]
    );
    return res.status(200).json(rows.map((r) => ({ ...r, id: Number(r.id) })));
  } catch (erro) {
    console.error(erro);
    return res.status(500).json({ erro: "Erro interno" });
  }
}
