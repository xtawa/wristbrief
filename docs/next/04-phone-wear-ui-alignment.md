# Phone / Wear UI 与 uidocs 对齐

## 当前差异

当前源码已加入 Wear 三项 More、Phone 五步 OOBE、Explore/Now Playing 目的地，以及 transcript viewer/player 导航接线。

仍未完成或未验证的差异：

1. 真实视觉效果与 neutral glass 规格尚未通过截图/人工验收。
2. Wear 圆屏裁切、旋钮交互和 Phone/Wear 实际可点击链路尚未验证。
3. 无障碍语义、焦点顺序与触控目标仍需 Android/设备级验证。

## 需要完成

- 先按信息架构改 row/route，再做视觉细节；不要把 Saved/Refresh 继续作为第四个导航入口。
- OOBE 每一步给一个明确主操作，迁移兴趣选择、内容添加、AI 配置和 ready 状态。
- 统一复用 neutral glass surfaces/tokens；保留语义色只用于状态，不绘制彩色背景场。
- 在不启动设备、模拟器或虚拟机的前提下，先补充 token/source assertions、Compose/JVM 可执行的状态测试和规格映射；设备截图/录屏不属于本次范围。

## 通过标准

- `uidocs` 的 checklist 在本次先按源码/测试证据标注；涉及真实视觉、旋钮、圆屏裁切的条目保持“未验证”。
- Phone/Wear 导航和播放器/转录入口均可实际点击，不只是孤立 Composable 或接口。
- Android JVM/静态检查在同一提交 CI 全部通过；managed device、Wear round emulator、实机和虚拟机测试不在本次执行范围。
