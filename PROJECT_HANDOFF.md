# 防沉迷 Mod 项目交接记录

生成时间：2026-06-28  
当前线程：Codex `019ea71e-6c5b-7a21-9258-ae2b639c2237`  
项目路径：`C:\Users\Fy1ng\Documents\Repo\antiaddiction-mod-extracted\antiaddiction-mod`

## 1. 项目定位

这是一个面向 Minecraft 1.21.11 的防沉迷 Mod 项目，目标是同时支持 Fabric 与 NeoForge 两个 Mod Loader，并配套一个低性能要求的网页后台。

核心目标：

- 玩家首次进入游戏时进行实名验证。
- 后端签发 credential 和 rules，客户端验证签名后执行限制。
- 未成年人只能在后台配置的可玩日期、星期、时间段、限时规则内游玩。
- 单人世界需要在不可游玩时间保持加密，避免复制存档绕过限制。
- 后台提供用户、游戏日、日志、仪表盘和 Mod 接入配置管理。

## 2. 架构概览

主要目录：

- `backend/`：Express + SQLite 后台服务和网页管理面板。
- `fabric/`：Fabric 版本客户端 Mod。
- `neoforge/`：NeoForge 版本客户端 Mod。

关键数据流：

1. 客户端提交实名信息到 `/api/verify`。
2. 后端验证并签发 credential，credential 内只包含摘要、年龄、minor 状态、Minecraft 身份等，不下发明文姓名/身份证。
3. 客户端携带 credential 请求 `/api/rules`。
4. 后端签发 rules，包含全局规则、用户当天覆盖规则、服务器时间、规则有效期、今日已游玩秒数。
5. 客户端只信任后端签名 payload，按规则限制进入世界、显示 HUD、上报游玩会话。
6. 后端根据 play session 事件统计用户每天、单人世界、多人服务器的游玩时长。

## 3. 当前关键安全决策

最新明确要求：**仅信任后端，做增量修复。**

已按该要求调整：

- 客户端不再把本地认证 JSON 中的“已游玩秒数”作为可信来源。
- `played_today_seconds` 由后端计算后写入签名 rules payload。
- Fabric/NeoForge 客户端读取签名 rules 中的 `played_today_seconds` 作为今日已用时长基线。
- 客户端只在当前进程内叠加本次会话未同步秒数，用于 HUD 和即时限制。
- 重启游戏后，限时模式的计时基线来自后端 play session 统计，而不是本地文件。
- 会话心跳从 60 秒缩短到 10 秒，减少异常退出后后端统计滞后的窗口。

安全边界：

- 本地 credential/rules 缓存仍用于签名验证和离线短期规则，但本地可编辑计时数据不可信。
- 如果后端不可达或签名规则不可用，未成年人应进入保守限制状态。
- 当前实现仍可能在客户端异常退出时丢失最近一次未上报心跳窗口内的秒数，窗口约 10 秒。

## 4. 已完成或已实现的功能

后端：

- Express 后台服务。
- SQLite 数据存储。
- 管理员登录。
- 用户管理。
- 实名认证用户表。
- 游戏日管理。
- 默认可玩星期、日期段批量设置。
- 用户个人当天规则表 `user_day_rules`。
- 用户每日游玩汇总、单日详情、会话和事件记录接口。
- 仪表盘显示今日状态、认证用户数、日志记录、未成年登录次数、活跃用户、今日总游玩、今日最高目标等。
- Mod 客户端接入配置 URL 使用 `window.location.origin`，匹配实际 http/https、端口和域名。
- `/api/storage-key` 限流调宽，避免正常解密频繁触发 HTTP 429。

客户端共同功能：

- Fabric 与 NeoForge 双 loader。
- 读取后端配置。
- 验证后端 Ed25519 签名 payload。
- 认证状态保存。
- 未成年人限制进入。
- 游戏左下角 HUD：
  - 限时模式显示今日剩余时间。
  - 不限时但有时间段控制时显示当前时间、可玩时间段和窗口剩余时间。
