# 发布验证与文档状态回写

## 当前阻塞

- 本轮 Gateway 27/27 文件、175/175 测试，typecheck 和 release guard 通过；更早的 26/168 数字仅是历史记录。
- Android Gradle 在当前 Windows 会话报 `Unable to establish loopback connection`，所以不能引用本轮 Android 测试/组装为通过。
- 未验证当前提交 CI、Play internal test、RTDN、生产 D1/R2/Queue、Provider、签名身份；本次未执行配对 Phone/Wear、设备、模拟器或虚拟机测试。
- `gateway/package-lock.json` 已存在；可复现 clean-checkout 安装和生产外部配置仍待验证，`wrangler.toml` 的 D1 database id 仍为占位配置。

## 需要完成

1. 在不启动设备/模拟器/虚拟机的前提下，优先在可用 JDK/Gradle/CI 环境复跑 `mobile:testDebugUnitTest`、`app:testDebugUnitTest` 和两个 debug/release assemble；设备矩阵不属于本次执行。
2. 确认 lockfile 策略（提交 npm lockfile 或记录受控替代方案），在 clean checkout 验证安装/测试可复现。
3. 在内部环境配置真实 D1、R2、Queue、Play、RTDN、provider secret；记录不含 secret 的部署证据。
4. 将不需要设备的账号、同步协议、转录授权、删除和 schema 场景补成 Gateway/SQLite/JVM 集成测试；设备登录/旋钮/音频路由/进程重建本次不执行。
5. 将 `docs/EXECUTION_STATUS.md` 的 Cloud Sync、Transcript、Liquid Glass、Release 状态改成按 L1–L5 的事实；不要把历史 24H 记录当作当前结果。
6. 清理 `STATIC_REVIEW_2026-09-11.md`、`AUTH.md`、`MEMBERSHIP.md` 中已完成但仍写成 future/planned 的条目，并同步 ROADMAP Anthropic/P7 文案。

## 最低发布门槛

- 当前提交 CI 全绿，且报告中给出 commit SHA。
- 本次发布前门槛不包含 L4 设备/模拟器/虚拟机；L5 外部配置仍必须有可审计记录，且不得被 L1/L2 结果替代。
- SEC-01～SEC-03 关闭并有双账号/删除测试。
- Cloud Sync 架构文档第 30 节全部验收条件逐项打勾前，不使用“多设备同步完成”或“共享转录完成”。
