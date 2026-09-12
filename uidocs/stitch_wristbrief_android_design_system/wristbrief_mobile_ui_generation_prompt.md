为 Android 应用 **WristBrief** 设计一套完整、高保真、可直接实现的 Mobile UI Design System 与全部产品页面。

这不是概念稿，也不是营销 Landing Page。设计必须像一款已经准备提交 Google Play 的成熟 Android 产品。请一次性输出完整页面体系，并确保所有页面属于同一个一致的设计系统。

# 1. 产品定义

WristBrief 是一款：

- RSS Reader
- Podcast Reader / Player
- AI Daily Brief
- AI Article / Podcast Summarization
- Podcast Transcript
- Ask AI
- Multi-device Mobile + Wear OS sync

融合型阅读产品。

它强调：

“把大量 RSS、Podcast 和订阅信息压缩为每天真正值得关注的内容。”

核心内容对象只有两类：

1. Article
2. Podcast Episode

AI 是内容消费流程的一部分，而不是一个悬浮在产品之外的聊天机器人。

产品体验参考方向：

- Google Material 3 Expressive
- Google Podcasts
- Readwise Reader
- Spotify
- Google Pixel 系统应用
- 少量 Apple News / Apple Podcasts 的信息层级处理

但禁止直接复制任何现有 App。

---

# 2. 总体视觉方向

使用 **Material 3 Expressive + Neutral Glass**。

关键词：

calm  
editorial  
intelligent  
premium  
neutral  
soft glass  
spacious  
content-first  
modern Android  
subtle depth

不要做：

- 赛博朋克
- AI 紫蓝渐变
- 大面积荧光
- 高饱和背景
- NFT / Web3 风格
- iOS clone
- 全页面毛玻璃
- 过度透明导致阅读困难
- 巨大渐变球
- 每个组件都有彩色背景
- 过多阴影
- 过多边框

背景使用接近 Android Material Surface 的低对比中性色。

Glass 应该只用于：

- navigation
- hero cards
- mini player
- floating controls
- key AI surfaces

正文阅读区域必须保持优秀的可读性。

语义色仅允许用于：

- Error
- Success
- Warning
- Active playback
- Subscription tier
- Important status

绝不能用语义色填满整个页面背景。

---

# 3. Material 3 Expressive Design Tokens

建立完整 Token System。

## Shape

Small controls:
12–16dp radius

Standard card:
20–24dp radius

Hero card:
28–32dp radius

Large modal / expanded player:
28–36dp radius

Pills:
fully rounded

允许少量 Material 3 Expressive 不对称 / organic shape，但不要让每张卡片长得不一样。

## Spacing

4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48

普通页面左右：
20dp

大屏：
最大正文宽度约 840dp。

## Typography

使用 Android / Google 风格无衬线字体。

建立：

Display  
Headline  
Title  
Body  
Label

内容标题比传统 RSS Reader 更大胆。

文章正文减少视觉装饰。

AI 摘要需要形成独立信息层级，但不要设计成 ChatGPT 对话框。

---

# 4. 全局导航

手机底部只有四个主入口：

1. Home
2. Explore
3. Ask AI
4. Library

绝对不要增加第五个 Now Playing Tab。

底部 Navigation Bar：

- neutral glass
- 轻微透明
- 背景 blur 感
- 清晰 hairline
- active destination 使用 Material expressive indicator
- inactive icon 保持低强调

大于约 600dp 的 tablet / foldable 使用 Navigation Rail。

顶部右侧设置 Settings 入口。

播放器：

播放 Podcast 时，在 Bottom Navigation 上方展示 Mini Player。

点击 Mini Player → Expanded Now Playing。

---

# 5. PAGE 01 — OOBE / Welcome

设计五页完整 OOBE。

## Step 1 — Welcome

标题：

Welcome to WristBrief

副标题表达：

Your feeds, podcasts and AI brief — without the noise.

页面中心：

抽象 WristBrief illustration。

不要直接画手表。

视觉表达：

多个内容源 → 一个干净 Brief。

使用简单 SVG-like vector geometry：

