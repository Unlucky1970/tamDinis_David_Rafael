import pg from "pg";

// Reutiliza o pool entre invocacoes da mesma instancia serverless
const pool =
  globalThis._smartaccessPool ??
  new pg.Pool({
    connectionString: process.env.DATABASE_URL ?? process.env.POSTGRES_URL,
    max: 3,
  });

globalThis._smartaccessPool = pool;

export default pool;
