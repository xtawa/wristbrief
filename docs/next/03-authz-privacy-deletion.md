# 转录授权、共享边界与账户删除

## 2026-09-12 当前状态

SEC-01/03 及 SEC-02 的默认私有、对象级授权部分已经落地：D1 migration 0008 用户范围数据删除、已配置 transcript storage 时的私有对象清理、公开 artifact creator 脱敏，以及 legacy 已删除账户拒绝认证。移动端登出、删除和直接切换账户时的 transcript cache、Cloud Sync outbox、cursor/preferences 清理也已接线。`PUBLIC_REUSE` 仍可由请求输入显式触发；服务端来源、域名或签名 URL eligibility policy 尚未实现，因此 SEC-02 仍是条件性风险并保持 PENDING。

仍待完成：Queue consumer/audio worker、删除过程的事务化或非事务失败恢复，以及生产 D1/R2/Queue 与端到端验证。下列“已确认问题”保留为修复前的历史基线，不再代表当前未修复状态。

## 历史基线：当时已确认的问题

### SEC-01 对象级授权

`gateway/src/index.ts` 只验证登录后就调用 `handleTranscriptStatus`/`handleTranscriptGet`，没有传递 `user.id`。路由按 job id/content code 查询全局记录，未检查 `job.userId`、`user_artifact_access` 或公共策略。

### SEC-02 私有内容被写成公共工件

客户端请求的 `sharePolicy` 被传给 resolver，而 `TranscriptService.completeJobWithArtifact()` 固定写入 `PUBLIC_REUSE`。私有账户内容不能依赖客户端字段决定是否公开。

### SEC-03 删除遗漏 0008 数据

账户删除目前清理 sessions/identities/managed AI usage 等旧表，但没有覆盖 devices、user_subscriptions、user_item_states、user_playback_progress、user_sync_cursors、user_artifact_access、artifact_jobs、credit_transactions 等新表。

## 修复要求

1. 所有 job/artifact/content 读取 API 接收认证 user id；公共 artifact 也必须显式满足 `PUBLIC_REUSE`，私有 artifact 只能由 creator/access grant 读取。
2. job status 只能返回请求用户自己的 job；不要依赖随机 UUID 作为授权机制。
3. share policy 由服务端策略决定：来源、账户权限、takedown 状态和内容隐私参与决策；生成 artifact 时沿用已验证策略，禁止 hard-code public。
4. 对音频 URL 做 HTTPS、长度、解析、SSRF/私网地址和重定向策略校验；content resolve、sync、transcript request 统一使用 body-size/字段数量限制和限流。
5. 设计可审计、幂等的账户删除事务：撤销 session、禁用用户、停止新 AI/转录任务、清理或 tombstone 所有 0008 用户数据和 artifact access，并明确保留 billing/fraud/RTDN 所需的最小记录。
6. 增加双账号越权测试、私有转录不可见测试、删除后查询/同步/缓存不可恢复测试。

## 通过标准

- 未授权用户统一得到 404/403（不泄漏存在性，按产品策略固定）。
- 私有 artifact 的 metadata、object key、内容和 job status 均不可被其他账户获取。
- 删除完成后，旧 session、同步数据、job、access grant 和可读缓存均失效；保留项有明确字段和文档理由。