- cards
- article lines
- podcast waveform
- converging nodes

底部：

Primary:
Get started

Secondary:
Skip

顶部：
WristBrief wordmark

进度：
5 step expressive progress indicator。

---

# 6. PAGE 02 — OOBE / Interests

标题：

What are you into?

说明：

Help WristBrief organize your starting experience.

使用 expressive chips：

Technology
Design
Science
Podcasts
News
Culture

Selected chip 使用低饱和 surface tint。

允许多选。

不要设计成社交媒体兴趣推荐墙。

明确说明：

Interests only help organize content; WristBrief does not create an algorithmic feed.

底部：

Back
Continue

---

# 7. PAGE 03 — OOBE / Add Content

标题：

Build your library

三个入口：

Add RSS Feed
Import OPML
Try sample feeds

下方推荐几个 sample feed cards。

每一个 sample source 使用：

favicon / simple icon
source name
category
Add button

添加成功：
按钮变成 ✓ Added。

必须设计：

empty state
adding state
added state

避免像设置页面。

这一页应该有“第一次建立自己的信息空间”的感觉。

---

# 8. PAGE 04 — OOBE / AI Features

标题：

A quieter way to catch up

展示三个 AI 功能：

Daily Brief
Podcast Transcript
Ask AI

可以使用一张大 AI preview card：

Today’s Brief
“5 sources summarized · 3 min read”

下方展示三条 capability：

• Focused daily brief  
• Word-level podcast transcripts with audio seek  
• Answers grounded in your feeds

不要出现：

API Key
Provider selector
BYOK

普通用户无需理解模型供应商。

底部：

Continue

---

# 9. PAGE 05 — OOBE / Ready

完成状态。

中心使用 Material Expressive completion animation 的静态关键帧设计：

一个圆形 / organic container
中心 check
周围少量 confetti geometry

不要过度庆祝。

标题：

You're ready

如果用户已有 feed：

Primary:
Start reading

如果没有 feed：

Primary:
Add a feed

Secondary:
Import OPML

Tertiary:
Explore first

---

# 10. PAGE 06 — HOME / TODAY

这是产品最重要的页面。

页面顺序：

## Header

显示：

Saturday, September 12

Good afternoon

右上：

Settings avatar/icon

不要巨大 App Bar。

---

## Category Filter

横向 chips：

All
Technology
Science
Design
…

保持轻量。

---

## Today's Brief Hero Card

这是 Home 最重要元素。

使用强一些的 neutral glass。

内容：

TODAY'S BRIEF

6 sources · generated 8:42 AM

一句 2–3 行 AI overview。

然后 3 个 bullet：

• OpenAI released...
• Android ecosystem...
• New research...

底部：

Read full brief

Regenerate

如果还没生成：

“You have 14 unread items ready to summarize.”

按钮：

Generate today's brief

AI card 可以有极轻微 sparkle / waveform icon。

不要做巨大 AI gradient。

---

## Continue Listening

如果有未播放完 Podcast：

section title:
Continue listening

横向 / 单张宽卡：

podcast artwork
episode title
podcast title
progress
22 min left

Play button。

---

## Continue Reading

文章卡：

source
headline
2-line summary
reading progress / unread state

---

## Latest

混合 Article 与 Podcast。

内容卡必须明显区分媒体类型，但整体结构统一。

Article card：

source favicon
source title
time
headline
short description
Read
Save

Podcast card：

artwork
show
episode
duration
progress
Play
Save

---

## Empty Home

如果没有订阅：

hero empty card

“Your brief starts with your sources.”

Primary:
Add feed

Secondary:
Import OPML

下方：
sample sources。

---

# 11. PAGE 07 — FULL DAILY BRIEF

从 Today's Brief 的 Read full brief 打开。

这是沉浸式 AI briefing 页面。

顶部：

Back
Today's Brief
日期

主体：

hero summary

随后：

Key points

Topics

Stories worth opening

每个来源必须可追踪。

Example:

AI summary paragraph

Sources:
The Verge · Ars Technica · Nature

文章推荐卡：

headline
source
reason it matters
Open article

底部：

