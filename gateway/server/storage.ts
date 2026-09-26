import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { join, resolve, sep } from "node:path";

/** Filesystem object store for the single-server deployment. Back it up with the DB. */
export function localObjectStore(directory: string): R2Bucket {
  const root = resolve(directory);
  function pathFor(key: string): string {
    const target = resolve(root, key);
    if (!target.startsWith(root + sep) || key.includes("\\")) throw new Error("invalid_object_key");
    return target;
  }
  return {
    async get(key: string) {
      try {
        const data = await readFile(pathFor(key));
        return { text: async () => new TextDecoder().decode(data) };
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
        throw error;
      }
    },
    async put(key: string, value: string | ArrayBuffer | Uint8Array) {
      const target = pathFor(key);
      await mkdir(resolve(target, ".."), { recursive: true, mode: 0o700 });
      const tmp = `${target}.${crypto.randomUUID()}.tmp`;
      await writeFile(tmp, typeof value === "string" ? value : Buffer.from(value), { mode: 0o600 });
      await rename(tmp, target);
    },
    async delete(key: string) { await rm(pathFor(key), { force: true }); }
  } as unknown as R2Bucket;
}
