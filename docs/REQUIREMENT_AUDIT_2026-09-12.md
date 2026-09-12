# WristBrief 文档需求逐项核查报告

核查日期：2026-09-12  
仓库：`G:\Projects\wristbrief`  
当前提交：`375e5ee`（`main`）  
核查原则：只核查和记录，不修改 `app/`、`mobile/`、`gateway/` 或其他产品源码。新增的改进项仅放在 `docs/next/`。

> 快照说明：第 1～7 节保留的是 2026-09-12 首轮审查时点的原始核查结论与问题基线；后续源码修复和复核结果以第 8 节及 `docs/next/` 的当前状态为准。

## 1. 结论摘要

当前仓库可以确认的是：本地 RSS/Podcast、Wear 基础界面、Gateway AI/会员基础、D1 迁移和若干同步/转录基础代码已经存在，Gateway 单元测试与类型检查通过。

当前不能确认的是：云同步和共享转录的端到端用户功能、Phone ↔ Wear ↔ 第二台 Phone 的闭环、R2/Queue 等生产存储与任务链路、Play/RTDN/D1 生产配置、配对真机行为，以及完整 Liquid Glass 视觉验收。`docs/EXECUTION_STATUS.md` 把 Cloud Sync 和 Liquid Glass 标成 “COMPLETED”，与源代码和本轮验证证据不一致，应按“部分实现 / 未验证”处理。

最需要优先处理的安全问题是：已登录用户之间的转录任务/工件访问缺少对象级授权；客户端可影响共享策略，而且生成工件路径将共享策略写死为 `PUBLIC_REUSE`；账户删除没有清理 0008 新增的云同步、工件、任务和额度表。这些问题先于发布验收。

## 2. 证据等级

| 等级 | 含义 | 本轮是否具备 |
|---|---|---|
| L1 | 源码/配置存在，静态结构可读 | 有 |
| L2 | 本机测试或静态检查通过 | Gateway 有；Android 本轮受环境阻断 |
| L3 | 当前提交的 CI 通过 | 未拉取/未复核 |
| L4 | 配对手机、Wear、旋转输入、进程重建等真机/模拟器验证 | 本次按要求不执行 |
| L5 | D1、R2、Queue、Play、RTDN、Provider 等生产配置与真实请求验证 | 无 |

历史文档中的 “L2/L3/L4/L5” 不自动等于本轮证据；本报告只把能在当前工作区复核的内容标为已证实。

## 3. 本轮可复现验证

| 检查 | 结果 | 说明 |
|---|---|---|
| `npm test --prefix gateway` | 通过 | 26 个测试文件、168 个测试通过 |
| `npm run typecheck --prefix gateway` | 通过 | `tsc --noEmit` 无错误 |
| `python scripts/release_guard.py` | 通过 | Android 安全、跨设备打包、字符串 parity、D1 migration 检查通过 |
| `gradlew.bat testDebugUnitTest --no-daemon --offline` | 环境阻断 | Java 17、离线模式仍报 `java.io.IOException: Unable to establish loopback connection`；不能据此判定 Android 源码失败 |
| 当前 CI | 未验证 | 未在本轮获取对应提交的 GitHub Actions 结果 |
| 视觉/交互 | 未验证 | 本次明确不启动实机、模拟器或虚拟机；按 Product Design audit 要求不把源码静态检查写成视觉通过 |

## 4. 文档逐一核查