- 单人存档加密/解密相关逻辑。
- play session 上报：
  - `play_start`
  - `play_heartbeat`
  - `play_end`
  - 单人/多人目标识别
  - 离线队列
- `/api/rules` 请求会携带 credential bearer token，以便后端返回用户个人规则和今日已用时长。

## 5. 最近一轮重要修复

### 5.1 HTTP 429 导致无法解密地图

问题：

- 添加防沉迷模组后仍无法解密地图。
- 日志提示 `/api/storage-key` HTTP 429 请求过于频繁。

处理：

- 后端新增可配置限流：
  - `ANTIADDICTION_STORAGE_KEY_USER_LIMIT_PER_HOUR`
  - `ANTIADDICTION_STORAGE_KEY_SAVE_LIMIT_PER_HOUR`
- 默认值：
  - 用户每小时 600 次
  - 单存档每小时 120 次
- 客户端 `StorageKeyManager.fetch` 做同步保护，避免并发重复请求。

### 5.2 后台用户管理和仪表盘 UI

处理：

- 修复用户管理界面错位。
- 用户管理改为用户列表 + 用户详情。
- 对应用户页面可管理当天可玩时长和时间段。
- 最近游玩会话改为每天游玩时长。
- 点击日期查看详细会话和事件记录。
- 优化仪表盘长条控件和大量留白问题。
- 修复 Mod 客户端接入配置中 URL 显示与实际网页 URL 不一致的问题。

### 5.3 重启游戏后限时模式重新计时

初始方案：

- 曾引入客户端本地持久化 `playUsageDate/playUsageSeconds`。

用户指出风险：

- 本地认证状态可被修改，存在绕过风险。

最终方案：

- 撤回本地持久计时信任。
- 后端在签名 rules 中下发 `played_today_seconds`。
- 客户端以内存叠加当前会话，但重启后必须重新从后端同步。

## 6. 当前未提交改动文件

截至最近检查，工作区在 `main...origin/main` 上有未提交改动：

- `backend/play_sessions.js`
- `backend/public/index.html`
- `backend/security.js`
- `backend/security.test.js`
- `backend/server.js`
- `fabric/src/main/java/com/antiaddiction/data/PlayerDataManager.java`
- `fabric/src/main/java/com/antiaddiction/network/ApiClient.java`
- `fabric/src/main/java/com/antiaddiction/network/PlaySessionReporter.java`
- `fabric/src/main/java/com/antiaddiction/storage/StorageKeyManager.java`
- `fabric/src/main/java/com/antiaddiction/time/PlayTimeChecker.java`
- `neoforge/src/main/java/com/antiaddiction/data/PlayerDataManager.java`
- `neoforge/src/main/java/com/antiaddiction/network/ApiClient.java`
- `neoforge/src/main/java/com/antiaddiction/network/PlaySessionReporter.java`
- `neoforge/src/main/java/com/antiaddiction/storage/StorageKeyManager.java`
- `neoforge/src/main/java/com/antiaddiction/time/PlayTimeChecker.java`

新增本文档：

- `PROJECT_HANDOFF.md`

注意：

- 当前改动尚未 commit/push。
- 用户之前要求过 push 到 GitHub，但当前这轮没有再次要求提交；如需提交，需要重新检查 diff 后写 commit。

## 7. 已运行验证

最近已运行并通过：

- `npm --prefix backend run test`
- `node -c backend/server.js`
- 后台 `backend/public/index.html` 内联脚本语法检查
- Fabric：`.\gradlew.bat build`
- NeoForge：`.\gradlew.bat build`
- `git diff --check`

构建后已同步到测试游戏 mods：

- Fabric：
  `C:\Users\Fy1ng\Desktop\Minecraft\1.21.11\.minecraft\versions\1.21.11-Fabric 0.19.2\mods\antiaddiction-fabric-1.0.0.jar`
