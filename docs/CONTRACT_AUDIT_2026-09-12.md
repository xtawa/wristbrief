# WristBrief 增量改造契约审计（Contract Audit）

核查日期：2026-09-12
仓库：`G:\Projects\wristbrief`
当前提交：`d6397fd`（`main`）
核查原则：只读审计，不修改产品源码。本文档是《WristBrief 增量改造实施方案》（邮箱认证 / 管理后台 / Provider 管理 / 全文阅读器 / OPML URL 导入 / 返回手势 / 云同步闭环 / UI 对齐）编码前的契约基线：该方案中所有"编码前必须核查的事实"逐项与源码比对的结果、方案假设与实际代码的偏差修正、以及后续阶段的既定决策。

> 需求来源以 `docs/` + `uidocs/` 为基准。用户需求文本中的 `docsui/` 目录在仓库中不存在，确认为 `uidocs/` 的名称笔误；不新建 `docsui/`，也不复制一套规格。

## 1. 结论摘要

方案中的绝大多数关键假设被源码证实：身份模型（`(provider, provider_subject)` → internal `user_id`）、session token 形态（`wbs_` + 43 位 base64url，D1 仅存 SHA-256 hash）、provider 架构（4 adapter + allowlist/timeout/retry/redirect 策略）、`ItemStateSync` 每字段独立合并、OPML 解析限制、`MobileFeedManager` 增删流程、文章正文现状（`description` → sanitize → paragraphs）、以及"无任何系统返回处理"的根因诊断。

同时发现 13 项方案未预见、必须在实施中采纳的事实（见第 3 节），其中影响最大的是：

1. **Cloud Sync 客户端已实现但未接线** —— `mobile/.../sync/` 下 `CloudSyncApi`/`CloudSyncCoordinator`/`CloudSyncMerge`/`CloudSyncOutboxStore` 均存在且经单测覆盖，仅未接入运行时；对应阶段从"新建"改为"接线"。
2. **Feed store split-brain** —— 设置▸订阅源与主界面使用两套持久化（SharedPreferences vs SQLite），一次性迁移后数据分叉；订阅生命周期阶段必须先修复。
3. **Wear 模块名是 `:app`** 而非 `:wear`；本地 DB 文件是 `db/WristBriefDatabase.kt`（版本 4）。
4. gateway 无任何邮件发送契约、无密码 KDF 依赖、无 rate limit 基础设施 —— 均需从零新增（邮箱认证按方案 Phase A/B 分阶段）。

## 2. 已证实的方案假设（抽查要点）