| 文档 | 文档要求/声明 | 与当前证据的关系 | 结论 |
|---|---|---|---|
| `README.md` | Wear OS-first RSS/Podcast、Phone companion、Gateway、Data Layer、验证命令 | 模块和主要路径存在；本轮 Gateway 检查通过，Android 构建未能复核 | 基础方向成立，端到端未证实 |
| `docs/EXECUTION_STATUS.md` | Batch 0–8、Cloud Sync、Liquid Glass 均标为 COMPLETED/L2 | 其中 Cloud Sync 客户端 coordinator、转录实际 API/R2/Queue、视觉验收证据不足；记录的 Android build 与本轮环境不一致 | 过度声明，需要重写状态 |
| `docs/ROADMAP.md` | P0–P8 多数完成；P5 Anthropic 未勾选；P7 声称 read/saved sync 完成但又注明 v1 字段不是端到端 | Gateway 已有 Anthropic provider；同步仍只有基础件 | 存在文档漂移和自相矛盾 |
| `docs/CLOUD_SYNC_AND_SHARED_TRANSCRIPT_ARCHITECTURE.md` | 明确写明是目标架构；发布接受条件全部未勾选 | 当前实现符合“部分基础设施”，不符合全部接受条件；尤其没有 R2/Queue 与移动端闭环 | 该文档的目标边界可信，应作为验收基准 |
| `docs/AUTH.md` | Google sub、D1 session、Google auth；后续步骤仍列 sign-out、Credential Manager、删除/绑定 | 当前源码已有 logout、删除、migration grant 等基础路径，但外部配置和全流程未验证 | “Planned next steps” 过时；保留为未验证项 |
| `docs/MEMBERSHIP.md` | 账户绑定、购买归属、RTDN、删除策略 | 代码已有本地基础和测试；旧文档仍写“future explicit link”，删除策略在 0008 数据上不完整 | 本地实现部分存在，生产/删除不满足 |
| `docs/BILLING.md` / `docs/BILLING_CHECKLIST.md` | Play/RTDN/GCP/Cloudflare 的合同和部署清单 | 是外部操作清单，不等于已执行；本轮未接入 Play、Pub/Sub、D1 | 未验证 |
| `docs/DEPLOYMENT.md` | D1、Worker、生产配置部署步骤 | `wrangler.toml` 仍有占位 database id；未执行生产部署 | 未完成生产配置 |
| `docs/AI_GATEWAY.md` | provider allowlist、超时、配额、BYOK、无任意上游代理 | provider 与配额源码存在，Gateway 测试通过 | 本地基础已实现；生产 provider/secret 未验证 |
| `docs/SECURITY.md` | 不带 secret、固定上游、限流/边界、server-owned entitlement | 主要不变量有实现；转录授权、share policy、删除清理仍有缺口 | 部分满足，不能称安全发布 |
| `docs/RELEASE_CHECKLIST.md` | CI、签名 Play、RTDN、配对设备、安全 smoke 才能放行 | 本轮只完成 Gateway/release guard；其余没有证据 | 未通过发布门槛 |
| `docs/STATIC_REVIEW_2026-09-11.md` | 记录旧一轮静态修复和待办 | 其中 runtime session、read/saved sync、quota race、RTDN dedup 等部分已在当前源码出现；lockfile/外部配置仍是问题 | 历史审查，不可直接作为当前待办真相 |
| `docs/24H_REVIEW.md`、`docs/NEXT_24_HOURS*.md` | 历史 24-slot 完成记录 | 与当前提交和本轮环境证据没有一一对应关系 | 仅作历史记录 |
| `uidocs/WEAR_LIQUID_GLASS_SPEC.md` | Stage 3 必须只有 Library / Now Playing / Settings；黑底、无渐变 | `MainActivity.kt` Stage 3 有 Saved、Now Playing、Feeds、Refresh 四项；无渐变调用的静态检查通过 | 信息架构不符合；视觉仍未验证 |
| `uidocs/PHONE_LIQUID_GLASS_SPEC.md` | 五步 OOBE、Home/Explore/Ask AI/Library/Now Playing、无装饰渐变 | OOBE 实际四页；`MobileDestination` 只有 Today/Library/AiProvider；Onboarding artwork 使用彩色 primary/secondary arcs | 部分实现 |

## 5. 功能需求核查矩阵

### 5.1 本地内容、播放和 Wear 基础