- NeoForge：
  `C:\Users\Fy1ng\Desktop\Minecraft\1.21.11\.minecraft\versions\1.21.11-NeoForge_21.11.42\mods\antiaddiction-neoforge-1.0.0.jar`

最近 jar 元数据：

- Fabric jar 大小约 `104727` 字节。
- NeoForge jar 大小约 `105700` 字节。

## 8. 后端部署同步范围

如果只部署后端，至少同步：

- `backend/server.js`
- `backend/security.js`
- `backend/play_sessions.js`
- `backend/public/index.html`

建议同时同步测试文件：

- `backend/security.test.js`
- `backend/play_sessions.test.js`
- `backend/rules.test.js`

当前没有新增 npm 依赖，没有修改 `backend/package.json`，通常不需要重新安装后台依赖。

部署步骤建议：

1. 停止当前后台服务。
2. 覆盖同步上述后端文件。
3. 重启后台服务。
4. 后台启动时会自动创建或补齐 SQLite 表，例如 `user_day_rules`。
5. 客户端必须使用新构建 jar，否则不会读取后端签名下发的 `played_today_seconds`。

## 9. 重要开发规则

来自用户项目规则：

- 每次调用工具前必须先用中文说明该工具作用和预期结果。
- 需要网络搜索最新信息时必须使用 Tavily MCP，不要用内置 WebSearch。
- 需要库/API 文档、生成代码、项目基架、配置步骤时应使用 Context7 MCP。
- 修改任何可能影响 Mod 功能的源代码后，必须立即构建最新 mod 文件。
- 构建成功后，必须覆盖同步到测试游戏 mods 文件夹，无需询问。
- 构建失败时暂停并报告错误。
- 用户反馈调试结果后，应自行查看相关 log 或报错文件；如果不知道路径，直接询问用户。

当前可用测试实例路径：

- Fabric mods：
  `C:\Users\Fy1ng\Desktop\Minecraft\1.21.11\.minecraft\versions\1.21.11-Fabric 0.19.2\mods`
- NeoForge mods：
  `C:\Users\Fy1ng\Desktop\Minecraft\1.21.11\.minecraft\versions\1.21.11-NeoForge_21.11.42\mods`

## 10. 下一步建议

优先级较高：

1. 手动验证限时模式重启：
   - 设置一个短限时规则。
   - 进入世界游玩 1 分钟以上。
   - 确认后端收到 play session heartbeat。
   - 退出并重启游戏。
   - 确认剩余时间不是重新从满额开始。

2. 验证异常退出窗口：
   - 在心跳后立即强关游戏。
   - 重启后检查最多只丢失约 10 秒以内计时。

3. 验证用户个人当天规则：
   - 后台为某个用户设置当天时间段和 max_minutes。
   - 客户端重新同步规则。
   - 确认 rules 中个人规则覆盖全局当天规则。

4. 验证存档加密状态：
   - 可玩时间内正常保存退出后，确认离开世界期间存档仍处于加密状态。
   - 将存档复制到同版本无 Mod 或未认证环境，确认不可直接游玩。

5. 提交前检查：
   - 重新运行后端测试。
   - 重新构建 Fabric/NeoForge。
   - 检查 `git diff --stat` 和关键 diff。
   - 写清 commit message，覆盖后台 UI、play session、429 修复、后端权威计时等内容。

## 11. 给下一个 Agent 的注意事项

- 不要把本地认证状态当作可信计时来源。
- 不要重新引入 `playUsageDate/playUsageSeconds` 这类可编辑持久计时字段。
- 如果要进一步减少异常退出丢时，可以考虑后端按服务端收到 heartbeat 的时间连续估算活跃会话，但要避免客户端伪造时长。
- 修改 Fabric 和 NeoForge 代码时，两套同构文件必须保持一致。
- 修改 Mod 源码后必须构建并同步 jar。
- 后台 UI 已经有较多内联 JS，改动前建议先跑内联脚本语法检查。
- 当前工作区包含前几轮 UI/API/429/计时修复的组合改动，提交前不要只看最后一轮 diff。
