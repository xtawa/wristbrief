# WristBrief 生产部署教程

本文是 Gateway、Google OAuth、Google Play 订阅、RTDN、Android 构建和发布验收的统一操作入口。它描述的是仓库当前代码真实支持的边界，不把本地测试或源码存在误写成生产集成已完成。

## 0. 先确认边界

当前仓库已经具备：

- Cloudflare Worker `wristbrief-gateway` 的 Wrangler 配置、D1 迁移、R2 transcript bucket 和 Queue producer binding。
- Gateway 的 HTTPS、账户 session、Google ID token 验证、服务端 Play purchase verification、RTDN OIDC 验证、D1 entitlement 和 AI provider allowlist。
- Android 手机端 Google sign-in、服务器 restore、Play Billing Library 8.3.0 和服务器拥有 entitlement 的 UI。
- 转写 job 的配额 reservation/commit/release、失败补偿、同一创建者重试和 R2 对象清理。

当前不能宣称已经完成：

- transcript Queue consumer、音频下载、Whisper 调用和 Worker 内的 `completeJobWithArtifact` 生产 worker。现在只会把 job 写入 D1，并在绑定存在时发送 Queue 消息；没有 consumer 时 job 会停在 `queued`。
- 真实 Cloudflare、Google Cloud、Play Console、Pub/Sub、Google OAuth、签名 APK、配对手机/手表和生产 provider 的端到端验证。
- Wear 端的 WristBrief session bridge。不要把旧的全局 `GATEWAY_TOKEN` 放进 Wear APK。

因此，第一次上线应先启用账户、OAuth、AI summary 和 Play billing；transcript 功能必须在 consumer/audio worker 完成并单独验收后再对外开放。

## 1. 准备环境

需要：

- Cloudflare 账户，以及有 Workers、D1、R2、Queues 权限的 API 登录。
- Node.js 20 或更高版本、npm、PowerShell 7（Windows 可使用系统 PowerShell 运行脚本）。
- Android Studio/JDK 17。仓库验证过的 JDK 路径示例是 `G:\AndroidStudio\jbr`。
- Google Cloud 项目、Play Console 应用和一个 HTTPS 域名。

不要把 API key、Google service-account 私钥、ID token、Play purchase token 或 session bearer 提交到 Git、日志和截图中。

安装 Gateway 依赖并做本地检查：

```powershell
Set-Location G:\Projects\wristbrief\gateway
npm ci
npm run typecheck
npm test
```

上述命令不启动 AVD、模拟器或 Android device tests。

## 2. Cloudflare 资源

`gateway/wrangler.toml` 当前声明：

| 绑定 | 资源名 | 用途 |
| --- | --- | --- |
| `ACCOUNT_DB` | `wristbrief-account-db` | 账户、session、membership、billing、sync、content registry、jobs、quota |
| `TRANSCRIPTS_BUCKET` | `wristbrief-transcripts` | transcript JSON/text/segments 对象 |
| `TRANSCRIPT_QUEUE` | `wristbrief-transcript-jobs` | transcript job producer；当前没有 consumer |

推荐使用仓库脚本，首次部署可以按名称自动创建资源：

```powershell
Set-Location G:\Projects\wristbrief
Copy-Item gateway\.env.cloudflare.example gateway\.env.cloudflare.local
# 只在本机编辑 gateway\.env.cloudflare.local，不要提交它
.\scripts\deploy_gateway.ps1 -HealthUrl "https://api.example.com"
```

脚本会：

1. 执行 `npm ci` 和 Gateway typecheck（除非传 `-SkipInstall`/`-SkipTypecheck`）。
2. 检查 Wrangler 登录状态，必要时打开 `wrangler login`。
3. 通过 `--x-provision --x-auto-create` 查找或创建 D1、R2、Queue。
4. 部署 Worker，并对 `ACCOUNT_DB` 执行远端迁移。
5. 使用 `wrangler secret bulk` 上传本地 secrets 文件，但不打印值。
6. 可选检查 `GET /health` 是否返回 `{ "ok": true }`。

已经在 Cloudflare 准备好资源时，可以使用：

```powershell
.\scripts\deploy_gateway.ps1 -SkipInstall -SkipLogin -HealthUrl "https://api.example.com"
.\scripts\deploy_gateway.ps1 -SkipProvisioning -SkipMigrations -SkipSecrets -SkipTypecheck
.\scripts\deploy_gateway.ps1 -DryRun -SkipInstall -SkipLogin -SkipTypecheck
```

`-SkipMigrations` 只适用于远端 schema 已经确认与 `gateway/migrations/` 一致的情况。迁移前先备份或确认 D1 恢复策略；不要用 `wrangler d1 migrations apply --remote` 以外的方式手工跳过版本。

如果不使用脚本，也可以在 Gateway 目录执行：