Ask AI about today's brief

设计目标类似：

“一份 5 分钟可以看完的私人早报。”

不要做聊天界面。

---

# 12. PAGE 08 — EXPLORE

Explore 用于发现内容源，不应该变成算法推荐首页。

顶部：

Explore

Search field:
Search feeds or podcasts

下面使用分类：

Technology
Science
Design
Business
Culture
Podcasts

区块：

Featured sources

Popular RSS feeds

Recommended podcasts

每个 Source Card：

icon / artwork
name
description
category
subscriber-style metadata可省略
Add

已订阅显示：

Following

不要产生无限 Feed。

Explore 的目标是找“来源”，不是消费文章。

---

# 13. PAGE 09 — ASK AI

一级导航之一。

标题：

Ask AI

副标题：

Ask across your saved articles, feeds and transcripts.

首次进入 Empty / Suggested Prompts：

“What did I miss today?”

“Summarize my unread technology stories.”

“What were the main arguments in this podcast?”

“Compare today's AI news.”

底部固定输入区。

输入框：

Ask your library…

附加：

context source selector

Today
Unread
Saved
Selected article

聊天结果必须是阅读型 UI。

User message 可以简化。

AI response 使用：

headline
structured answer
bullets
inline sources

Sources 应是可点击 citation chips：

The Verge
Nature
Podcast episode

支持：

Regenerate
Copy
Open source

不要照搬 ChatGPT UI。

重点突出“grounded in your library”。

---

# 14. PAGE 10 — LIBRARY

顶部：

Library

右侧：

Manage sources

Search：

Search your library

第一行 filters：

Newest ↓
All
Unread
Saved
Articles
Podcasts

第二行可横滑 category chips：

All
Technology
News
Science
…

内容列表。

Unread item：
标题权重更高。

Read item：
权重降低。

Article：

headline
source + date
2 line preview
Read
Mark read/unread
Save

Podcast：

episode artwork
episode title
show
date / duration
Play
Save

搜索为空：

No results for “...”
Clear search

Library 空：

Your library is empty.
Manage sources

确保信息密度比 Home 高。

Home 是 briefing。
Library 是完整内容数据库。

---

# 15. PAGE 11 — ARTICLE DETAIL

顶部 sticky：

Back
Save
More

正文开始：

Source
Published time

Headline

subtitle / description

hero image if available

正文。

使用适合长阅读的 typography。

不要把正文塞进玻璃卡。

正文必须直接排版在 surface 背景。

支持：

Mark read / unread

Save

Open original

Ask AI

如果是 Podcast 类型：

Play episode

Transcript

AI Summary

Article AI panel 使用轻量 inset card。

---

# 16. PAGE 12 — PODCAST EPISODE DETAIL

与 Article Detail 共用内容体系，但针对 audio 优化。

顶部：

podcast artwork

show name
episode title
date
duration

Primary:
Play

Secondary:
Save

内容：

episode description

AI summary

Transcript

chapters if available

CTA：

Ask AI about this episode

如果播放中：
mini player 持续保留。

---

# 17. PAGE 13 — TRANSCRIPT VIEWER

独立 fullscreen destination。

顶部：

Back

Transcript

episode title

正文按 timestamp 分段。

当前播放句子需要明显但克制地 highlight。

每一段：

12:34
transcript sentence...

点击 timestamp：
seek audio。

顶部 / 底部可保留 compact player controls：

Play/Pause
-15 sec
+30 sec
progress

搜索 Transcript 可作为 secondary action。

必须设计以下状态：

Loading transcript
Processing transcript
Ready
Quota exceeded
Unavailable
Error

Processing：

clean progress state
“Preparing transcript…”

不要无限 spinner 占整页。

---

# 18. PAGE 14 — MINI PLAYER

任何主页面播放 Podcast 时出现。

位置：

Bottom Navigation 上方。

内容：

小 artwork
episode title
show name
progress bar
Play/Pause

可选择：
close / queue action

点击整个 surface：

打开 Expanded Player。

Material expressive glass pill / card。

不能遮挡底部导航。

---

