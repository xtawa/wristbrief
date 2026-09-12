// @ts-nocheck
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import path from "node:path";

export function createMigratedTestDb(): { db: DatabaseSync; d1: D1Database } {
  const db = new DatabaseSync(":memory:");
  const migrationsDir = path.resolve(__dirname, "../migrations");
  const migrationFiles = fs
    .readdirSync(migrationsDir)
    .filter((file) => file.endsWith(".sql"))
    .sort();

  for (const file of migrationFiles) {
    const sql = fs.readFileSync(path.join(migrationsDir, file), "utf-8");
    db.exec(sql);
  }

  const d1: D1Database = {
    prepare(sql: string) {
      let boundValues: unknown[] = [];
      const stmtObj = {
        bind(...values: unknown[]) {
          boundValues = values;
          return stmtObj;
        },
        async first<T = unknown>() {
          const stmt = db.prepare(sql);
          const row = stmt.get(...boundValues);
          return (row ?? null) as T | null;
        },
        async all<T = unknown>() {
          const stmt = db.prepare(sql);
          const results = stmt.all(...boundValues);
          return { results } as { results: T[] };
        },
        async run() {
          const stmt = db.prepare(sql);
          const info = stmt.run(...boundValues);
          return {
            success: true,
            meta: { changes: info.changes, last_row_id: info.lastInsertRowid }
          };
        }
      };
      return stmtObj;
    },
    async batch(statements: unknown[]) {
      const results = [];
      for (const s of statements) {
        results.push(await s.run());
      }
      return results;
    }
  };

  return { db, d1 };
}
