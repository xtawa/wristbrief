// @ts-nocheck
import { describe, expect, it } from "vitest";
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import path from "node:path";

describe("D1 SQL Migrations Chain", () => {
  it("applies migrations 0001 through 0007 cleanly on a fresh SQLite database", () => {
    const migrationsDir = path.resolve(__dirname, "../migrations");
    const migrationFiles = fs
      .readdirSync(migrationsDir)
      .filter((file) => file.endsWith(".sql"))
      .sort();

    expect(migrationFiles.length).toBeGreaterThanOrEqual(7);
    expect(migrationFiles[0]).toBe("0001_membership.sql");
    expect(migrationFiles[migrationFiles.length - 1]).toBe("0007_play_purchase_entitlements.sql");

    const db = new DatabaseSync(":memory:");

    for (const file of migrationFiles) {
      const filePath = path.join(migrationsDir, file);
      const sql = fs.readFileSync(filePath, "utf-8");
      // D1 migrations may contain multiple statements separated by semicolons
      db.exec(sql);
    }

    // Verify all expected tables exist
    const tables = db
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
      .all() as Array<{ name: string }>;

    const tableNames = tables.map((t) => t.name);
    expect(tableNames).toContain("membership_entitlements");
    expect(tableNames).toContain("managed_ai_usage");
    expect(tableNames).toContain("play_purchase_bindings");
    expect(tableNames).toContain("users");
    expect(tableNames).toContain("identities");
    expect(tableNames).toContain("sessions");
    expect(tableNames).toContain("play_rtdn_messages");
    expect(tableNames).toContain("legacy_migration_grants");

    // Verify play_purchase_bindings schema includes migration 0007 alterations
    const bindingCols = db
      .prepare("PRAGMA table_info(play_purchase_bindings)")
      .all() as Array<{ name: string; type: string }>;

    const colNames = bindingCols.map((c) => c.name);
    expect(colNames).toContain("token_hash");
    expect(colNames).toContain("user_id");
    expect(colNames).toContain("product_id");
    expect(colNames).toContain("status");
    expect(colNames).toContain("expires_at");
    expect(colNames).toContain("created_at");
    expect(colNames).toContain("updated_at");

    // Test row insertion with new columns
    const testHash = "a".repeat(64);
    db.prepare(
      "INSERT INTO play_purchase_bindings (token_hash, user_id, product_id, status, expires_at) VALUES (?, ?, ?, ?, ?)"
    ).run(testHash, "usr-test-1", "pro_yearly", "active", "2027-09-12T00:00:00Z");

    const row = db
      .prepare("SELECT * FROM play_purchase_bindings WHERE token_hash = ?")
      .get(testHash) as Record<string, unknown>;

    expect(row.user_id).toBe("usr-test-1");
    expect(row.product_id).toBe("pro_yearly");
    expect(row.status).toBe("active");
    expect(row.expires_at).toBe("2027-09-12T00:00:00Z");

    db.close();
  });
});