```powershell
npx wrangler login
npx wrangler deploy --config wrangler.toml --x-provision --x-auto-create --keep-vars
npx wrangler d1 migrations apply ACCOUNT_DB --remote --config wrangler.toml
```

Cloudflare Dashboard 的 Workers Build 根目录应设为 `gateway`，安装命令使用 `npm ci`，部署命令使用 `npm run deploy`。第一次自动创建资源可能产生 Cloudflare 费用，执行前确认账户和预算。

## 3. Worker variables 与 secrets

### 3.1 明文 variables

这些值可以在 `gateway/wrangler.toml` 的 `[vars]` 或 Dashboard Variables 中配置：

- `AI_BASE_URL`：OpenAI-compatible provider 的 HTTPS base URL。
- `AI_MODEL`：默认模型名。
- `AI_PROVIDER`：默认 `openai-compatible`，也可以选择已配置的 D1 provider id。
- `AI_FALLBACK_PROVIDER`：可选的 fallback provider id。
- `AI_ALLOWED_HOSTS`：允许的 provider hostname，逗号分隔；不要把它设成任意域名。
- `AI_PROVIDER_TIMEOUT_MS`：请求超时，代码会限制最大值。
- `AI_PROVIDER_MAX_RETRIES`：重试次数，代码会限制最大值。
- `PUBLIC_REUSE_FEED_HOSTS`：允许进入公共 transcript reuse 池的 HTTPS feed hostname，逗号分隔；空值表示全部按 `PRIVATE_ACCOUNT` 保存。
- `OPENROUTER_MODEL`、`GEMINI_MODEL`、`ANTHROPIC_MODEL`：使用 legacy env provider 时的模型名。
- `PLAY_PACKAGE_NAME`：必须是 `ink.underflo.wristbrief`。
- `PLAY_SUBSCRIPTION_PRODUCT_IDS`：逗号分隔的 Play subscription product ID allowlist。
- `GATEWAY_PUBLIC_BASE_URL`：邮件验证/密码流程生成链接所使用的 HTTPS origin。
- `PUBSUB_PUSH_AUDIENCE`：RTDN push OIDC token 的精确 HTTPS audience。

`AI_BASE_URL`、`AI_MODEL` 是地址和模型配置，不是密钥；AI provider key 必须放 secrets。D1 provider admin 模式下，数据库只保存 provider 配置和 `secret_ref`，真实 key 放 `AI_PROVIDER_SECRET_1` 到 `AI_PROVIDER_SECRET_10`。

### 3.2 Worker secrets

至少按实际启用的功能配置：

```text
AI_API_KEY
GOOGLE_OAUTH_CLIENT_ID
GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL
GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY
PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL
```

可选兼容/功能 secrets：

```text
GATEWAY_TOKEN
OPENROUTER_API_KEY
GEMINI_API_KEY
ANTHROPIC_API_KEY
AI_PROVIDER_SECRET_1 ... AI_PROVIDER_SECRET_10
ADMIN_RECOVERY_SECRET
```

其中 `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` 支持真实 PEM 换行或字面量 `\n`。服务端只保存 purchase token 的 SHA-256，不保存 raw token。

本地上传示例：

```powershell
npx wrangler secret bulk gateway\.env.cloudflare.local --name wristbrief-gateway --config gateway\wrangler.toml
```

上传后用 Dashboard 或 `wrangler secret list` 检查名称，不要把 secret 值输出到终端记录或 CI 日志。

## 4. 自定义域名和 HTTPS

1. 在 Cloudflare DNS 中把 `api.example.com` 放到同一个 Cloudflare zone。
2. Workers & Pages -> `wristbrief-gateway` -> Settings -> Domains & Routes 添加 custom domain。
3. 确认外部访问使用 HTTPS，证书状态为 active。
4. 访问 `https://api.example.com/health`，确认 HTTP 200 和 `{ "ok": true }`。
5. 将完全相同的 origin 写入 Android `wristbrief.gatewayBaseUrl`。

不要让手机把 Gateway 配成公共 HTTP。API Server 的明文 HTTP opt-in 是另一项目的明确例外，不适用于 WristBrief Gateway；生产 Gateway、OAuth、billing、restore、RTDN 都要求 HTTPS。

## 5. Google OAuth

### 5.1 Google Cloud

1. 在 Google Cloud Console 创建或选择生产项目。
2. 配置 OAuth consent screen，填写应用名称、支持邮箱、开发者联系信息和隐私政策 URL。
3. 创建 Web application OAuth client，记录 Web client ID。
4. 如使用 Play signing / internal testing，按实际发布证书配置 Android OAuth client；包名必须是 `ink.underflo.wristbrief`，SHA-1/SHA-256 必须对应上传签名。
5. Gateway secret `GOOGLE_OAUTH_CLIENT_ID` 必须与 Android `wristbrief.googleWebClientId` 的 Web client ID 相同。

