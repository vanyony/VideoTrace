# VideoTrace

AI 视频内容理解与检索平台。通过语音转写、画面文字识别和大语言模型，将视频转化为可检索、可提问、可回到原片段核验的内容。

适用于课程、会议、访谈、发布会和操作教程等场景，帮助用户快速了解视频重点，围绕具体问题获取回答，并通过时间戳查看原始上下文。

## 功能

- **视频导入**：本地文件上传及在线视频链接导入（需配置受控出口代理）；大文件支持分片上传、断点续传和分片校验。
- **异步解析**：提取语音转写（ASR）与关键帧文字（OCR），持久化阶段结果，中断后复用已完成阶段。
- **目标驱动分析**：围绕用户目标执行规划、分析与校验流程，生成结构化结果。
- **长视频检索与问答**：对内容分块，结合向量检索与关键词评分选取相关片段。
- **证据定位**：将分析结果和检索片段关联到原视频时间戳，点击查看对应内容。
- **任务进度**：通过 SSE 推送处理状态，提供失败任务管理与重试入口。

当前内容理解主要依赖语音和画面文字，关键帧用于 OCR 和证据展示，尚未完整覆盖人物动作、物体关系等纯视觉内容。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot、MyBatis-Plus、LangChain4j |
| 前端 | Vue 3、Vite |
| 数据与缓存 | MySQL、Flyway、Redis、Redisson |
| 异步任务 | RocketMQ |
| 对象存储与向量检索 | MinIO、Qdrant |
| 媒体处理 | FFmpeg、ffprobe、Tesseract、yt-dlp |

## 处理流程

```text
本地上传 / 视频链接
        ↓
MinIO 存储 + MySQL 媒体记录
        ↓
持久投递记录 → RocketMQ → 分析任务执行
        ↓
ASR 语音转写 + 关键帧 OCR
        ↓
阶段结果持久化 → 内容分块与向量索引
        ↓
相关片段检索 → AI 规划、执行与校验
        ↓
分析结果 / 内容问答 / 原视频证据定位
```

后端围绕长耗时任务实现了以下机制：

- **可靠投递**：先保存任务投递记录，再发送 MQ 消息，定时扫描并补偿失败投递。
- **执行恢复**：通过租约、心跳和条件更新管理任务归属，回收并重新调度过期任务。
- **阶段复用**：保存 ASR、OCR 阶段结果与 AI 工作流检查点，减少恢复时的重复处理。
- **资源控制**：隔离处理线程池，限制本地并发与跨实例调用频率，并设置 AI 执行预算。

## 快速开始

以下命令在项目根目录执行，示例使用 PowerShell。

### 环境要求

- JDK 21 或更新版本，Maven 3.9+（也可使用项目 Maven Wrapper）
- Node.js 22.12+ 与 npm
- Python 3（用于后端启动脚本）
- Docker 与 Docker Compose
- FFmpeg、ffprobe、Tesseract；使用视频链接导入时还需 yt-dlp
- 可调用的 LLM、Embedding 和 ASR 服务凭据

将 `java`、`ffmpeg`、`ffprobe` 和 `tesseract` 加入 PATH。Tesseract 需安装 `chi_sim` 与 `eng` 语言数据。

### 1. 配置环境变量

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，填写模型服务密钥并替换示例密码：

| 配置 | 说明 |
| --- | --- |
| `SILICONFLOW_API_KEY` | 默认模型网关凭据，用于 LLM、Embedding 和 ASR |
| `SILICONFLOW_BASE_URL`、`LLM_MODEL` | LLM 服务地址及模型 |
| `EMBEDDING_MODEL`、`ASR_MODEL` | 向量模型及语音识别模型 |
| `DB_USERNAME`、`DB_PASSWORD` | 应用数据库账号；`MYSQL_APP_USER` 应与 `DB_USERNAME` 一致 |
| `MYSQL_ROOT_PASSWORD` | Compose 初始化 MySQL 使用的 root 密码 |
| `REDIS_PASSWORD` | Redis 密码 |
| `MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY` | MinIO 凭据 |
| `QDRANT_API_KEY` | Qdrant 凭据 |

完整配置见 [.env.example](.env.example) 和 [application.properties](server/src/main/resources/application.properties)。不要将真实密钥或 `.env` 提交到版本库。

### 2. 启动基础设施

```powershell
docker compose up -d --build
docker compose ps
```

Compose 启动 MySQL、Redis、MinIO、Qdrant 和 RocketMQ。首次构建 MinIO 镜像需要下载并编译源码，可能耗时较长。