- **状态：部分已实现（L1，历史 L2；本轮 Android L2 未复核）。**
- `app/`、`mobile/` 中存在 RSS/Podcast、SQLite、Media3、Today/Library、Wear Data Layer 和播放相关路径；`scripts/release_guard.py` 通过。
- 由于 Gradle 在当前 Windows 会话被 loopback 错误阻断，不能重新确认 Android JVM 测试和 APK 组装；需要在 CI 或可用 JDK/Gradle 环境复跑。

### 5.2 AI provider、缓存和配额

- **状态：Gateway 本地基础已实现（L1/L2）。**
- `gateway/src/provider.ts` 有 OpenAI-compatible、OpenRouter、Gemini、Anthropic adapter 和固定 HTTPS host policy；`gateway/src/membership.ts` 有原子额度预留；summary cache/lock 与测试存在。
- `docs/ROADMAP.md:74-81` 仍将 Anthropic 标为未勾选，需与源码状态对齐。
- 生产 provider URL、密钥、D1 membership store 和真实上游调用未验证。

### 5.3 Google 身份、会员和 Play/RTDN

- **状态：本地协议和存储基础已实现；生产链路未验证。**
- Google `sub`、哈希 session、purchase hash、RTDN re-query、migration grant 等路径存在并有 Gateway 测试。
- `docs/AUTH.md:31`、`docs/MEMBERSHIP.md:68` 仍正确提醒 D1/外部配置未生产化，但“planned/future”段落已部分过时。
- 需要真实 Play internal test、RTDN Pub/Sub、同一签名身份和跨设备 restore 才能升级到 L4/L5。

### 5.4 账户级 Cloud Sync

- **状态：部分实现，不是端到端功能。**
- 服务端：`0008_cloud_sync_and_content_registry.sql`、`gateway/src/sync/d1SyncStore.ts`、`/v1/sync/push`、`/v1/sync/pull` 存在；请求按认证 `user.id` 写入。
- 客户端：有 `CloudSyncMerge.kt`、`CloudSyncOutboxStore.kt` 和单测，但没有找到具体 `CloudSyncApi`、网络 coordinator、启动/恢复同步、pull 后写入本地订阅/阅读/播放状态的闭环。
- outbox 只有 `retryCount`，`getPending()` 按创建时间取数据，没有 next-attempt、指数退避、jitter 或网络调度；这是架构文档要求的离线可靠性缺口。
- 因而“D1 表和冲突 resolver 存在”不能等于“换手机可恢复、离线进程死亡可恢复、Phone/Wear/Phone B 收敛”。

### 5.5 Content Code、共享转录和持久化工件

- **状态：部分实现，当前更接近 metadata foundation。**
- Content resolver、alias hash、Content Code、quota 0/0.2/1.0 和 job metadata 存在；Gateway tests 通过。
- `TranscriptRepository.kt` 只定义 `TranscriptGatewayApi` 接口，没有找到生产实现；`TranscriptViewerDestination` 存在，但 `mobile/MainActivity.kt` 没有把它接入主导航；播放器的 `onOpenTranscript` 仍是可选回调，未看到调用链。
- `completeJobWithArtifact()` 只把 object key 写入 D1 metadata；`wrangler.toml` 没有 R2/Queue binding，也没有转录 worker/对象写入路径。架构文档的 R2 durable artifact、后台 job、真实读取尚未完成。

### 5.6 Wear Liquid Glass

- **状态：部分实现。**
- `MainActivity.kt:333-375` 有三段纵向结构、Material 3/Wear 组件、黑底 token 和无渐变静态迹象。
- 但 `uidocs/WEAR_LIQUID_GLASS_SPEC.md:75-80` 要求 Stage 3 恰好三行 Library / Now Playing / Settings；当前实际是 Saved、Now Playing、Feeds、Refresh 四项。该差异是确定的功能/信息架构偏差，不需要视觉截图即可确认。
- 旋钮、round clipping、字体放大、视觉密度和真实小/大圆表盘属于 L4 范围；本次按要求不执行，不把它们伪装成已验证。

### 5.7 Phone Liquid Glass

