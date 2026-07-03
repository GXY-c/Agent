# 基于 Spring 的技术方案实现 AgentRunner 功能等价物

## 1. 项目实现方案

### 1.1 总体架构

采用 **Spring Boot 3.x** 作为基础框架，结合 **Playwright**（浏览器自动化）、**LLM 集成**、**文件处理**、**WebSocket** 等组件。将浏览器扩展的核心能力抽象为 REST API + WebSocket 服务，部署为独立后端，前端/客户端通过 API 调用。

```
┌─────────────────────────────────────────────────┐
│                 Client Layer                      │
│  (Web UI / CLI / External Apps / Chrome Ext)    │
└───────────────────┬─────────────────────────────┘
                    │ HTTP / WebSocket
                    ▼
┌─────────────────────────────────────────────────┐
│           Spring Boot Backend                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐   │
│  │ Browser   │  │ LLM      │  │ 文件/导出    │   │
│  │ Automation│  │ Service   │  │ Service      │   │
│  │ (Playwright)│          │  │              │   │
│  ├──────────┤  ├──────────┤  ├──────────────┤   │
│  │ Recording│  │ WebSocket│  │ 图表生成     │   │
│  │ & HAR    │  │ Hub      │  │ (JFreeChart) │   │
│  └──────────┘  └──────────┘  └──────────────┘   │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐   │
│  │ 认证/授权 │  │ 存储     │  │ 消息队列     │   │
│  │ (Spring  │  │ (MySQL/  │  │ (RabbitMQ/   │   │
│  │  Security) │  │  S3)    │  │  Redis)      │   │
│  └──────────┘  └──────────┘  └──────────────┘   │
└─────────────────────────────────────────────────┘
```

### 1.2 各模块实现方案

#### 模块 1：浏览器自动化引擎（BrowserAgentService）

- **技术栈**：Playwright for Java（微软官方库，控制 Chromium/Firefox/WebKit）
- **功能**：
  - `executeTask(task, config)`：接收自然语言任务，调用 LLM 解析为操作步骤序列，逐条执行。
  - 支持操作：导航、点击、输入、滚动、等待、截图、获取页面文本等。
  - 状态回调：通过 WebSocket 或 SSE 推送当前步骤状态。
- **实现要点**：
  - 维护浏览器实例池（每个用户或会话分配独立 BrowserContext）。
  - LLM 解析使用 LangChain4j 或直接调用 OpenAI / 阿里云 DashScope API。
  - 操作超时机制（默认为 120 秒）。
  - 错误重试与回滚。

#### 模块 2：网页保存（PageSaverService）

- **技术栈**：Playwright + 第三方 SingleFile 命令行工具（Node.js）或纯 Java 实现
- **功能**：
  - 接收 URL，打开页面，保存为完整 HTML（内联 CSS、图片 Base64 编码）。
  - 支持选项：移除隐藏元素、压缩 HTML、延迟加载图片等。
- **实现要点**：
  - 使用 Playwright 获取页面内容后，通过 `page.content()` 获取 HTML，再调用 `SingleFile` 工具优化（或自己实现资源内联）。
  - 超时控制（120 秒），文件名自动生成。

#### 模块 3：录制与回放（RecorderService）

- **技术栈**：Playwright 的 Tracing 功能 + 自定义事件记录
- **功能**：
  - 开始录制：启动 Playwright Tracing（可记录屏幕截图、DOM 快照）。
  - 停止录制：导出为 HAR（HTTP Archive）或自定义 JSON（步骤列表 + Base64 截图）。
  - 回放：解析录制文件，模拟操作回放（基于 Playwright 动作序列）。
- **HAR 录制**：Playwright 本身提供 `page.route()` 记录网络请求，导出 HAR 格式。

#### 模块 4：AI 对话导出（ChatExporterService）

- **技术栈**：普通 Java + 模板引擎（Thymeleaf / Freemarker）+ Markdown 生成库（CommonMark / Flexmark）
- **功能**：
  - 接收对话数据（JSON 格式），转换为 Markdown 格式（包含 YAML Front Matter）。
  - 支持主流平台标记（DeepSeek / 通义千问等）。
  - 返回 .md 文件下载。
