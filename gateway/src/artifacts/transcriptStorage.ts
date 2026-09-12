export interface TranscriptStorage {
  get(key: string): Promise<string | null>;
  put(key: string, data: string, contentType?: string): Promise<void>;
  delete(key: string): Promise<void>;
}

export class InMemoryTranscriptStorage implements TranscriptStorage {
  private readonly store = new Map<string, string>();

  async get(key: string): Promise<string | null> {
    return this.store.get(key) ?? null;
  }

  async put(key: string, data: string): Promise<void> {
    this.store.set(key, data);
  }

  async delete(key: string): Promise<void> {
    this.store.delete(key);
  }
}

export class R2TranscriptStorage implements TranscriptStorage {
  constructor(private readonly bucket: R2Bucket) {}

  async get(key: string): Promise<string | null> {
    const obj = await this.bucket.get(key);
    return obj ? await obj.text() : null;
  }

  async put(key: string, data: string, contentType = "application/json"): Promise<void> {
    await this.bucket.put(key, data, {
      httpMetadata: { contentType }
    });
  }

  async delete(key: string): Promise<void> {
    await this.bucket.delete(key);
  }
}

export function createTranscriptStorage(env: {
  TRANSCRIPTS_BUCKET?: R2Bucket;
  TRANSCRIPT_STORAGE?: TranscriptStorage;
}): TranscriptStorage {
  if (env.TRANSCRIPT_STORAGE) return env.TRANSCRIPT_STORAGE;
  if (env.TRANSCRIPTS_BUCKET) return new R2TranscriptStorage(env.TRANSCRIPTS_BUCKET);
  throw new Error("transcript_storage_unavailable");
}