- **状态：部分实现。**
- Today/Library/播放器已有 `GlassSurface`、`GlassBottomBar` 等 token 路径；源码静态搜索未发现 `linearGradient`/`radialGradient`/`sweepGradient` 调用。
- `Onboarding.kt:66-71` 只有 Welcome/Sources/Devices/Done 四页，而规格要求五步 Welcome/Interests/Add content/AI features/Ready。
- `Onboarding.kt:194-227` 使用彩色 `primary`、`secondary`、`primaryContainer` 绘制背景 artwork，与“无装饰彩色背景/无渐变”目标不一致。
- `MobileDestination.kt:3-11` 只有 Today、Library、AiProvider，没有 Explore 和独立 Now Playing destination；转录查看器也未接入主导航。

### 5.8 发布与生产验收

- **状态：未完成/未验证。**
- `gateway/wrangler.toml:9-13` 仍是 D1 binding 基础和占位 database id；没有 R2/Queue binding。
- `gateway/package-lock.json` 不存在，当前依赖虽可在本机 `node_modules` 下运行，但 clean install 的可复现性仍是阻塞项。
- 未验证 CI 当前提交、Play 签名、RTDN、生产 D1/R2/Queue、真实 provider 和灾难/删除流程；配对设备与模拟器/虚拟机测试按本次要求明确不执行。

## 6. 已确认的改进项

### 安全（优先级 P1/P2；需在发布前处理）

1. **SEC-01：转录 job/artifact 缺少对象级授权（P1）。**
   - `gateway/src/index.ts:229-245` 只判断请求者“已登录”，调用 `handleTranscriptStatus(jobId, env)` 和 `handleTranscriptGet(contentCode, env)` 时没有传入 `user.id`。
   - `gateway/src/artifacts/transcriptRoutes.ts:46-69` 按 content code 直接返回 preferred artifact；`artifactStore.ts:79-92` 的查询没有 `share_policy` 或 `user_artifact_access` 条件。
   - `transcriptRoutes.ts:72-96` 按任意 job id 返回状态，也没有校验 job 所属用户。
   - 结果：任一登录用户可探测/读取另一账户的转录 metadata、对象 key 或 job 状态；即使对象下载还依赖后续网关，这已经违反账户边界。

2. **SEC-02：共享策略由客户端影响，且生成工件写死为公共复用（P1）。**
   - `contentResolver.ts:111-120` 直接使用 `input.sharePolicy`，`transcriptService.ts:42-51` 将请求体的 share policy 传入。
   - `transcriptService.ts:172-188` 创建 artifact 时固定 `sharePolicy: "PUBLIC_REUSE"`。私有请求不能保持私有。
   - `contentRoutes.ts:16-29` 与 `transcriptRoutes.ts:21-38` 直接 `request.json()`，也没有对媒体 URL 做协议/主机/私有资源策略校验。
   - 结果：私有内容可能进入公共 artifact 选择路径；需由服务端根据来源/账户策略决定可复用性，不能信任客户端字段。

3. **SEC-03：账户删除没有覆盖 0008 云数据（P1）。**
   - `gateway/src/authServer.ts:131-147` 只撤销 session、删除 identity/billing binding，更新/删除 `users`、`identities`、`sessions`、`managed_ai_usage`。
   - `gateway/migrations/0008_cloud_sync_and_content_registry.sql:2-169` 新增 `devices`、`user_subscriptions`、`user_item_states`、`user_playback_progress`、`user_sync_cursors`、`user_artifact_access`、`artifact_jobs`、`credit_transactions` 等表，且没有足够的 FK cascade 作为删除保证。
   - 结果：账户删除后仍可能残留同步状态、任务、工件授权和额度记录，违反架构文档的 deletion/takedown 要求。

4. **SEC-04：请求体/限流硬化缺口（P1，需补测试确认）。**
   - content resolve、sync push/pull、transcript request 路径没有复用 `index.ts` 中 summary/auth 的统一 body-size parser；sync/transcript 路由直接解析 JSON。
   - 需补充 per-user/IP rate limit、请求体上限、数组/字段数量限制以及 abuse telemetry；本项当前标为 hardening gap，不把它夸大为已被线上利用的漏洞。

