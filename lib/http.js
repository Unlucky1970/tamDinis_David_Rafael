import { timingSafeEqual } from "node:crypto";

// Devolve false (e responde 405) se o metodo HTTP nao for o esperado
export function exigirMetodo(req, res, metodo) {
  if (req.method === metodo) return true;
  res.setHeader("Allow", metodo);
  res.status(405).json({ erro: "Metodo nao permitido" });
  return false;
}

// O Arduino envia a chave no cabecalho "x-api-key"
export function exigirChave(req, res) {
  const esperada = process.env.DEVICE_API_KEY;
  if (!esperada) {
    res.status(500).json({ erro: "DEVICE_API_KEY nao configurada no servidor" });
    return false;
  }

  const recebida = Buffer.from(String(req.headers["x-api-key"] ?? ""));
  const certa = Buffer.from(esperada);
  if (recebida.length === certa.length && timingSafeEqual(recebida, certa)) return true;

  res.status(401).json({ erro: "Chave invalida" });
  return false;
}

// "d5 ff df b5" -> "D5FFDFB5"; devolve null se nao for hexadecimal valido
export function normalizarUid(valor) {
  const uid = String(valor ?? "").replace(/\s+/g, "").toUpperCase();
  return /^[0-9A-F]{8,20}$/.test(uid) ? uid : null;
}

export function lerId(valor) {
  const id = Number(valor);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}