- **实现要点**：
  - 定义通用对话数据模型（角色、内容、时间、附件）。
  - 使用 Flexmark 库生成 GFM（GitHub Flavored Markdown）格式。

#### 模块 5：图表生成（ChartMakerService）

- **技术栈**：JFreeChart 或 XChart（Java 图表库） + 图片输出
- **功能**：
  - 接收 JSON 数据（labels + datasets），生成柱状图、折线图、饼图等。
  - 返回 PNG/JPEG 图片文件。
- **实现要点**：
  - 支持 Chart.js 类似输入结构。
  - 可配置图表样式、颜色、标题。

#### 模块 6：WebSocket Hub（HubController / WebSocketHandler）

- **技术栈**：Spring WebSocket + STOMP 或原生 WebSocket
- **功能**：
  - 允许外部客户端（如 Node.js 服务）通过 WebSocket 连接，发送 `execute` 命令。
  - 服务端执行任务并实时推送状态、结果。
  - 支持认证：首次连接需提供 Token，可选自动批准模式。
- **实现要点**：
  - 每个 WebSocket 连接绑定一个会话（Session），独立浏览器上下文。
  - 任务队列管理，防止并发执行冲突。

#### 模块 7：认证与授权

- **技术栈**：Spring Security + JWT
- **功能**：
  - 用户注册/登录，生成 JWT 令牌。
  - 每个 API 请求需携带 `Authorization: Bearer token`。
  - 管理用户认证令牌（存储在数据库）。

### 1.3 技术选型总结

| 组件 | 技术选型 |
|------|----------|
| 后端框架 | Spring Boot 3.2+ |
| 浏览器自动化 | Playwright for Java 1.40+ |
| LLM 集成 | LangChain4j (OpenAI / DashScope) |
| 数据存储 | MySQL (用户/任务记录) + Redis (Session/缓存) |
| 文件存储 | MinIO / 本地文件系统 / S3 |
| Markdown 生成 | Flexmark 0.62+ (GFM) |
| 图表生成 | JFreeChart 1.5+ |
| WebSocket | Spring WebSocket |
| 消息队列 | RabbitMQ (可选，用于任务队列) |
| 日志 | Logback + ELK |
| 构建工具 | Maven / Gradle |

### 1.4 API 设计

| 端点 | 方法 | 描述 |
|------|------|------|
| `POST /api/v1/tasks/execute` | 执行自然语言任务 | 请求体：`{ task, config: { baseURL, model, apiKey, ... } }` |
| `POST /api/v1/tasks/{id}/stop` | 停止任务 | |
| `GET /api/v1/tasks/{id}/status` | 获取任务状态（SSE） | |
| `POST /api/v1/pages/save` | 保存网页 | 请求体：`{ url, options }` |
| `POST /api/v1/recording/start` | 开始录制 | 请求体：`{ url }` |
| `POST /api/v1/recording/stop` | 停止录制 | |
| `POST /api/v1/exporter/chat` | 导出 AI 对话 | 请求体：`{ platform, conversations }` |
| `POST /api/v1/charts` | 生成图表 | 请求体：`{ type, labels, datasets, options }` |
| WebSocket `/ws/hub` | Hub 连接 | 发送 JSON 命令，接收实时消息 |

---

## 2. 项目目录结构