### 功能与设计（优先级 P1/P2）

5. **FUNC-01：补齐 Cloud Sync 客户端网络闭环。** 目前只有 D1 route、merge 和 outbox；需要 API client、账号/设备注册、pull/push coordinator、进程重启恢复、退避、cursor 持久化、Data Layer 与本地 DB 的明确 ownership。

6. **FUNC-02：补齐转录实际存储和消费链路。** 需要 R2/Queue 或等价持久化/worker、读取权限、对象内容返回、失败/重试/退款闭环，以及 `TranscriptGatewayApi` 的真实实现和 viewer/player 导航接入。

7. **DES-01：Wear Stage 3 对齐规范。** 删除或重新归类 Saved/Feeds/Refresh，使 More 只暴露 Library / Now Playing / Settings 三个主要入口；刷新应是状态动作而不是第四个导航 row。

8. **DES-02：Phone OOBE 对齐五步规格。** 将当前四页拆为 Interests、Add content、AI features、Ready，并用可读的中性玻璃表面替代彩色旋转 artwork；每步保留清晰主操作。

9. **DES-03：补齐 Phone IA 与转录入口。** 明确 Explore、Now Playing、Ask AI 的 destination/route ownership，接入 transcript viewer，避免只有接口和孤立 Composable。

10. **DOC-01：建立文档状态单一真相。** 将 `EXECUTION_STATUS.md` 的 Cloud Sync/Liquid Glass 从 COMPLETED 调整为 “部分实现 / L1-L2 / L3-L5 未验证”；清理 `STATIC_REVIEW_2026-09-11.md`、`AUTH.md`、`MEMBERSHIP.md` 中已过时的 “future/planned” 条目；同步修正 ROADMAP 的 Anthropic 与 P7 文案。

## 7. 结论

本项目不是“所有 docs 功能都已完成”，而是“本地基础和 Gateway 测试较完整，云同步/转录/视觉交互/生产发布仍处于分段实现”。在 SEC-01～03、FUNC-01～02 和 L5 外部配置证据补齐前，不建议使用“生产就绪”“多设备同步完成”或“共享转录完成”的表述；L4 实机/模拟器/虚拟机验证按本次要求不执行。

下一步工作单已拆分到 [`docs/next/`](./next/README.md)。

## 8. 二次修复与复核状态（2026-09-12）

- SEC-01/03 及 SEC-02 的默认私有、对象级授权部分已修复：D1 migration 0008 用户范围删除、已配置 transcript storage 的私有对象清理及公开 artifact creator 脱敏也已落地。`PUBLIC_REUSE` 目前仍可由请求输入显式触发，服务端尚未实现基于来源、域名或签名 URL 的资格判定，因此 SEC-02 仍有条件性风险并保持 PENDING。
- Legacy `GATEWAY_TOKEN` 认证已增加账户状态检查，持久化用户为 `deleted` 时拒绝访问；无历史用户行时保留兼容迁移路径。
- 移动端已补登出、删除和直接切换账户时的 transcript cache、Cloud Sync outbox、cursor/preferences 清理。
- 最新本地证据：Gateway 27 个测试文件、175 个测试通过，typecheck 与 `release_guard` 通过。
- Android host Gradle 因 `Unable to establish loopback connection` 阻断，不能把本轮 Android 测试或组装写为通过；L3 CI、L4 设备/模拟器/虚拟机和 L5 生产配置均未验证。
- 仍待完成：Queue consumer/audio worker、Cloud Sync 应用生命周期与完整账号隔离、删除过程的事务化或非事务失败恢复，以及生产 D1/R2/Queue 等外部配置和端到端验证。

因此，第 6 节的旧发现是首轮历史基线，判断当前状态时必须与本节及 [`docs/next/`](./next/README.md) 的状态说明一并阅读。
