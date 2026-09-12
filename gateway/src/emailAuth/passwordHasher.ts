import { argon2id, argon2Verify } from "hash-wasm";

// Argon2id per the OWASP minimum configuration (m=19 MiB, t=2, p=1). Workers cap
// isolate memory at 128 MiB, so memory cost stays far below the limit. This is the
// gateway's first runtime dependency; the WASM bundle has no Node API requirements.
const ARGON2ID_MEMORY_KIB = 19_456;
const ARGON2ID_ITERATIONS = 2;
const ARGON2ID_PARALLELISM = 1;
const ARGON2ID_HASH_LENGTH = 32;
const SALT_BYTES = 16;

export const PASSWORD_ALGO = "argon2id" as const;

export function randomPasswordSalt(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(SALT_BYTES));
}

export async function hashPassword(password: string, salt: Uint8Array = randomPasswordSalt()): Promise<string> {
  return argon2id({
    password,
    salt,
    parallelism: ARGON2ID_PARALLELISM,
    iterations: ARGON2ID_ITERATIONS,
    memorySize: ARGON2ID_MEMORY_KIB,
    hashLength: ARGON2ID_HASH_LENGTH,
    outputType: "encoded"
  });
}

export async function verifyPassword(password: string, encodedHash: string): Promise<boolean> {
  try {
    return await argon2Verify({ password, hash: encodedHash });
  } catch {
    return false;
  }
}

// A throwaway verification with fixed cost to equalize the timing of the
// "no such credential" and "wrong password" branches of login.
const DUMMY_HASH_PROMISE = hashPassword("timing-equalizer", new Uint8Array(SALT_BYTES));

export async function dummyVerifyPassword(): Promise<void> {
  await verifyPassword("irrelevant", await DUMMY_HASH_PROMISE);
}
