# 共享转录的存储、任务与客户端接入

## 2026-09-12 当前状态

- Content Code、artifact/job metadata、0/0.2/1.0 quota 逻辑、D1 表与 R2 binding 已有源码基础。
- `TranscriptGatewayApi`、job polling、bounded response parsing、`TranscriptCache` 和 viewer/player wiring 已部分存在。
- 实际 Queue consumer/audio fetch、生产对象存储读写，以及 quota/账户删除的生产端到端验证仍为 PENDING。
- 下方清单与验收标准保持有效；局部源码存在不表示工作项已经全部完成。

## 需要完成

1. 选择并配置 R2/Queue（或同等可靠组件），实现 job claim/lease、重试、失败释放/结算 quota 和幂等完成。
2. 生成 transcript JSON/text/segments 后实际写入对象存储；D1 只保存 metadata、hash、版本和状态。
3. 提供经过授权的内容读取 API，区分 `PUBLIC_REUSE` 和 `PRIVATE_ACCOUNT`；不能仅返回未经检查的 object key。
4. 实现移动端 `TranscriptGatewayApi`、processing polling、网络失败恢复、`TranscriptCache` 写入和 viewer/player 导航。
5. 对相同 Content Code + language + version 做单飞去重，并验证两个不同用户的 quota：首次 1.0x、公共缓存 0.2x、同一用户重复 0x。
6. 增加 takedown、版本替换、私有转公共禁止升级、删除账户后 artifact access 清理。

## 最低验收

- 首次生成、共享命中、重复访问、失败/重试分别有集成测试。
- 新设备可从账户恢复已授权 transcript；离线缓存不越过账户边界。
- R2/Queue/D1 的生产绑定在内部测试环境真实验证，记录 L5 证据。