| 方案假设 | 证据 | 结论 |
|---|---|---|
| `uidocs/` 存在，含 README + PHONE/WEAR LIQUID_GLASS_SPEC + assets/examples | `uidocs/`（README.md、PHONE_LIQUID_GLASS_SPEC.md、WEAR_LIQUID_GLASS_SPEC.md、assets/、examples/） | 证实 |
| `identities` 以 `(provider, provider_subject)` 唯一；Google `sub` 为外部主键；email 仅元数据 | `gateway/migrations/0003_google_identity.sql:12-23`（复合 PK；`CHECK (provider IN ('google'))`）；`gateway/src/googleIdentity.ts`（RS256/JWKS/aud/exp 验签，要求 `email_verified`）；`docs/AUTH.md:7` | 证实；注意 provider CHECK 目前仅允许 `google`，新增 email 身份需扩展 |
| session token `wbs_[A-Za-z0-9_-]{43}`；D1 存 SHA-256 hash | `gateway/src/accountSession.ts:56-59,126`；mobile `AccountAuth.kt:293` 同一 regex；`sessions.token_hash`（0004） | 证实 |
| mobile token 存普通 SharedPreferences `account_session` | `AccountAuth.kt:74-81`（keys `token`/`expires_at`/`user_id`） | 证实 |
| `AiProvider`/`AiProviderRegistry`/`createProviderRegistry(env)`、4 种 adapter、https + `AI_ALLOWED_HOSTS`、timeout ≤60s、retry ≤2、`redirect:"error"` | `gateway/src/provider.ts:12-16,188-254,312-316,343-353`；注意：OpenAI-compatible provider 是**无条件注册**的（`AI_BASE_URL` 非法即抛错），非可选 | 证实（含一条细化） |
| 0008 表结构：`user_item_states`（is_read/is_saved/read_changed_at/saved_changed_at/revision）、`user_subscriptions`（revision/deleted_at）等 | `gateway/migrations/0008_cloud_sync_and_content_registry.sql:14-41` | 证实；注意 `user_item_states` **无** `deleted_at`（tombstone 仅在 `user_subscriptions`） |
| `ItemStateSync`：`VersionedFlag` read/saved 独立合并，timestamp-newer-wins + origin tie-break | `mobile/.../ItemStateSync.kt:14-67`（`SyncOrigin { PHONE, WEAR }`，tie 时 WEAR 胜） | 证实 |
| `MobileFeedManager.add()`：normalize → 本地查重 → 网络 probe → 新建 → 保存 → Wear 发布；删除为 `filterNot { it.id == id }` | `mobile/.../FeedManagement.kt:180-199,236`（方法名是 `remove()`）；Wear 发布走 `DataClient.putDataItem`（`/wristbrief/subscriptions/v1`），非 MessageClient | 证实 |
| 文章正文 = `item.description` → `ArticleContentSanitizer.sanitize` → `paragraphs`，保留"打开原网页"回退 | `mobile/.../ArticleDetailDestination.kt:75-77,274-314` | 证实 |
| OPML：2,000,000 字符 / 1000 feeds / DOCTYPE-ENTITY 拒绝 / normalize / 批内去重 / category / wristbrief 自定义字段 | `mobile/.../Opml.kt:3-65,89-93`；`OpmlDocuments.kt`（8MiB 字节上限的受限读取） | 证实 |
| 仓库无任何系统返回处理（`BackHandler`/`OnBackPressedDispatcher`/`NavHost` 零命中） | 全仓库 grep 零命中；`MainActivity` 为 `ComponentActivity`，无 `onBackPressed` 覆盖 | 证实 —— Phase 1 根因成立 |
| 无装饰性渐变 | grep `linearGradient|radialGradient|sweepGradient|Brush.gradient` 仅命中 `GlassTokens.kt:11`、`GlassComponents.kt:41` 两条禁止注释 | 证实（已达标，后续防回归） |
| gateway 无邮件 provider、无 KDF 依赖 | `gateway/package.json` 仅 devDeps（typescript/vitest/wrangler/workers-types）；package-lock 与全仓库 grep 无 resend/mailchannels/postmark/SES/argon2/scrypt/bcrypt | 证实 —— 邮箱认证按 Phase A（接口 + test sender）先行 |
| Play Billing ownership 挂 internal `user_id`，token 仅存 SHA-256 hash | `gateway/src/d1MembershipStore.ts:75-93`（`sha256(purchaseToken)` → `play_purchase_bindings.user_id`） | 证实 |

## 3. 方案必须采纳的事实修正（13 项）