# 19. PAGE 15 — EXPANDED NOW PLAYING

这是独立 fullscreen sheet / destination，但不是 bottom tab。

顶部：

down/back

Now Playing

中部：

large episode artwork

show title
episode title

progress scrubber

current time
remaining / total

大号 controls：

-15
Play/Pause
+30

secondary controls：

speed
transcript
save

Playback speed：
1×
1.2×
1.5×
2×

下面：

Up next / episode summary

AI actions：

Summarize episode
Ask AI
Transcript

整体参考高质量 podcast player，但保持 WristBrief neutral glass。

---

# 20. PAGE 16 — SETTINGS SHELL

顶部：

Back

Settings

四个 sections / tabs：

Sources
Preferences
Account
About

手机窄屏可使用 horizontally scrollable tabs。

Tablet 可以升级为 side navigation。

---

# 21. PAGE 17 — SETTINGS / SOURCES

这是完整 RSS / Podcast Source Manager。

顶部：

Sources

actions：

Add feed
Import OPML

按 category 分组。

每个 source：

favicon / artwork
name
URL / description
category
enabled toggle
more menu

Actions：

Rename
Move category
Disable
Remove

Add Feed Dialog / Sheet：

Feed URL
Optional title
Category

Primary:
Add feed

必须包含：

Validating
Success
Invalid URL
Feed unavailable

OPML：

Import OPML

Export OPML

Import preview：
“37 feeds found”

Conflict handling：
skip duplicate / merge

---

# 22. PAGE 18 — SETTINGS / PREFERENCES

分为三张或四张 large surface cards。

## Appearance

System
Light
Dark

使用 expressive segmented chips。

## Refresh

1 hour
3 hours
6 hours
Manual

## Network & Sync

Wi-Fi only
Wear OS sync
Notifications

每个 switch 都有标题 + 一行说明。

不要做几十个 isolated settings rows。

使用信息分组。

---

# 23. PAGE 19 — SETTINGS / ACCOUNT — SIGNED OUT

顶部：

Account

第一张：

Sign in to WristBrief

说明：

Sync your library, AI usage and membership across devices.

Primary：

Continue with Google

分割线：

or

Email

Password

Sign in

actions：

Create account
Forgot password

下面：

Membership preview

Free
Plus
Pro

不要让登录页像企业后台。

---

# 24. PAGE 20 — SETTINGS / ACCOUNT — SIGNED IN

用户卡：

avatar / initial

email / account identifier

plan badge：

FREE / PLUS / PRO

AI quota：

AI usage this month

145 / 300 requests

progress indicator

Sync status：

Cloud sync
Wear sync

Membership card：

Current plan

Manage subscription

Restore purchases

下方：

Sign out

Delete account

Delete Account 必须使用 error semantic color，但只用于 destructive action。

---

# 25. PAGE 21 — MEMBERSHIP / PAYWALL

独立完整购买页面。

顶部：

WristBrief

Upgrade your brief

三个方案：

FREE

基础 RSS / Podcast reader
limited AI

PLUS
$6.99 / month

More AI summaries
transcripts
sync
advanced Ask AI

PRO
$11.99 / month

highest AI allowance
advanced transcription
premium AI capabilities

突出推荐 Plus 或产品实际推荐方案。

价格卡使用 Material 3 Expressive 大圆角。

不要做 SaaS 网页 pricing table。

手机应该纵向卡片。

底部：

Restore purchases

Terms

Privacy

Google Play billing indicator 可以非常轻量。

---

# 26. PAGE 22 — SETTINGS / ABOUT

卡片 1：

WristBrief

version

产品一句简介。

卡片 2：

Replay onboarding

卡片 3：

Privacy Policy
Open-source licenses

可增加：

Website
Contact
GitHub

但作为 secondary entries。

Debug build 才可出现 Developer diagnostics。

生产设计不要暴露 debug 内容。

---

# 27. PAGE 23 — SEARCH STATE

Library Search 和 Explore Search 使用同一 Search Design Language。

Active search：

top search field expanded

keyboard area无需展示

recent searches

results groups

Articles
Podcasts
Sources