agent-runner-backend/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/agentrunner/
│   │   │   ├── AgentRunnerApplication.java
│   │   │   ├── config/
│   │   │   │   ├── WebConfig.java
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   ├── PlaywrightConfig.java
│   │   │   │   ├── AsyncConfig.java
│   │   │   │   ├── RedisConfig.java
│   │   │   │   ├── RabbitMqConfig.java
│   │   │   │   ├── MinioConfig.java
│   │   │   │   └── SwaggerConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── TaskController.java
│   │   │   │   ├── PageSaveController.java
│   │   │   │   ├── RecordingController.java
│   │   │   │   ├── ChatExportController.java
│   │   │   │   ├── ChartController.java
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── UserController.java
│   │   │   │   └── HealthController.java
│   │   │   ├── service/
│   │   │   │   ├── task/
│   │   │   │   │   ├── BrowserAgentService.java
│   │   │   │   │   ├── TaskExecutor.java
│   │   │   │   │   ├── LLMService.java
│   │   │   │   │   ├── TaskContext.java
│   │   │   │   │   ├── StepPlanner.java
│   │   │   │   │   └── ActionExecutor.java
│   │   │   │   ├── page/
│   │   │   │   │   ├── PageSaverService.java
│   │   │   │   │   ├── SingleFileConverter.java
│   │   │   │   │   └── PageResourceResolver.java
│   │   │   │   ├── recording/
│   │   │   │   │   ├── RecorderService.java
│   │   │   │   │   ├── HarRecorder.java
│   │   │   │   │   ├── StepRecorder.java
│   │   │   │   │   └── ReplayService.java
│   │   │   │   ├── export/
│   │   │   │   │   ├── ChatExporterService.java
│   │   │   │   │   ├── MarkdownGenerator.java
│   │   │   │   │   ├── SelectorsParser.java
│   │   │   │   │   └── YamlFrontMatterBuilder.java
│   │   │   │   ├── chart/
│   │   │   │   │   ├── ChartService.java
│   │   │   │   │   ├── ChartType.java
│   │   │   │   │   └── ChartRenderer.java
│   │   │   │   ├── hub/
│   │   │   │   │   ├── HubService.java
│   │   │   │   │   ├── MessageHandler.java
│   │   │   │   │   └── HubSession.java
│   │   │   │   ├── auth/
│   │   │   │   │   ├── AuthService.java
│   │   │   │   │   ├── JwtTokenProvider.java
│   │   │   │   │   └── UserDetailsServiceImpl.java
│   │   │   │   ├── user/
│   │   │   │   │   ├── UserService.java
│   │   │   │   │   └── UserSettingsService.java
│   │   │   │   └── storage/
│   │   │   │       ├── FileStorageService.java
│   │   │   │       └── DownloadService.java
│   │   │   ├── repository/
│   │   │   │   ├── UserRepository.java
│   │   │   │   ├── TaskRepository.java
│   │   │   │   ├── RecordingSessionRepository.java
│   │   │   │   ├── ExportHistoryRepository.java
│   │   │   │   ├── HubConnectionRepository.java
│   │   │   │   └── UserSettingsRepository.java
│   │   │   ├── model/entity/
│   │   │   │   ├── User.java
│   │   │   │   ├── Task.java
│   │   │   │   ├── RecordingSession.java
│   │   │   │   ├── ExportHistory.java
│   │   │   │   ├── HubConnection.java
│   │   │   │   └── UserSettings.java
│   │   │   ├── model/dto/
│   │   │   │   ├── request/
│   │   │   │   │   ├── TaskRequest.java
│   │   │   │   │   ├── PageSaveRequest.java
│   │   │   │   │   ├── RecordingStartRequest.java
│   │   │   │   │   ├── ChatExportRequest.java
│   │   │   │   │   ├── ChartRequest.java
│   │   │   │   │   ├── LoginRequest.java
│   │   │   │   │   └── UserSettingsRequest.java
│   │   │   │   └── response/
│   │   │   │       ├── TaskResponse.java
│   │   │   │       ├── PageSaveResponse.java
│   │   │   │       ├── RecordingStateResponse.java
│   │   │   │       ├── ChatExportResponse.java
│   │   │   │       ├── ChartResponse.java
│   │   │   │       ├── AuthResponse.java
│   │   │   │       └── ErrorResponse.java
│   │   │   ├── websocket/
│   │   │   │   ├── TaskProgressWebSocket.java
│   │   │   │   ├── HubWebSocketHandler.java
│   │   │   │   └── WebSocketSessionManager.java
│   │   │   ├── scheduler/
│   │   │   │   ├── TaskCleanupScheduler.java
│   │   │   │   └── TempFileCleanupScheduler.java
│   │   │   ├── exception/
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   ├── BusinessException.java
│   │   │   │   ├── ResourceNotFoundException.java
│   │   │   │   ├── TaskExecutionException.java
│   │   │   │   ├── UnauthorizedException.java
│   │   │   │   └── RateLimitException.java
│   │   │   ├── util/
│   │   │   │   ├── JsonUtils.java
│   │   │   │   ├── FileUtils.java
│   │   │   │   ├── UrlUtils.java
│   │   │   │   ├── ImageUtils.java
│   │   │   │   └── HtmlSanitizer.java
│   │   │   └── constants/
│   │   │       ├── TaskStatus.java
│   │   │       ├── RecordingStatus.java
│   │   │       ├── ExportPlatform.java
│   │   │       └── HubConnectionStatus.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       ├── db/migration/
│   │       │   ├── V1__init_user_table.sql
│   │       │   ├── V2__init_task_table.sql
│   │       │   └── ...
│   │       ├── static/
│   │       ├── templates/
│   │       └── selectors/
│   │           ├── deepseek.json
│   │           ├── tongyi.json
│   │           └── ...
│   └── test/
│       ├── java/com/agentrunner/
│       │   ├── AgentRunnerApplicationTests.java
│       │   ├── config/
│       │   │   └── TestConfig.java
│       │   ├── controller/
│       │   │   ├── TaskControllerTest.java
│       │   │   ├── PageSaveControllerTest.java
│       │   │   ├── RecordingControllerTest.java
│       │   │   ├── ChatExportControllerTest.java
│       │   │   ├── ChartControllerTest.java
│       │   │   ├── AuthControllerTest.java
│       │   │   ├── UserControllerTest.java
│       │   │   └── HealthControllerTest.java
│       │   ├── service/
│       │   │   ├── task/
│       │   │   │   ├── BrowserAgentServiceTest.java
│       │   │   │   ├── TaskExecutorTest.java
│       │   │   │   ├── LLMServiceTest.java
│       │   │   │   ├── StepPlannerTest.java
│       │   │   │   └── ActionExecutorTest.java
│       │   │   ├── page/
│       │   │   │   ├── PageSaverServiceTest.java
│       │   │   │   └── SingleFileConverterTest.java
│       │   │   ├── recording/
│       │   │   │   ├── RecorderServiceTest.java
│       │   │   │   ├── HarRecorderTest.java
│       │   │   │   ├── StepRecorderTest.java
│       │   │   │   └── ReplayServiceTest.java
│       │   │   ├── export/
│       │   │   │   ├── ChatExporterServiceTest.java
│       │   │   │   ├── MarkdownGeneratorTest.java
│       │   │   │   └── SelectorsParserTest.java
│       │   │   ├── chart/
│       │   │   │   ├── ChartServiceTest.java
│       │   │   │   └── ChartRendererTest.java
│       │   │   ├── hub/
│       │   │   │   ├── HubServiceTest.java
│       │   │   │   └── MessageHandlerTest.java
│       │   │   ├── auth/
│       │   │   │   ├── AuthServiceTest.java
│       │   │   │   └── JwtTokenProviderTest.java
│       │   │   ├── user/
│       │   │   │   └── UserServiceTest.java
│       │   │   └── storage/
│       │   │       ├── FileStorageServiceTest.java
│       │   │       └── DownloadServiceTest.java
│       │   ├── repository/
│       │   │   ├── UserRepositoryTest.java
│       │   │   ├── TaskRepositoryTest.java
│       │   │   ├── RecordingSessionRepositoryTest.java
│       │   │   ├── ExportHistoryRepositoryTest.java
│       │   │   └── HubConnectionRepositoryTest.java
│       │   ├── util/
│       │   │   ├── JsonUtilsTest.java
│       │   │   ├── FileUtilsTest.java
│       │   │   ├── UrlUtilsTest.java
│       │   │   ├── ImageUtilsTest.java
│       │   │   └── HtmlSanitizerTest.java
│       │   └── exception/
│       │       └── GlobalExceptionHandlerTest.java
│       └── resources/
│           ├── application-test.yml
│           ├── test-data/
│           │   ├── sample-chat-export.json
│           │   ├── sample-chart-data.json
│           │   └── sample-page-save.html
│           └── selectors/
│               ├── deepseek.json
│               └── tongyi.json