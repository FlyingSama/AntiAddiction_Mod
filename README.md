# Minecraft 防沉迷合规系统

适配 Minecraft 1.21.11 的客户端防沉迷 Mod 与 Web 管理后台。项目同时维护 Fabric 与 NeoForge 两套 Loader 实现，后端使用 Node.js、Express 和 SQLite 提供实名认证、规则下发、游玩日志、日历管理和存档加密密钥服务。

## 当前能力

| 模块 | 能力 |
| --- | --- |
| 实名认证 | 客户端启动后要求完成姓名和身份证号认证，后端签发带策略版本的凭证。 |
| 时间规则 | 支持默认可玩星期、默认可玩时间段、限时分钟、单日例外、调休非玩日和节假日数据。 |
| 游戏内提示 | HUD 左下角显示当前时间、可玩时段、窗口剩余时间或今日剩余限时。 |
| 到时处理 | 倒计时后走原生断开/保存流程，避免重入卡死，并回到防沉迷限制界面。 |
| 游戏日日历 | Web 后台和游戏内均支持绿色可玩日、橙色限时日、红色调休非玩日；游戏内显示分钟级时间段。 |
| 批量管理 | 后台支持按日期段批量设置、按星期批量设置、默认规则调整和全年节假日同步。 |
| 用户日志 | 记录登录、下线、单人地图、多人服务器及剩余游玩时长，用于后台用户详情和仪表盘汇总。 |
| 存档保护 | 单人世界退出后加密 `level.dat` 与 `level.dat_old`，非认证状态下保持加密，防止复制到同版本游戏绕过限制。 |
| 后台安全 | 管理登录、密码修改、运行配置校验、签名规则下发、存档密钥吊销与基础限流。 |

## 项目结构

```text
antiaddiction-mod/
├── backend/
│   ├── server.js                  # Express API、后台页面、SQLite 初始化
│   ├── security.js                # 身份凭证、规则签名、存档密钥派生
│   ├── play_sessions.js           # 游玩会话记录与汇总
│   ├── public/index.html          # 管理后台 SPA
│   └── *.test.js                  # 后台回归测试
├── fabric/
│   └── src/main/java/com/antiaddiction/
│       ├── mixin/                 # TitleScreen、HUD、LevelStorage 注入
│       ├── network/               # 后端通信、离线日志队列
│       ├── screen/                # 认证、限制、日历界面
│       ├── storage/               # level.dat 加解密与密钥缓存
│       └── time/                  # 规则判断与运行时强制检查
├── neoforge/
│   └── src/main/java/com/antiaddiction/
│       └── 与 Fabric 对齐的 Mojang 映射实现
└── docs/
```

## 环境要求

- JDK 21
- Node.js 18+
- Minecraft 1.21.11
- Fabric Loader 0.19.x 或 NeoForge 21.11.x

## 构建 Mod

```bash
cd fabric
./gradlew build
# 输出: fabric/build/libs/antiaddiction-fabric-1.0.0.jar

cd ../neoforge
./gradlew build
# 输出: neoforge/build/libs/antiaddiction-neoforge-1.0.0.jar
```

Windows 下可使用 `gradlew.bat build`。

## 启动后台

```bash
cd backend
npm install
npm run test
npm start
```

默认访问地址为 `http://localhost:3000`。首次本地开发默认管理员为 `admin / admin123`，生产环境应配置环境变量并修改默认密码。

常用环境变量：

| 变量 | 说明 |
| --- | --- |
| `NODE_ENV` | `production` 时启用生产配置校验。 |
| `ANTIADDICTION_PRIVATE_KEY_PEM` | 规则和凭证签名私钥，生产环境必填。 |
| `ANTIADDICTION_PUBLIC_KEY_PEM` | 客户端校验使用的公钥。 |
| `ANTIADDICTION_KEY_ID` | 签名密钥 ID。 |
| `ANTIADDICTION_SERVER_SECRET` | 存档密钥派生服务端密钥，生产环境必填。 |
| `ANTIADDICTION_POLICY_VERSION` | 规则策略版本号。 |

