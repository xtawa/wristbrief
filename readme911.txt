# WristBrief 当前状态交接文档（给下一 Agent）

仓库：`xtawa/wristbrief`
当前分支：`main`
当前 HEAD：

```
21cff488152810764e7ae1c30993e61cc4967504
```

最新提交：

```
Skip duplicate subscription sync updates
```

开放 PR：

```
无
```

最新 CI：

```
CI #350
状态：success ✅
```

---

# 一、项目当前整体状态

WristBrief 已经完成原定 **NEXT_24_HOURS 全部 24 个 slot**。

状态文件：

`docs/NEXT_24_HOURS_STATUS.md`

全部：

```
[x] Slot 01 - Slot 24
```

完成记录完整。

当前阶段：

> 从短期开发阶段进入 ROADMAP / Release Readiness 阶段。

---

# 二、已经完成的核心功能

## 1. RSS / Feed 系统

已完成：

* RSS / Atom parsing
* GUID / id identity
* 去重：

优先级：

```
GUID
 ↓
canonical link
 ↓
enclosure/audio URL
 ↓
deterministic fallback
```

支持：

* RSS fixture
* Atom fixture
* offline cache

---

## 2. Subscription 系统

已完成：

Repository 支持：

* add
* update
* rename
* enable
* disable
* remove

特性：

* URL normalize
* duplicate subscription detection
* persistence round-trip

最近优化：

commit：

```
21cff488152810764e7ae1c30993e61cc4967504
```

内容：

> Skip duplicate subscription sync updates

解决：

相同 subscription snapshot 不再：

* 重复写 SharedPreferences
* 唤醒 Tile
* 刷新 Complication

测试：

覆盖：

* URL canonicalization
* default port
* trailing slash
* keyword normalization

---

## 3. Wear OS App

架构：

```
Wear :app
```

UI：

使用：

* Wear Compose Material 3
* Material 3 Expressive
* AppScaffold
* ScreenScaffold
* TransformingLazyColumn

已完成：

### Feed

* Inbox
* Feed management
* Article detail

### Read state

支持：

* unread
* read
* unread count

### Saved

支持：

* save/star
* offline saved list

---

## 4. Media / Podcast

已经完成：

Media3：

```
MediaSessionService
        |
        |
MediaController
        |
Wear UI
```

能力：

* background playback
* play/pause
* seek
* speed
* resume position
* checkpoint persistence

最近修复：

### Podcast progress persistence

问题：

prepared state 下：

seek / speed change 不立即保存。

已修复：

```
Persist podcast seek and speed changes
```

并增加：

* position persistence
* speed persistence

---

## 5. AI Gateway

目录：

```
gateway/
```

已完成：

Provider abstraction：

```
AiProvider
      |
      |-- OpenAI compatible
      |-- OpenRouter
      |-- Gemini
```

安全：

已经有：

* provider allowlist
* HTTPS only
* no arbitrary proxy
* timeout
* retry
* request id
* secret redaction

---

## 6. Structured AI Summary

已经完成：

结构：

```json
{
 tinySummary,
 briefSummary,
 bullets,
 topics,
 language,
 schemaVersion,
 promptVersion
}
```

包含：

* validation
* repair attempt
* malformed output rejection

---

## 7. AI Cache

已完成：

cache key:

```
SHA256(
 normalized content
 + language
 + prompt version
 + schema version
)
```

支持：

* memory fake
* Cloudflare KV abstraction

---

## 8. Phone Companion

已完成：

模块：

```
:mobile
```

定位：

Android 手机 companion。

UI：

Compose Material 3 / Material You

已有：

* Feed management
* AI settings placeholder
* Membership placeholder

CI 已加入：

```
mobile JVM tests
mobile assembleDebug
```

---

## 9. Data Layer

已完成：

Phone ↔ Wear contract：

包含：

* subscription sync
* read state
* saved state

采用：

versioned payload。

避免：

* 大文本
* 音频
* 网页正文

通过 Data Layer 传输。

---

## 10. Billing / Membership

已完成基础架构。

客户端：

Google Play Billing：

```
Billing Library 8.3.0
```

已有：

* BillingRepository
* Fake implementation
* ProductDetails flow
* restore foundation

服务器：

已有：

FREE / PRO:

```
Entitlement
Quota
Billing verifier boundary
```

支持：

* managed AI quota
* BYOK bypass

---

## 11. Play Server Verification

已完成：

接口设计：

* purchase verification
* restore
* RTDN endpoint contract

安全：

* purchase token hash binding
* ownership verification
* replay protection

