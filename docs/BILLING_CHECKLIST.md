# Google Play Billing 生产上线与配置清单 (Production Billing Checklist)

本文档是 WristBrief 阶段 F（账户、会员和 Google Play Billing）生产上线的外部依赖与人工配置指南。

依据工程原则，生产环境的 Google OAuth Client ID、Play Console 订阅配置、GCP Service Account 凭据、Pub/Sub RTDN 推送与 Cloudflare 生产 Secret **不得在代码中伪造**。必须完成以下人工步骤方可在生产环境开通订阅：

---

## 一、Google Play Console 配置

1. **应用与多 APK 发布**：
   - 确保 `:mobile` 与 `:app` 的 `applicationId` 均为 `ink.underflo.wristbrief`。
   - 确保 Wear 模块的 `versionCode` 严格大于手机模块的 `versionCode`（通过 `scripts/release_guard.py` 自动化检查）。
   - 上传首个 Release 构建至 Google Play Console **内部测试轨道 (Internal Testing Track)**。

2. **订阅商品配置**：
   - 进入 **创收 (Monetize) -> 产品 -> 订阅 (Subscriptions)**。
   - 创建订阅方案（与 Android `BuildConfig.BILLING_SUBSCRIPTION_PRODUCT_IDS` 及 Gateway `BILLING_ALLOWED_PRODUCT_IDS` 保持严格一致）：
     - `pro_monthly`：月度订阅，配置基础方案（Base Plan）与计费周期（1 个月）。
     - `pro_yearly`：年度订阅，配置基础方案（Base Plan）与计费周期（1 年）。
   - 为基础方案配置可用国家/地区及定价。
   - 激活方案。

3. **测试账号配置**：
   - 进入 **设置 -> 许可测试 (License Testing)**。
   - 添加开发与测试团队的 Google 账号，许可响应设为 `RESPOND_NORMALLY`。

---

## 二、Google Cloud Platform (GCP) 与 API 凭据

1. **启用 Android Publisher API**：
   - 在已关联 Play Console 的 GCP 项目中，启用 **Google Play Android Developer API**。

2. **创建专用 Service Account**：
   - 在 GCP 控制台 **IAM 与管理 -> 服务账号 (Service Accounts)** 创建服务账号，例如：
     `wristbrief-play-verifier@<project-id>.iam.gserviceaccount.com`
   - 为该服务账号生成并下载 JSON 格式的私钥（PEM 格式的私钥文本）。

3. **授权 Play Console 访问权限**：
   - 在 Play Console 中进入 **用户与权限 -> 邀请新用户**。
   - 填入上述 Service Account 的邮箱。
   - 授予权限：
     - 查看财务数据、订单和退款；
     - 管理订阅。
   - 保存并生效。

---

## 三、实时开发者通知 (RTDN) 配置

1. **创建 Pub/Sub 主题**：
   - 在 GCP 控制台 **Pub/Sub -> 主题 (Topics)** 创建主题：
     `projects/<project-id>/topics/wristbrief-play-rtdn`

2. **在 Play Console 绑定 RTDN**：
   - 在 Play Console 进入 **创收设置 (Monetization setup)**。
   - 在 **实时开发者通知 (Real-time developer notifications)** 处，填入完整的 Pub/Sub 主题名称：
     `projects/<project-id>/topics/wristbrief-play-rtdn`
   - 发送测试通知以确认连接状态正常。

3. **创建安全推送订阅 (Push Subscription)**：
   - 在 GCP 控制台 **Pub/Sub -> 订阅 (Subscriptions)** 为上述主题创建订阅：
     - 交付类型：**推送 (Push)**。
     - 端点网址 (Endpoint URL)：`https://<production-gateway-domain>/v1/billing/rtdn`。
     - 勾选 **启用身份验证 (Enable authentication)**：
       - 选择或创建专用的推送服务账号，例如：
         `wristbrief-pubsub-pusher@<project-id>.iam.gserviceaccount.com`
       - 受众 (Audience)：填入完整的 HTTPS 目标地址 `https://<production-gateway-domain>/v1/billing/rtdn`。
   - 授权 Pub/Sub 服务代理：
     - 为 GCP 默认的 Pub/Sub 服务代理（`service-<project-number>@gcp-sa-pubsub.iam.gserviceaccount.com`）授予 `roles/iam.serviceAccountTokenCreator`（服务账号令牌创建者）角色，以允许其代表推送服务账号为推送请求签名 OIDC JWT。

---

## 四、Cloudflare Gateway 环境变量与 Secrets 配置

在 Cloudflare Gateway 部署环境（Wrangler / Dashboard）配置以下变量与安全密钥：

| 变量名 | 类型 | 说明 | 示例值 |
|---|---|---|---|
| `GATEWAY_PACKAGE_NAME` | Plaintext | 匹配 Android 客户端包名 | `ink.underflo.wristbrief` |
| `BILLING_ALLOWED_PRODUCT_IDS` | Plaintext | 允许购买的订阅产品 ID 列表 | `pro_monthly,pro_yearly` |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL` | Secret | 用于请求 Android Publisher API 的服务账号 | `wristbrief-play-verifier@<project-id>.iam.gserviceaccount.com` |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` | Secret | 服务账号 RSA 私钥（PEM 格式） | `-----BEGIN PRIVATE KEY-----\nMIIEv...` |
| `PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL` | Plaintext | Pub/Sub 推送请求必须匹配的服务账号邮箱 | `wristbrief-pubsub-pusher@<project-id>.iam.gserviceaccount.com` |
| `PUBSUB_PUSH_AUDIENCE` | Plaintext | Pub/Sub 推送必须匹配的受众（Audience） | `https://<production-gateway-domain>/v1/billing/rtdn` |

---

## 五、端到端生命周期验证

在内部测试轨道上，使用许可测试账号进行以下测试：

1. **购买与验证**：
   - 打开手机端 **设置 -> 账户与会员**。
   - 登录 Google 账号。
   - 点击订阅 Pro 会员，完成测试购买。
   - 点击 **恢复并验证会员**，确认调用 `/v1/billing/restore` 返回 `Success`，并即时更新客户端会员徽章为 `Pro 会员`。
2. **RTDN 状态变更**：
   - 在 Play Console 取消测试订阅或模拟扣费失败（进入宽限期 Grace Period 或挂起 Account Hold）。
   - 检查 Gateway 日志确认收到 Pub/Sub 推送，并调用 Google Play API 验证后将会员状态降级或保持。
3. **退款与撤销**：
   - 模拟退款操作，确认会员权益即时终止并回到免费版。