| # | 事实 | 证据 | 对实施的约束 |
|---|---|---|---|
| 1 | Wear 模块是 `:app`（package `ink.underflo.wristbrief`），非 `:wear`；`settings.gradle.kts:17-19` 仅 `:app` + `:mobile` | `settings.gradle.kts`、`app/build.gradle.kts:13` | 所有 gradle 命令用 `:app:`；Wear UI 阶段只改 `app/` 现有文件 |
| 2 | 本地 DB 文件是 `db/WristBriefDatabase.kt`（类 `WristBriefDatabaseHelper`），`wristbrief.db`，`DATABASE_VERSION = 4`，已有 `cloud_sync_outbox`、`transcript_cache` 表，`onUpgrade` 为 additive if-block | `mobile/.../db/WristBriefDatabase.kt:118-169` | Phase 5 本地迁移从 v5 起；不依据 D1 migration 编号推 Android 版本 |
| 3 | Cloud Sync 客户端代码已存在但休眠：`CloudSyncApi`（`/v1/sync/push|pull`）、`CloudSyncCoordinator`、`CloudSyncMerge`、`CloudSyncOutboxStore` 仅被测试引用；运行时 item-state 走 SharedPreferences + Wear Data Layer | `mobile/.../sync/CloudSyncApi.kt:35-96`、`CloudSyncCoordinator.kt:62`；grep 确认 MainActivity 未实例化 | Phase 4 = 接线现有 coordinator，不新建平行实现 |
| 4 | Feed store split-brain：`MainActivity.kt:115,123-129` 用 `SqliteMobileFeedStore`；`CategorizedFeedManagementDestination.kt:47-53` 自建 `MobileFeedManager(SharedPreferencesMobileFeedStore)`；inbox 同样模式（`MobileInboxRepository.kt:120`）；`LegacyDataMigration` 仅一次性 | 上述位置 | Phase 5 第一步修 split-brain，否则订阅增删与同步在两套数据上工作 |
| 5 | OPML 导入是单次 `importOpml()`，无 preview/apply 两段式 | `OpmlManagerExtensions.kt:10-33`、`OpmlManagementActions.kt:37-61` | Phase 6 mobile 侧需新增 preview→confirm→apply 流程，复用 `Opml.kt` parser |
| 6 | `:mobile` 无 Coil / 任何图片加载库（`AsyncImage`/Coil 零命中） | 两个 build.gradle.kts 依赖表 | Phase 7 引入 Coil（本仓库首个图片库）；不引入 WebView |
| 7 | 服务端 sync cursor 是毫秒时间戳而非 revision；`user_sync_cursors` 写而不读（pull 由 device 显式传 cursor）；push 的 idempotency key 仅批内唯一、未持久化去重 | `gateway/src/sync/d1SyncStore.ts:74-84`、`syncRoutes.ts:89-96,157-175` | Phase 4 接受 at-least-once + 每字段时间戳仲裁（天然幂等）；持久化幂等键记为已知缺口 |
| 8 | `MobileFeedParser.kt` 已读取 `content:encoded` 与 Atom `<content>`（first-non-blank 覆盖） | `MobileFeedParser.kt:40-57` | Phase 7 Level 1 部分就位；重点改为字段区分（full content vs summary）+ 结构化渲染 + 服务器提取 |
| 9 | gateway 路由为 `index.ts` 线性 if 链；错误形态 `{error:"code"}` + `X-Request-ID`；wire JSON camelCase（D1 列 snake_case）；测试 = vitest + `testDbHelper.ts` 的 `createMigratedTestDb()`（node:sqlite + D1 shim 含 batch） | `gateway/src/index.ts`、`sync/syncTypes.ts`、`testDbHelper.ts`、`d1Migrations.test.ts` | 新路由/新测试沿用既有风格与 helper |
| 10 | `AI_MODEL`/`AI_BASE_URL` 是 `wrangler.toml` 已提交的 `[vars]`（非 secret）；secret 名单见 `package.json` 的 `cloudflare.bindings` | `gateway/wrangler.toml:5-7`、`gateway/package.json` | Phase 8 的 legacy fallback 即此现状；provider config 化后 [vars] 退为默认值 |
| 11 | 无 rate limit 基础设施（唯一 429 是 managed-AI quota） | `gateway/src/index.ts:302` 及 grep | Phase 2 从零新增 rate limit（内存 per-isolate + D1 计数） |
| 12 | uidocs 无 loading/empty/error-state 规格（仅有语义色）；uidocs IA（Home/Explore/Ask AI/Library/playback）与实际 `MobileDestination { Today, Explore, Library, NowPlaying, AiProvider }` 不一致；`docs/next/04` 已列 Phone 导航为待办 | `uidocs/README.md:30`、`MobileDestination.kt:8-12` | Phase 9 的三态契约以实施方案 §6.5–6.7 为准；IA 增量对齐（Today→Home 展示层、AiProvider→Ask AI） |
| 13 | `ExploreDestination`/`NowPlayingDestination` 是 `MainActivity.kt` 内的私有 composable；账户切换清理仅清 transcript cache/cloud-sync outbox/prefs，不清 feeds/inbox/item states | `MainActivity.kt:518,609`；`AccountAuth.kt:44-65,177` | Phase 1/9 的导航改动落在 MainActivity 内部；Phase 3 账户切换沿用现有清理策略（Google/Email 共用，不建两套） |

