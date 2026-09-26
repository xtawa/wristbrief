import { DatabaseSync } from "node:sqlite";

const dbPath = process.argv[2];
const db = new DatabaseSync(dbPath);

console.log("users columns:", db.prepare("PRAGMA table_info(users)").all().map((c) => c.name).join(","));
console.log("credential columns:", db.prepare("PRAGMA table_info(email_credentials)").all().map((c) => c.name).join(","));

for (let i = 1; i <= 3; i++) {
  const id = `usr_walkthrough_reader_${i}`;
  const email = `reader${i}@example.com`;
  try {
    db.prepare("INSERT INTO users (id, status) VALUES (?, 'active')").run(id);
    console.log("inserted user", id);
  } catch (e) {
    console.log("user insert skipped:", e.message);
  }
  try {
    db.prepare(
      "INSERT INTO email_credentials (id, user_id, normalized_email, password_hash, password_algo, verified_at) VALUES (?, ?, ?, 'x', 'argon2id', CURRENT_TIMESTAMP)"
    ).run(`cred_walkthrough_${i}`, id, email);
    console.log("inserted credential", email);
  } catch (e) {
    console.log("credential insert skipped:", e.message);
  }
}

console.log("user count:", db.prepare("SELECT COUNT(*) AS c FROM users").get().c);
db.close();
