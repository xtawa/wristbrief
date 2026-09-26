import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const migrationsDir = fileURLToPath(new URL("../migrations/", import.meta.url).href);

/** SQLite-backed adapter for the gateway's existing prepared-statement stores. */
export function openServerDatabase(filename: string): { sqlite: DatabaseSync; db: D1Database } {
  mkdirSync(dirname(filename), { recursive: true, mode: 0o700 });
  const sqlite = new DatabaseSync(filename);
  sqlite.exec("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;");
  sqlite.exec("CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
  for (const name of readdirSync(migrationsDir).filter((file) => /^\d+.*\.sql$/.test(file)).sort()) {
    if (sqlite.prepare("SELECT name FROM schema_migrations WHERE name = ?").get(name)) continue;
    sqlite.exec("BEGIN IMMEDIATE");
    try {
      sqlite.exec(readFileSync(join(migrationsDir, name), "utf8"));
      sqlite.prepare("INSERT INTO schema_migrations (name) VALUES (?)").run(name);
      sqlite.exec("COMMIT");
    } catch (error) {
      sqlite.exec("ROLLBACK");
      sqlite.close();
      throw new Error(`Migration ${name} failed`, { cause: error });
    }
  }

  function prepared(sql: string, values: unknown[] = []) {
    return {
      sql,
      values,
      bind(...params: unknown[]) { return prepared(sql, params); },
      async first<T>() { return (sqlite.prepare(sql).get(...values.map(normalize)) ?? null) as T | null; },
      async all<T>() { return { results: sqlite.prepare(sql).all(...values.map(normalize)) as T[] }; },
      async run() {
        const info = sqlite.prepare(sql).run(...values.map(normalize));
        return { success: true, meta: { changes: info.changes, last_row_id: info.lastInsertRowid } };
      }
    };
  }
  const db = {
    prepare: (sql: string) => prepared(sql),
    async batch(statements: Array<{ sql: string; values: unknown[] }>) {
      sqlite.exec("BEGIN IMMEDIATE");
      try {
        // Execute without an await inside the transaction so concurrent requests
        // cannot interleave statements on this process's single SQLite connection.
        const result = statements.map((statement) => {
          const info = sqlite.prepare(statement.sql).run(...statement.values.map(normalize));
          return { success: true, meta: { changes: info.changes, last_row_id: info.lastInsertRowid } };
        });
        sqlite.exec("COMMIT");
        return result;
      } catch (error) { sqlite.exec("ROLLBACK"); throw error; }
    }
  } as unknown as D1Database;
  return { sqlite, db };
}

function normalize(value: unknown): string | number | bigint | Uint8Array | null {
  if (value == null) return null;
  if (typeof value === "boolean") return value ? 1 : 0;
  if (typeof value === "string" || typeof value === "number" || typeof value === "bigint" || value instanceof Uint8Array) return value;
  throw new TypeError("Unsupported database parameter");
}