Gateway 校验 ID token 的 issuer、audience、签名和 immutable Google `sub`，不以 email 作为账户主键。首次登录会创建内部 WristBrief user 和 session；删除/登出后 session 由 Gateway 负责撤销。

### 5.2 Android Gradle properties

在本机 `~/.gradle/gradle.properties` 或 CI secret-backed Gradle properties 中配置：

```properties
wristbrief.gatewayBaseUrl=https://api.example.com
wristbrief.googleWebClientId=1234567890-example.apps.googleusercontent.com
wristbrief.billingSubscriptionProductIds=wristbrief_pro_monthly,wristbrief_pro_yearly
```

这些值不是 server private key。不要把 `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` 或 `AI_API_KEY` 写入 Gradle properties。

`mobile/build.gradle.kts` 会把上述三个 property 编译为：

- `BuildConfig.GATEWAY_BASE_URL`
- `BuildConfig.GOOGLE_WEB_CLIENT_ID`
- `BuildConfig.BILLING_SUBSCRIPTION_PRODUCT_IDS`

空值会让账户、billing 或 Gateway 功能显示未配置，不能作为生产构建。

## 6. Google Play 订阅和内购

### 6.1 Play Console

1. 创建应用，包名使用 `ink.underflo.wristbrief`。
2. 创建 subscription product，例如 `wristbrief_pro_monthly`、`wristbrief_pro_yearly`。
3. 每个 product 创建至少一个 active base plan；按需要配置 offer、试用和地区价格。
4. 在 `PLAY_SUBSCRIPTION_PRODUCT_IDS` 中只写实际允许的 product ID。Gateway 会拒绝未 allowlist 的 product。
5. 上传同一 package/signing identity 的 mobile 和 Wear 多 APK。当前 version-code lane 是 mobile `2xxxxxx`、Wear `3xxxxxx`，不要再次上传旧的 `versionCode=1` 包。
6. 创建 Internal testing track，添加 license tester；不要直接用 production 购买验证未发布的版本。

### 6.2 Google Play Developer API

1. 在 Google Cloud 启用 Android Publisher API。
2. 创建单独 service account，不要复用应用运行账号。
3. 在 Play Console -> Users and permissions 邀请该 service account。
4. 只授予读取订阅购买所需的最小权限，至少覆盖 Android Publisher API 的 subscription verification；不授予发布、财务或用户管理权限。
5. 将 service account email 写入 `GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL`，私钥作为 `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` secret。

Gateway 固定调用 `purchases.subscriptionsv2.get`，会检查 package、allowlisted product、subscription state 和 expiry。客户端声称已购买不构成 entitlement；restore 成功前客户端不会把未确认购买当作服务器授权。

状态规则：

| Play 状态 | Gateway entitlement |
| --- | --- |
| active | PRO |
| grace | PRO |
| canceled 且未来 expiry | PRO，直到 expiry |
| canceled 缺少/非法 expiry | FREE（fail closed） |
| on hold / expired / revoked | FREE |

### 6.3 RTDN / Pub/Sub

1. 在 Google Cloud Pub/Sub 创建 RTDN topic。
2. 在 Play Console 的 Monetization setup / Real-time developer notifications 中绑定该 topic。
3. 创建 push subscription，endpoint 为 `https://api.example.com/v1/billing/rtdn`。
4. 为 push subscription 选择专用 user-managed service account，开启 OIDC token。
5. OIDC audience 必须是完整 endpoint URL，并与 Gateway 的 `PUBSUB_PUSH_AUDIENCE` 完全一致。
6. 将该 service account email 配置为 `PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL`。
7. 授予 Pub/Sub service agent 为该 service account mint OIDC token 所需的权限。
8. 确认 push 请求带 `Authorization: Bearer <JWT>`；Gateway 固定使用 Google JWKS、issuer、audience、email、`email_verified`、`iat` 和 `exp` 做校验。

RTDN 只是变化通知。Gateway 会先根据 token hash 查找已绑定用户，再重新调用 Android Publisher API；RTDN payload 自己不能授予 PRO。`messageId` 去重后，验证或 D1 暂时失败会释放 dedup claim，让 Pub/Sub 重试。

### 6.4 购买验收

使用 license tester 在 Internal testing 中逐项验证：

- 新购、购买回调、服务器 restore、acknowledge。
- App 重装后 restore；同一 token 重复 restore 必须幂等。
- 同一 token 尝试绑定另一个 WristBrief user，必须返回 conflict。
- 取消但未到期、grace、account hold、expired、revoked。
- RTDN 重复投递、testNotification、错误 audience、错误 service account。
- D1 entitlement 与 `/v1/me` 返回的 plan、expiresAt、managedAiQuota 一致。