No result state 使用简洁 illustration。

禁止独立生成一个与整个应用完全不同的 Search Page。

---

# 28. PAGE 24 — ERROR / OFFLINE STATES

建立完整状态系统：

Offline

Partial refresh failure

Feed unavailable

AI unavailable

Quota exhausted

Authentication expired

Transcript failed

Empty library

No search results

Loading

所有状态都遵守：

title
short explanation
one primary recovery action

不要展示工程错误文本。

例如：

“You’re offline”
“Showing your latest downloaded stories.”

Primary:
Try again

Secondary:
Continue offline

---

# 29. Tablet / Foldable Adaptive Layout

同一套 Mobile UI 必须额外设计至少：

phone portrait

tablet landscape

不要简单放大手机 UI。

>= 600dp：

Bottom Navigation → Navigation Rail

Library：

left list / right detail 可使用 two-pane。

Settings：

left section navigation
right content

Ask AI：

conversation centered，sources side panel 可选。

最大内容宽度限制，禁止横向铺满。

---

# 30. Dark Mode

同时设计 light / dark tokens。

Dark mode：

不要纯黑 #000 作为所有页面。

使用 deep charcoal surfaces。

Glass：
略高亮 border
极低透明亮层

正文保持高对比。

Article reader 仍应舒适。

---

# 31. Motion Specification

提供关键 motion intent：

Bottom tab switch：
short spring + subtle horizontal movement

Open article：
shared-axis forward

Back：
shared-axis reverse

Mini player → expanded player：
container transform

OOBE：
fade + short expressive scale

AI generation：
soft pulse / shimmer，但不要长期动画

Success：
short Material expressive burst

Filter change：
animated size + selection indicator

---

# 32. Accessibility

所有设计必须：

touch target >= 48dp

文本满足对比度

不能仅靠颜色表示 read/unread

支持 Dynamic Type / font scale

按钮需要明确 label

Icon-only actions必须有 content description

Podcast player controls必须适合单手点击

---

# 33. 设计稿必须输出的 Mobile Frames

必须完整生成至少以下 frames：

01 OOBE Welcome
02 OOBE Interests
03 OOBE Content
04 OOBE AI Features
05 OOBE Ready

06 Home populated
07 Home empty
08 Home Daily Brief generated
09 Full Daily Brief

10 Explore
11 Explore search

12 Ask AI empty
13 Ask AI conversation

14 Library
15 Library filtered
16 Library search results
17 Library empty

18 Article Detail
19 Podcast Episode Detail
20 Transcript Processing
21 Transcript Viewer

22 Mini Player
23 Expanded Now Playing

24 Settings Sources
25 Add Feed
26 OPML Import

27 Settings Preferences

28 Account Signed Out
29 Account Signed In
30 Membership / Upgrade

31 Settings About

32 Offline
33 AI Error / Quota
34 Search Empty

35 Tablet Home
36 Tablet Library two-pane
37 Tablet Settings

并为必要页面提供 Light + Dark mode variants。

---

# 34. Component Library

同时生成 reusable component sheet：

App Navigation Bar
Navigation Rail
Top App Bar
Glass Hero Card
Standard Content Card
Article Row
Podcast Row
Source Row
AI Summary Card
Citation Chip
Filter Chip
Category Chip
Mini Player
Playback Controls
Search Field
Settings Group
Toggle Row
Membership Plan Card
Quota Indicator
Empty State
Error Banner
Loading State
Dialog
Bottom Sheet
Toast / Snackbar
OOBE Progress
Transcript Row

---

# 35. 最终产品感觉

打开 WristBrief 时，不应该让用户想到：

“又一个 AI App。”

应该让人首先觉得：

“这是一个很安静、很成熟、能帮我处理大量信息的 Reader。”

AI 是阅读基础设施。

内容永远是视觉主体。

Material 3 Expressive 提供形状、motion 和层级。

Neutral Glass 提供品牌识别。

不要通过巨大渐变、AI 光球和炫技特效制造“AI 感”。

最终设计必须看起来能够直接被 Android Compose 团队实现，而不是概念艺术。