确认服务就绪后再启动后端。数据库默认名为 `videotrace`，表结构由 Flyway 管理。

### 3. 构建并启动后端

```powershell
mvn -f server/pom.xml -s server/.mvn/central-settings.xml package -DskipTests
New-Item -ItemType Directory -Force docs | Out-Null
python scripts/run-backend.py
```

启动脚本读取根目录 `.env` 并运行构建后的 JAR；`docs/` 用于保存本地进程记录。后端默认监听 `http://localhost:9090`。

没有安装 Maven 时，可用以下命令替代构建命令：

```powershell
Push-Location server
.\mvnw.cmd -s .mvn/central-settings.xml package -DskipTests
Pop-Location
```

### 4. 启动前端

在另一个终端执行：

```powershell
cd client
npm ci
npm run dev
```

打开终端显示的地址，默认是 `http://localhost:5173`。注册并登录后，上传视频或导入链接，输入分析目标，再查看结果、提问或跳转证据片段。

前端开发服务器通过代理访问后端，目标地址由根目录 `.env` 的 `VITE_DEV_PROXY_TARGET` 配置。

## 配置说明

- **模型服务**：默认接入 SiliconFlow；切换服务时需核对 LLM、Embedding 和 ASR 各自的接口兼容性。ASR 地址可通过 `ASR_URL` 配置。
- **解析版本**：调整提取规则或 ASR／OCR 模型后，更新 `VIDEO_PARSE_VERSION`，区分解析结果与相关缓存。
- **资源限制**：通过 `MEDIA_PROCESS_CONCURRENT`、`*_CALL_CONCURRENT` 和 `*_CALLS_PER_MINUTE` 调整并发与频率；各 Worker 应使用一致的共享频率配置。
- **AI 预算**：通过 `AGENT_MAX_ROUNDS`、`AGENT_MAX_DURATION_MS` 和 `AGENT_MAX_ESTIMATED_TOKENS` 限制执行消耗。启用成本预算时需同时配置输入、输出 Token 单价。
- **视频链接**：`YTDLP_PROXY` 留空时关闭链接导入，本地上传不受影响。启用前须配置 `http://host:port` 形式的受控 HTTP 代理，逐次阻止访问本机、内网、链路本地、保留网段和云元数据地址，并在网络层阻止下载进程及子进程绕过代理直连。普通代理本身不提供这些安全保障。部分站点需要登录 Cookie，可配置 `YTDLP_COOKIES` 或 `YTDLP_COOKIES_FROM_BROWSER`，前者优先。

## 测试与构建

后端测试：

```powershell
mvn -f server/pom.xml -s server/.mvn/central-settings.xml test
```

真实 MySQL Mapper 测试需额外配置 `VIDEOTRACE_TEST_JDBC_URL`、`VIDEOTRACE_TEST_DB_USER` 和 `VIDEOTRACE_TEST_DB_PASSWORD`；未配置时跳过。

前端测试与生产构建：

```powershell
cd client
npm test
npm run build
```

`scripts/` 中提供上传、消息队列恢复和重启清理的冒烟检查脚本。这些检查需要运行中的服务，部分脚本会改变服务状态，执行前请阅读对应脚本。

## 项目结构

```text
VideoTrace/
├── client/                 # Vue 前端与交互测试
├── server/
│   ├── src/main/java/      # API、任务调度、媒体解析与 AI 服务
│   ├── src/main/resources/ # 应用配置、数据库迁移与评测样例
│   └── src/test/           # 后端测试
├── scripts/                # 本地启动与冒烟检查
├── infra/minio/            # MinIO 镜像构建
├── rocketmq/               # Broker 配置
├── docker-compose.yml      # 本地基础设施
└── .env.example            # 环境变量示例
```

## 部署注意事项

当前 Compose 面向本地开发，服务端口默认绑定回环地址。对外部署时需配置 HTTPS、访问控制、备份与资源配额。

视频链接下载共享媒体处理并发配额，配额耗尽时返回繁忙提示。公网地址校验不能单独防止 DNS 重绑定或重定向到内网；启用链接导入必须落实上述代理与网络出口限制。

`/health` 当前仅用于检查 HTTP 服务是否响应，不代表数据库、队列或模型服务已就绪。任务恢复机制也不保证外部模型调用严格只执行一次。AI 回答可能存在遗漏或误判，关键内容应结合原视频证据核验。

## 许可证

本项目采用 [MIT License](LICENSE)。第三方依赖及服务遵循各自的许可证与使用条款。

