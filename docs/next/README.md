# WristBrief 下一步改进工作单

这些文件是本次核查产生的改进要求和验收清单。本次已经完成部分产品源码修复，但工作单仍有未完成项；条目存在或局部源码落地不代表已经通过完整验收。本次不执行 L4 实机/模拟器/虚拟机验证，也不执行 L5 生产配置验证。

## 优先级顺序

1. `03-authz-privacy-deletion.md`：先修复转录对象级授权、私有/公共边界和账户删除残留。
2. `01-cloud-sync-client-integration.md`：补齐移动端同步网络闭环和离线恢复。
3. `02-transcript-storage-and-access.md`：补齐 R2/Queue（或等价物）、转录内容读取和客户端接入。
4. `04-phone-wear-ui-alignment.md`：按 uidocs 修正 Wear Stage 3、Phone OOBE 与导航。
5. `05-release-validation-and-doc-reconciliation.md`：完成 CI/真机/生产配置验收并回写文档状态。

## 统一验收规则

- 每项都要区分 L1 源码、L2 本地测试、L3 当前提交 CI、L5 生产配置；L4 实机/模拟器/虚拟机验证不属于本次执行范围。
- 涉及账户数据、私有内容或删除的测试必须使用两个不同账号，并验证“看不到/删干净”，不能只测 200 响应。
- 本次不启动实机、模拟器或虚拟机；视觉结论只能写成源码/规格层面的“未验证”，源码无渐变不等于视觉验收通过。
- 生产部署前不能把占位 D1 id、未配置 R2/Queue、未验证 Play/RTDN 当成完成；本次只做静态/本地 Gateway/CI 证据整理。
