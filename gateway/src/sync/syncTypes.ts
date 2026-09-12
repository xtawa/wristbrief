export type SyncEntity = "subscription" | "item_state" | "playback_progress";

export interface SubscriptionPayload {
  feedUrl: string;
  title?: string;
  category?: string;
  enabled?: boolean;
  sendToWatch?: boolean;
  watchKeywords?: string[];
}

export interface ItemStatePayload {
  isRead?: boolean;
  isSaved?: boolean;
}

export interface PlaybackProgressPayload {
  contentId?: string;
  episodeLocalId?: string;
  positionMs: number;
  durationMs: number;
  playbackSpeed?: number;
  completed?: boolean;
  progressGeneration?: number;
  playbackSessionId?: string;
}

export interface SyncMutation {
  idempotencyKey: string;
  entity: SyncEntity;
  entityId: string;
  updatedAt: number;
  deleted?: boolean;
  payload: SubscriptionPayload | ItemStatePayload | PlaybackProgressPayload;
}

export interface SyncPushRequest {
  deviceId: string;
  baseCursor?: number;
  mutations: SyncMutation[];
}

export interface SyncPushResponse {
  newCursor: number;
  appliedCount: number;
}

export interface DeviceSubscriptionRecord {
  subscriptionId: string;
  feedUrl: string;
  title: string | null;
  category: string | null;
  enabled: boolean;
  sendToWatch: boolean;
  watchKeywordsJson: string | null;
  revision: number;
  updatedAt: number;
  deletedAt: number | null;
}

export interface DeviceItemStateRecord {
  itemId: string;
  isRead: boolean;
  isSaved: boolean;
  readChangedAt: number | null;
  savedChangedAt: number | null;
  revision: number;
  updatedAt: number;
}

export interface DevicePlaybackProgressRecord {
  contentId: string;
  episodeLocalId: string | null;
  positionMs: number;
  durationMs: number;
  playbackSpeed: number;
  completed: boolean;
  sourceDeviceId: string | null;
  playbackSessionId: string | null;
  progressGeneration: number;
  revision: number;
  updatedAt: number;
}

export interface SyncPullResponse {
  cursor: number;
  hasMore: boolean;
  subscriptions: DeviceSubscriptionRecord[];
  itemStates: DeviceItemStateRecord[];
  playbackProgress: DevicePlaybackProgressRecord[];
}
