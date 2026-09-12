# 增量改造执行状态（2026-09-12）

对应实施计划：`docs/CONTRACT_AUDIT_2026-09-12.md`（契约基线）。
本文档记录本轮增量改造（Phase 0–9）各阶段的实现状态与验证边界，作为 `EXECUTION_STATUS.md` 的补充而不是替代。

## 验证汇总（本机可复现）

| 检查 | 结果 |
|---|---|
| `npm test --prefix gateway` | 通过（40 个测试文件 / 240 个测试） |
| `npm run typecheck --prefix gateway` | 通过 |
| `./gradlew :mobile:testDebugUnitTest` | 通过（220+ 测试） |
| `./gradlew :app:testDebugUnitTest` | 通过 |
| `./gradlew :mobile:lintDebug` | 通过 |
| `./gradlew :app:lintDebug` | **环境阻断**：AGP lint 的 Compose detector 崩溃（`IncompatibleClassChangeError: RememberInCompositionDetectorKt`，lint 工具自身与 Kotlin UAST 的兼容性错误）。本轮未改动 `app/` 任何源码，且 CI（ci.yml）本就不运行该 task；判定为工具链问题而非代码失败 |
| `python scripts/release_guard.py` | 通过（Android 安全、跨设备打包、字符串 parity、D1 migration 0001–0012） |
| `npx wrangler d1 migrations apply ACCOUNT_DB --local` | 通过（0001→0012 全链） |

## 各阶段状态

| 阶段 | 内容 | 状态 | 提交 |
|---|---|---|---|
| 0 | 契约审计文档 | 完成 | `165100f` |
| 1 | 系统返回手势（MobileBackPolicy/Handler + JVM 测试） | 完成 / unit-tested | `73817bf` |
| 2 | migration 0009 + 邮箱认证 + first-admin CAS + Web 管理后台（cookie session + CSRF + audit + 恢复端点 + rate limit） | 完成 / unit-tested | `29348e6` |
| 3 | Mobile 邮箱认证 + Keystore 安全 session 存储（明文→加密透明迁移） | 完成 / unit-tested | `b187a51` |
| 4 | 接线既有 CloudSyncCoordinator（运行时触发 + item-state outbox） | 完成 / unit-tested | `05c17e7` |
| 5 | 订阅生命周期（增删即入 outbox、tombstone、本地 items 清理）+ Feed store split-brain 修复 + 服务端 URL 去重（migration 0010） | 完成 / unit-tested | `75bd093` |
| 6 | SafeRemoteFetcher（SSRF/redirect/流式限长）+ OPML URL 预览（gateway 0010 编号后移；实际 migration 编号按落地顺序：0009 admin/email → 0010 sync integrity → 0011 article → 0012 provider） | 完成 / unit-tested | `907d6ff` |
| 7 | 全文阅读器（ArticleDocument 管线、R2+D1 缓存、媒体代理、原生 Compose renderer、Coil） | 完成 / unit-tested | `f9e9c60` |
| 8 | Provider 管理（D1 配置 + secret slot + 双层 allowlist + 熔断 + /admin/providers） | 完成 / unit-tested | `73249a8` |
| 9 | Phone 导航对齐 uidocs（Home/Explore/Ask AI/Library 四 tab、playback 移出 tab、Today→Home） | 完成（见下） | `7596f4b` |

## 契约审计中既定决策的落地确认

1. **密码 KDF**：`hash-wasm` Argon2id（OWASP 最小配置 m=19MiB/t=2/p=1）在 gateway 打包与 Node 测试环境均正常——第一个 runtime 依赖引入成功，无回落。
2. **first-admin 竞态**：实现为"CAS UPDATE 先行、胜者再建账户"（比规格的"先建候选再回滚"更安全——败者根本不会留下任何行）。20+ 并发注册测试验证恰 1 admin。
3. **邮箱验证确认**：同时支持 POST 与 GET（邮件链接直开）；生产未配置邮件 sender 时 raw token 仍绝不返回客户端。
4. **migration 编号**：按落地顺序 0009–0012（sync integrity 先于 article/provider 落地）。
5. **SSRF 边界**：hostname/字面 IP 策略 + manual redirect 每跳重验 + 流式限长已实现；Cloudflare Workers 无法预解析 DNS 的限制与 DNS rebinding 残余风险已在 `gateway/src/net/ipPolicy.ts` 头注释中如实声明。

## Phase 9 的诚实边界

- 已完成：四 tab 信息架构、`showsInBottomBar`、旧 NowPlaying 保存态归一化、Today→Home 文案（EN/zh）、底部栏与 NavigationRail 统一、静态无渐变验收（grep 仅命中两条禁止注释）。
- 部分/未完成：规格 §6.5–6.7 的完整三态（skeleton / 缓存+progress / 错误保留+inline retry）与全量空状态清单未逐屏实施（现有屏幕已有 today/library/feed 等空态文案，但非系统化契约）；Wear 侧仅做静态核对未做视觉改动（uidocs IA 与现状的映射关系见契约审计 §4.3）。

## 真实环境才能验证的内容（维持规格第十六部分边界）

- Google OIDC 生产（Credential Manager 真机 UI、已签名 APK audience）
- Play Billing（internal track 购买/恢复/RTDN 生产链路）——billing ownership 仍挂 internal user_id，未受邮箱认证影响
- 邮件投递（SPF/DKIM/DMARC、provider 接入后 Phase B）
- Cloudflare 生产（remote migration、Worker secrets 轮换、R2/Queue 生产绑定、DNS/SSRF 生产行为）
- 真机/模拟器（返回手势、圆屏裁剪、rotary、TalkBack、图片实际加载）

以上各项状态均为 **implemented / unit-tested / static-reviewed**，不声称运行验证完成。

## 部署提醒（生产操作，不在本轮执行）

1. `npm run db:migrations:apply`（生产 D1，0009–0012 为新增）。
2. Worker secrets：`AI_PROVIDER_SECRET_1..10`（可选）、`ADMIN_RECOVERY_SECRET`（建议）、`GATEWAY_PUBLIC_BASE_URL`（邮件链接域名，Phase B 前）。
3. `ADMIN_RECOVERY_SECRET` 成功使用后必须轮换（docs/SECURITY.md 契约）。
4. Provider 迁移窗口：Release N 已同时支持 Env 回落 + D1 配置；生产确认 D1 配置可用后再移除 Env 默认路径（N+2）。