## 4. docs 与代码的冲突记录（实施时以"明确、最新、可验证的需求文档"为产品目标，不偷偷改 API）

1. `docs/AI_GATEWAY.md` 称 provider base URL/model "通过 deployment secrets/bindings 配置"，实际 `AI_BASE_URL`/`AI_MODEL` 是明文 `[vars]`。Phase 8 落地后以 D1 config + Worker secret 为准，届时同步修订该文档。
2. `docs/EXECUTION_STATUS.md` 将 Cloud Sync / Liquid Glass 标为 COMPLETED，与本审计及 `docs/REQUIREMENT_AUDIT_2026-09-12.md` 证据不符（Cloud Sync 客户端休眠、视觉未验收）。后续阶段完成时按实际状态改写，不沿用过度声明。
3. uidocs Phone IA 与 `MobileDestination` 现状不一致（无 Home/Ask AI 命名、NowPlaying 为第五 tab）——Phase 9 处理，非紧急冲突。
4. uidocs WEAR spec 建议的 `wear/ui/liquidglass/` 包路径与实际 `app/src/main/java/ink/underflo/wristbrief/ui/liquidglass/` 不符 —— 文档描述的是目标布局，实施保持现有包路径不搬迁。

## 5. 后续阶段既定决策（本审计冻结）

1. **密码 KDF**：优先 `hash-wasm` 的 Argon2id（WASM，Workers 可运行）——gateway 首个运行时依赖；若打包/兼容性问题在验证中出现，回落序为 `@noble/hashes` scrypt → WebCrypto PBKDF2（高迭代），并在本文档追加记录。绝不使用裸 SHA-256。
2. **first-admin 竞态**：D1 单语句 CAS（`UPDATE bootstrap_state ... WHERE first_admin_user_id IS NULL AND web_registration_enabled=1`，判受影响行数），候选用户创建与 CAS 同 batch，败者回滚；不做 count-then-insert。
3. **邮箱身份**：`(provider='email', provider_subject=email_credentials.id)`，email 字符串只存在 `email_credentials.normalized_email`；绝不按 email 自动合并 Google 与密码身份。
4. **migration 编号**：0009 = admin+email（已定）；article 与 provider 的编号在落地时按合并顺序分配（字典序应用需每个合并点单调）；sync integrity migration 仅在确认 0008 缺口后建立。
5. **SSRF 边界**：Workers fetch 无法在请求前 resolve DNS；实施 hostname 策略 + 字面私有/保留 IP 拒绝 + `redirect:"manual"` 每跳重验 + 流式限长；DNS rebinding 残余风险如实写入文档，不虚假声称完全防护。OPML 预览、文章抓取、图片代理共用同一 `SafeRemoteFetcher`。
6. **Secret 管理**：AI provider secret 采用预注册 slot 模式（`AI_PROVIDER_SECRET_1..10`），D1 只存 `secret_ref`；Admin API/HTML/日志均不可读 secret 内容（仅 version/last changed）。
7. **Sync 幂等**：接受 at-least-once + 每字段独立时间戳仲裁；`user_item_states` 不新增 tombstone 列（无删除语义需求）。
8. **验证边界**：每阶段跑 `npm test`/`typecheck`（gateway）、`:mobile`/`:app` 的 `testDebugUnitTest`/`lintDebug`、`python scripts/release_guard.py`；真实 Google/Play/邮件投递/Cloudflare 生产/真机手势等只能标 `implemented / unit-tested / static-reviewed`。历史审计已记录本机 Android Gradle 可能环境阻断（loopback 连接错误）——如复现，区分环境与代码失败并保存错误输出。

## 6. 验证方式

本审计基于：三路并行代码探索（gateway / mobile / docs+uidocs+Wear）+ 抽读关键文件。所有 file:line 引用对应 `d6397fd`。审计本身不改动任何产品源码；第 3、5 节作为 Phase 1–9 的输入基线，后续各阶段开始时以当时 HEAD 复核相关条目是否仍成立。