但：

没有生产部署。

原因：

需要：

* Play Console
* Google Cloud
* Pub/Sub
* production secrets

---

# 三、最近 Release Readiness 修复

最近几个重点 commit：

## MediaSession

```
3809b65ee1300655797d7cd4ada00899e721aa29
```

修复：

* seek/speed persistence

---

## Unicode Tile / Complication

```
c42b29ae2acd3f12769e2bccdc27a8b88fe14615
```

修复：

Tile title:

UTF-16 截断导致 emoji surrogate split。

---

```
ec19e283ca385103b0b286bde2010e969ba3a292
```

修复：

Complication:

Unicode safe truncation。

---

## Surface refresh optimization

```
124a5217db3cd52a164fbe7873599630580039d5
```

修复：

read state sync:

避免：

```
timestamp changed
但 read 状态没变
```

导致 Tile/Complication refresh。

---

## Subscription sync optimization

```
21cff488152810764e7ae1c30993e61cc4967504
```

修复：

duplicate subscription snapshot:

避免无意义更新。

---

# 四、当前 CI 要求

每次提交必须保持：

## Android

至少：

```
:app:testDebugUnitTest
:app:assembleDebug
```

以及：

```
:mobile:testDebugUnitTest
:mobile:assembleDebug
```

---

## Gateway

必须：

```
npm run typecheck
npm test
```

---

## Instrumentation

当前已有：

Wear:

* small round
* large round
* fontScale 1.30

Mobile:

* instrumentation

---

# 五、当前未完成 / 下一 Agent 建议方向

## 优先级 1：真实 Wear 交互测试

目前缺：

### Rotary

已有代码适配：

但是缺：

真实：

```
RotaryInputEvent
```

自动化覆盖。

建议：

增加：

* rotary scroll test
* focus behavior test

---

## 优先级 2：Complication / Tile Host 测试

目前：

已有：

* mapping tests
* manifest validation

缺：

真实：

* placement
* tap action
* host update propagation

原因：

需要 Wear host environment。

---

## 优先级 3：MediaSession 更深测试

已有：

* service
* persistence
* controller

缺：

真实：

```
service killed
        ↓
restart
        ↓
restore episode
        ↓
restore position/speed
```

端到端测试。

当前难点：

生产只允许 HTTPS audio。

不要为了测试放开 HTTP arbitrary source。

建议：

增加：

test-only media source abstraction。

---

## 优先级 4：Data Layer resilience

缺：

真实：

```
phone disconnect
        ↓
reconnect
        ↓
state reconciliation
```

需要：

paired device CI 或 fake Data Layer。

---

## 优先级 5：生产发布准备

未完成：

* Play Console internal testing
* production signing
* Google Cloud Pub/Sub RTDN
* Cloudflare KV production binding
* D1 migration deploy

这些不能伪造完成。

---

# 六、给下一 Agent 的执行规则

继续开发时：

1. 首先读取：

```
main HEAD
open PR
latest CI
docs/NEXT_24_HOURS.md
docs/NEXT_24_HOURS_STATUS.md
docs/ROADMAP.md
```

2. 当前不要重新做 NEXT_24_HOURS。

已经完成：

```
Slot 01-24
```

3. 进入：

```
ROADMAP.md
Release readiness
```

4. 每个小阶段：

必须：

* 增加测试
* commit main
* 等 CI 完成
* CI green 后继续

5. 不允许：

* force push
* reset
* 大规模 rewrite
* 降级 Wear Compose 1.6.2
* 把手机 UI复制到 Wear
* 放宽 HTTPS / Gateway 安全限制

---

# 七、当前项目成熟度评估

| 模块                 | 状态                |
| ------------------ | ----------------- |
| Wear RSS Reader    | ✅ 完成              |
| Offline cache      | ✅ 完成              |
| Read/Saved         | ✅ 完成              |
| Podcast background | ✅ 完成              |
| AI Gateway         | ✅ 完成              |
| Multi-provider AI  | ✅ 完成              |
| AI cache           | ✅ 完成              |
| Phone companion    | ✅ 基础完成            |
| Data Layer         | ✅ 基础完成            |
| Billing client     | ✅ Foundation      |
| Entitlement server | ✅ Foundation      |
| RTDN               | ⚠️ Interface only |
| Play production    | ❌                 |
| Wear host testing  | ⚠️                |

整体已经从“功能开发”阶段进入：

> **Release hardening + production integration 阶段。**

下一 Agent 可以直接从 `ROADMAP.md` 的 release-readiness 项开始。