## 7. Android 构建与发布

设置 JDK 17 后，只做必要的 host 编译检查：

```powershell
$env:JAVA_HOME = "G:\AndroidStudio\jbr"
Set-Location G:\Projects\wristbrief
.\gradlew.bat :mobile:compileDebugKotlin --no-daemon
```

需要生成 APK/AAB 时，再按发布流程执行：

```powershell
.\gradlew.bat :mobile:bundleRelease --no-daemon
.\gradlew.bat :app:bundleRelease --no-daemon
```

发布前检查：

- mobile/Wear `applicationId` 都是 `ink.underflo.wristbrief`。
- release 签名证书、Play package、OAuth Android client 和 Data Layer identity 完全一致。
- `wristbrief.gatewayBaseUrl` 是生产 HTTPS origin。
- `wristbrief.googleWebClientId` 与 Worker `GOOGLE_OAUTH_CLIENT_ID` 匹配。
- `wristbrief.billingSubscriptionProductIds` 与 Worker `PLAY_SUBSCRIPTION_PRODUCT_IDS` 逐字匹配。
- APK/AAB 中不存在 AI key、Play service-account private key、Gateway bearer 或 raw purchase token。

本仓库工作流明确不启动 AVD、模拟器或 connected/device tests。真实设备验收需要单独的签名安装、Play license tester、OAuth client 和 paired phone/Wear 环境；未执行时只能标记为 `UNVERIFIED`。

## 8. 发布后健康检查

```powershell
Invoke-WebRequest https://api.example.com/health
```

然后在不打印敏感内容的前提下检查：

1. Cloudflare Worker logs 无 secret、Authorization header、Google ID token、session token、purchase token、transcript text。
2. `/v1/auth/google` 能建立 session，错误 audience 会被拒绝。
3. `/v1/me` 对已登录用户返回 entitlement 和 quota；过期 PRO 自动 fail closed 为 FREE。
4. `/v1/billing/restore` 只接受已登录 session、正确 package、allowlisted product 和 Play 可验证 token。
5. `/v1/billing/rtdn` 对合法 OIDC push 返回 204；重复 message 不重复调用 Play API。
6. `/v1/summary` 只使用配置的 provider/allowlist，不接受客户端 arbitrary upstream URL。
7. Account deletion 撤销 session、清理账户同步数据、清理 private transcript objects，并对保留的 public artifact 清除 creator identity。

## 9. 迁移、回滚和故障处理

### D1 migration

- 迁移文件按数字前缀顺序执行，发布前先在独立 Cloudflare 账户或本地 D1 做 dry review。
- 迁移后保留 D1 备份/恢复方案；不要直接删除生产表。
- Worker 代码与 migration 必须同一发布批次登记。若 migration 已执行而 Worker 未发布，先确认旧代码能安全读取新增列。

### Worker rollback

- 保留最近一次成功的 Worker deployment version 和对应 Git commit。
- 只回滚 Worker 代码，不回滚已应用的 D1 schema；schema rollback 需要独立的数据迁移和人工审批。
- 回滚前暂停新 Play/RTDN 变更，避免新代码写入旧 schema。

### billing incident

- 先禁用有问题的 product ID allowlist，而不是删除 D1 billing history。
- 保留 purchase-token hash、messageId dedup 和 entitlement audit；不要为了“修复”而打印 raw token。
- 如果 Play API 暂时不可用，restore/RTDN 应失败并可重试，不能把错误响应当作 FREE 或 PRO 的永久事实。

### transcript incident

- 当前没有 consumer 时，`artifact_jobs` 会停在 `queued`，这是已知部署边界，不是客户端轮询 bug。
- consumer 上线后必须实现 lease、attempt、音频 URL SSRF/大小校验、provider 超时、`completeJobWithArtifact`、`failJob` 和对象清理，再开放 transcript quota。
- R2 写入和 D1 状态不是跨系统事务；代码已有尽力补偿，但发布前仍需做故障注入和孤立对象巡检。

## 10. 相关文件

- Gateway 自动部署：[`scripts/deploy_gateway.ps1`](../scripts/deploy_gateway.ps1)
- Wrangler 绑定：[`gateway/wrangler.toml`](../gateway/wrangler.toml)
- secrets 示例：[`gateway/.env.cloudflare.example`](../gateway/.env.cloudflare.example)
- 认证：[`docs/AUTH.md`](./AUTH.md)
- 内购协议：[`docs/BILLING.md`](./BILLING.md)
- AI provider：[`docs/AI_GATEWAY.md`](./AI_GATEWAY.md)
- 安全边界：[`docs/SECURITY.md`](./SECURITY.md)
- 当前执行状态：[`docs/EXECUTION_STATUS.md`](./EXECUTION_STATUS.md)
