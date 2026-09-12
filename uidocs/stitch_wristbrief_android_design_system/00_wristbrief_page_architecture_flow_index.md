# WristBrief 完整页面流转顺序与体验架构索引

按用户旅程（User Journey）与产品架构分为 6 大核心模块，共 25 个已生成的高保真画板：

---

## 模块一：新用户入职体验流 (OOBE Onboarding Flow)
> 引导用户完成“来源汇聚而非算法推荐”的心智建立与首次个性化。

1. **01 OOBE Welcome** (`SCREEN_16`)
   - 概念破冰、品牌极简矢量汇聚图形、三大核心价值点、Get Started。
2. **02 OOBE Interests** (`SCREEN_15`)
   - 兴趣分类多选 Chip、明确非算法推荐原则声明、组织初始内容池。
3. **03 OOBE Content** (`SCREEN_14`)
   - 添加 RSS、导入 OPML、精选源推荐与一键添加态（Added）。
4. **04 OOBE AI Features** (`SCREEN_13`)
   - 阅读基础设施型 AI 演示（今日早报样张、逐词时间戳、防幻觉溯源）。
5. **05 OOBE Ready** (`SCREEN_12`)
   - 静态关键帧就绪确认、初始源与腕表连接状态概览、直达主页阅读。

---

## 模块二：核心日常消费与简报流 (Daily Consumption & Feed)
> 用户的每日核心日常动线：晨间速报、来源探索、全局 AI 问答。

6. **06 Home (Today)** (`SCREEN_30`)
   - 核心主屏：Today's Brief 玻璃大卡片、未读摘要、未听完播客、待续长文。
7. **07 Full Daily Brief** (`SCREEN_29`)
   - 沉浸式私人早报详情：核心观点归纳、分类深度解析、关联高价值原文推荐。
8. **08 Explore** (`SCREEN_28`)
   - 纯粹的 RSS / 播客内容源发现中心（非算法流），按领域发现与关注。
9. **09 Ask AI** (`SCREEN_27`)
   - 基于个人资料库的端侧/云端问答，结构化答案与可溯源文献 Chip。

---

## 模块三：资料库与长内容消费流 (Library & Content Readers)
> 个人高密度知识库与纯粹阅读/播客消费。

10. **10 Library** (`SCREEN_26`)
    - 高密度内容数据库：未读/已读筛选、多媒体混合排列与关键词搜索。
11. **11 Article Detail** (`SCREEN_25`)
    - 纯正沉浸式长文阅读排版、Inset AI Executive Summary 摘要面板。
12. **12 Podcast Episode Detail** (`SCREEN_24`)
    - 播客单集详情、大号播放键、AI 提炼 3 大要点、结构化章节控制。
13. **13 Transcript Viewer** (`SCREEN_23`)
    - 独立全屏逐字稿阅读器、逐词对齐高亮、点击时间戳寻轨定位。
14. **15 Expanded Now Playing** (`SCREEN_22`)
    - 全屏大播放器：波形进度条、大号触控按键、倍速调节、即时 AI 总结。

---

## 模块四：系统设置与商业化体系 (Settings & Account)
> RSS 订阅源治理、系统偏好配置、账号权益与支付墙。

15. **16 Settings Sources** (`SCREEN_21`)
    - 订阅源管理器：分类分组管理、OPML 导入导出、开启/停用开关。
16. **18 Settings Preferences** (`SCREEN_20`)
    - 偏好设置：主题外观（浅色/深色/纯黑 OLED）、刷新频次、Wear OS 腕表同步。
17. **20 Settings Account** (`SCREEN_19`)
    - 已登录账号：Pro 状态、AI 配额监控仪表、多端设备协同（Pixel Watch 3）。
18. **21 Membership Paywall** (`SCREEN_18`)
    - 会员购买页面：Free / Plus / Pro 纵向权益对比与 Google Play 订阅保障。
19. **22 Settings About** (`SCREEN_17`)
    - 关于应用：版本信息、技术栈标识、重放新手引导、隐私政策与开源协议。

---

## 模块五：网络与系统异常兜底流 (State & Error Fallback)
> 保证产品在恶劣网络或特殊状态下的健壮性与可恢复性。

20. **24 Offline State** (`SCREEN_11`)
    - 离线模式：离网检测、本地安全缓存（音频、长文）展示、重试与离线继续。
21. **33 AI Error Quota** (`SCREEN_10`)
    - AI 配额超限：300 次额度告警、端侧 Gemini Nano 本地模型降级开关。
22. **34 Search Empty** (`SCREEN_8`)
    - 搜索无结果空状态：直达 Explore 发现库、历史推荐词与精选推荐。

---

## 模块六：大屏与折叠屏自适应体系 (Tablet & Large Screen 2-Pane)
> 针对大屏（>= 600dp 横屏）的双栏分栏排版，提升阅读吞吐率。

23. **35 Tablet Home** (`SCREEN_6`)
    - 侧边 Navigation Rail 导航轨、左侧主简报与长文、右侧播客伴随与腕上同步。
24. **36 Tablet Library Two-Pane** (`SCREEN_2`)
    - 经典主从双栏：左侧资料库高密度列表，右侧直开长文沉浸阅读。
25. **37 Tablet Settings Two-Pane** (`SCREEN_4`)
    - 分屏设置中心：左侧模块导航，右侧大卡片系统级配置面板。