## 客户端配置

在 `.minecraft/config/antiaddiction.properties` 中配置后台地址：

```properties
backend_url=http://your-server:3000
```

完成实名认证后，客户端会缓存后端签发凭证，并使用该凭证同步规则、上报日志和请求存档密钥。

本地开发时可使用：

```properties
environment=development
backend_url=http://localhost:3000
development_public_key_base64=<后台启动日志打印的临时公钥>
```

生产环境不要依赖临时密钥，应通过 `ANTIADDICTION_PRIVATE_KEY_PEM` 或 `ANTIADDICTION_PRIVATE_KEY_PATH` 提供固定 Ed25519 私钥，并在客户端内置或配置匹配公钥。

## Web 后台

后台包含以下页面：

- 仪表盘：展示今日登录、游玩时长、单人/多人活跃、重要规则和游戏日概览。
- 游戏日管理：支持默认可玩星期、默认时段、默认限时分钟、日期段批量设置、星期批量设置和单日例外。
- 用户管理：用户列表与用户详情，展示剩余游玩时长、登录/下线、单人地图与多人服务器记录。
- 登录日志：查看认证、登录、下线和客户端上报事件。
- 系统设置：管理员密码、运行参数和存档密钥管理。

## 规则语义

系统使用“默认规则 + 单日例外”的模型：

- 默认规则保存在 `default_config`，包含可玩星期、开始时间、结束时间和默认限时分钟。
- 单日例外保存在 `game_days`，可覆盖某一天是否可玩、时间段、限时分钟、标签和调休标记。
- `max_minutes = 0` 表示不限时，仅受可玩时间段约束。
- `max_minutes > 0` 表示限时模式，客户端按当日剩余时长和可玩窗口共同限制。

## 存档加密机制

单人世界正常退出时，Mod 会在最终保存后加密：

- `level.dat`
- `level.dat_old`

进入世界或读取世界列表时，客户端会使用当前认证凭证向后台请求 `/api/storage-key`。后台根据用户、Minecraft UUID、存档名、服务端密钥和随机 salt 派生 AES-256-GCM 密钥。未安装 Mod、未认证、无网络或密钥被吊销时，存档保持加密状态，世界列表会显示加密提示，不能直接加载明文世界。

该设计的目标是保证用户所有不在世界内的时间，单人存档关键入口文件都处于加密状态。

## 主要 API

### Mod 客户端

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/verify` | 实名认证并签发客户端凭证。 |
| `GET` | `/api/rules` | 获取签名规则、默认规则和每日例外。 |
| `POST` | `/api/play-session/event` | 上报登录、下线、单人地图、多人服务器会话事件。 |
| `POST` | `/api/storage-key` | 获取指定存档的加密密钥。 |

### 管理后台

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/admin/login` | 管理员登录。 |
| `GET` | `/api/admin/dashboard` | 仪表盘数据。 |
| `GET` | `/api/admin/users` | 用户列表。 |
| `GET` | `/api/admin/users/:id/play-logs` | 用户游玩日志。 |
| `GET` | `/api/admin/game-days/calendar` | 日历数据和默认规则。 |
| `PUT` | `/api/admin/game-days/:date` | 编辑单日规则。 |
| `POST` | `/api/admin/game-days/batch-range` | 批量设置日期段。 |
| `POST` | `/api/admin/game-days/batch-weekdays` | 批量设置星期。 |
| `POST` | `/api/admin/storage-keys/revoke` | 吊销存档密钥。 |

## 验证命令

```bash
npm --prefix backend run test
node -c backend/server.js
cd fabric && ./gradlew build
cd ../neoforge && ./gradlew build
```

## 备注

- 不要提交 `backend/antiaddiction.db`、`backend/antiaddiction.db-wal`、`backend/antiaddiction.db-shm` 或 `backend/sessions/`。
- 生产部署必须使用独立的签名私钥和 `ANTIADDICTION_SERVER_SECRET`。
- Fabric 与 NeoForge 的功能应保持一致，新增客户端能力时需要同步两套实现。

## License

MIT
