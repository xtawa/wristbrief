# Cloud Sync 客户端闭环

## 当前证据

- 服务端 `/v1/sync/push`、`/v1/sync/pull`、D1 migration 0008，以及移动端 `HttpCloudSyncApi`、`CloudSyncCoordinator`、cursor、backoff、outbox 和相关测试已有源码基础。
- 登出、账户删除和直接切换账户时，本地 outbox、cursor/preferences 与 transcript cache 清理已接线。
- 真实应用生命周期接线、完整账号隔离、WorkManager/网络恢复调度，以及端到端 CI/设备验证仍待完成。
- 下方“需要完成”清单继续作为完整验收范围；已有局部实现不等于闭环完成。

## 需要完成

1. 定义并实现认证 session、device registration、push/pull API client；禁止客户端传入的 user id 改变账户边界。
2. 将订阅、read/saved、playback 的本地写入统一变成可幂等 outbox mutation。
3. 增加 WorkManager/等价 coordinator：网络恢复、账号切换、进程重启、失败重试、指数退避和 jitter。
4. 持久化 per-device cursor；pull 后按领域规则写入本地 SQLite，再向 Wear Data Layer 发布 canonical state。
5. 明确订阅 tombstone、read/saved 双时钟、播放 session/generation 的冲突规则；不允许旧设备覆盖新状态。
6. 账号删除、登出、账号切换时清理 pending outbox、cursor、Data Layer 和本地账户缓存。

## 最低验收

- Phone A → Wear → Phone B 的订阅、read/saved、播放位置收敛。
- 离线写入、进程被杀、重启后仍能 push；重复 push 不产生重复记录。
- 两设备并发冲突结果确定且有单测/集成测试。
- 删除/切换账号后，旧账户的数据不出现在新账户。
- 真实或可复现的 L3 CI 记录；L4 配对设备、模拟器和虚拟机验证不在本次范围，不能据此宣称端到端设备闭环。
