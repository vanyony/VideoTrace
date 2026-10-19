# 运行与排障

适用于当前 Windows 本地开发部署。首次安装命令见 [项目 README](../README.md)，变量清单见 [.env.example](../.env.example) 和 [API 配置说明](testing-and-api-configuration.md)。Linux 需调整虚拟环境路径并安装中文字体。

## 启动顺序与就绪检查

1. 配置根目录 `.env`，启动 Docker Compose 基础设施。
2. 构建 Java JAR，启动 Java 后端，让 Flyway 完成数据库迁移。
3. 配置两端相同的内部令牌与 Python 的独立 MinIO 凭据，启动一个 Python 创作进程。
4. 启动前端，登录，上传一个短视频，完成分析，再检查创作就绪状态。

```powershell
docker compose ps
curl.exe http://127.0.0.1:9090/health
curl.exe http://127.0.0.1:8091/health
```

| 服务 | 默认本地端口 | 还需检查什么 |
| --- | --- | --- |
| Java | 9090 | 数据库迁移成功、Redis 可用、MQ 连接、媒体工具可执行 |
| Python | 8091 | 内部令牌已配置、可访问 Java、模型与成片存储凭据齐全 |
| Vue 开发服务 | 5173 | `VITE_DEV_PROXY_TARGET` 指向实际 Java 端口 |
| MySQL | 3307 | Compose 映射容器 3306，应用用户已初始化 |
| Redis | 6379 | 密码匹配，连接到预期数据库编号 |
| MinIO | 9000 / 9001 | S3 API / 控制台；目标桶可访问 |
| Qdrant | 6333 | API Key、集合及向量维度匹配 |
| RocketMQ NameServer | 9876 | Broker 正常注册、应用可达 Broker 公告地址 |

HTTP `/health` 成功仅说明服务可响应。Python 的 `configured` 只说明内部令牌存在，不等于外部模型和 MinIO 端到端可用。

运行中的旧 JAR/Python 进程不会因 `git pull` 自动加载新功能。修改代码后重新构建并重启对应服务；启动前确认原进程和端口，不重复启动多个 Python worker。

## 一次真实验收怎么做

1. 登录并上传一段自己有权处理的短视频，记录 `mediaId`。
2. 提交 `/analysis/ai`，轮询相同 `id/goal/mode` 的状态，检查成功与质量提示。
3. 查询 `/creation/eligibility`，确认当前解析版本可用。
4. 创建 15 秒对话或分镜任务，轮询到 `DISCUSSED` 或 `AWAITING_CONFIRMATION`。
5. 核对事实、来源时间戳、文字可读性和总时长，再显式确认当前分镜版本。
6. 等待渲染完成，读取成片 URL，实际播放并下载；核对版本、尺寸、时长和文件完整性。

测试分层：本地渲染样片不验证模型；模拟模型集成不验证模型质量；真实模型配合固定证据不验证视频解析。已公开 NASA 单样本才覆盖了真实解析主链路，但仍不构成大规模质量保证。对照结果见 [渲染与样片说明](diagram-rendering.md)。

`scripts/smoke-creation.py` 会创建本地测试记录并启动专用进程；阅读脚本后在开发环境运行，不把其本地测试存储配置当作生产配置。真实模型脚本会消耗外部模型额度。

## 配置变更如何生效

| 修改内容 | 操作 |
| --- | --- |
| Java `LLM_*`、ASR、Embedding、OCR、数据库或 MQ 配置 | 重启 Java；模型覆盖关系见配置文档 |
| `CREATOR_LLM_*`、`CREATOR_VISION_*`、渲染字体或存储配置 | 重启 Python |
| 两端共用令牌、共享 `LLM_*` 且 Python 使用回退值 | 同步配置并重启两侧 |
| `VITE_*` | 重启开发服务；生产前端需重新构建部署 |
| 解析规则或 OCR/ASR 策略 | 更新 `VIDEO_PARSE_VERSION` 并重新建立所需上下文 |
| Embedding 模型或维度 | 规划向量重建和集合切换，不能只改模型名复用旧索引 |
| 绘制与动画规则 | 更新渲染版本，避免命中旧样式缓存 |

创作模型必须支持现有工具调用协议，视觉模型必须支持图片输入；接口地址可配不代表所有供应商都无需适配。用 `.env.example` 中的模型选项覆盖不兼容参数，勿把真实 Key 放进命令输出或提交记录。

## Python 成片存储账号

Java 的 `MINIO_*` 配置不会自动补全 Python 的 `CREATOR_MINIO_ACCESS_KEY/SECRET_KEY`。应由管理员为创作服务建立独立账号，绑定目标桶 `creations/*` 范围的策略，再把凭据放入运行环境。

以下是桶名为 `media` 的对象级策略示例；桶名不同需同步修改资源路径：

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "s3:GetObject", "s3:PutObject",
      "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"
    ],
    "Resource": ["arn:aws:s3:::media/creations/*"]
  }]
}
```

使用当前 MinIO 管理工具创建账号并绑定策略后，验证能对该前缀上传和读取对象元数据，且不能读取原视频、写入其他前缀或执行管理操作。Python 的 Bucket、Endpoint 必须与 Java 查询结果时可访问的对象存储一致。本地验收脚本可能复用已有测试凭据，不代表已完成上述部署授权。

## 常见故障

| 现象 | 优先检查 | 处理方向 |
| --- | --- | --- |
| 创作入口提示素材未就绪 | 当前解析版本是否有带时间戳的 ASR/OCR 上下文 | 完成视频分析；单独全文转写不保证可创作 |
| 任务长期 `QUEUED` | Outbox 投递、MQ/Broker、Python 进程与内部地址 | 先定位是解析队列还是创作队列，不重复提交多个新任务 |
| Python `configured=false` 或内部 401 | 两端令牌、Python 是否读到 `.env` | 配置同一非空令牌并重启；不要把用户 token 当作服务令牌 |
| 模型返回 401/403/429 | 对应提供商的凭据、模型权限及额度 | 根据 ASR/Embedding/文本/视觉各自配置排查，避免混用 Key |
| 分镜有效但渲染上传失败 | `CREATOR_MINIO_*`、Bucket、前缀权限和网络 | 配置独立账号；不要反复生成分镜来掩盖存储错误 |
| 中文乱码或找不到字体 | `CREATOR_FONT_PATH` 和实际字体文件 | 指定可读中文字体；不同字体影响排版和缓存 |
| OCR 无法启动 | `OCR_COMMAND`、语言包与 `TESSDATA_PREFIX` | 验证 Tesseract 可运行且含 `chi_sim/eng` |
| 找不到 FFmpeg/ffprobe | Java 进程的 PATH 与 `FFMPEG_DIR` | 从正确环境启动；Python 的 imageio-ffmpeg 不等于系统已装 ffprobe |
| 分镜持续被拒绝 | 错误中的场景/元素、文字框高度、边界和证据 ID | 修正需求或让 Agent 按反馈集中修复，不放松校验绕过 |
| 修改或确认返回 409 | 分镜版本、当前操作、相同 requestId 是否变更了内容 | 刷新后用当前版本发起新意图；丢失响应则先查询原请求结果 |
| 已有作品但新版本预览 404 | `revision` 与 `artifactRevision` 是否不同 | 显式读取已有作品版本，或确认并渲染当前分镜 |
| 视频 URL 失效 | 链接是否过期、浏览器能否访问 MinIO 地址 | 重新查询作品接口；容器内地址不一定能供浏览器访问 |
| 大文件合并慢或磁盘不足 | 本地临时文件、音频/截图及并发任务占用 | 当前合并需本地完整文件，容量规划需计入并发与预处理数据 |

## 定位任务与保留数据

排查时记录 `mediaId/taskId/operationId/attempt/revision` 和错误发生时间。Java 业务快照保存在 `creation_tasks/creation_operations/creation_artifacts`；Python 操作和工具反馈在 `CREATOR_DATA_DIR/operations.sqlite`，图 Checkpoint 在 `checkpoints.sqlite`。这些数据可能含原视频文字，不直接贴到公开 issue。

| 数据 | 作用与保留要求 |
| --- | --- |
| MySQL 及 Flyway 迁移记录 | 业务身份、任务快照、阶段恢复和作品归属；需备份 |
| MinIO 原片、截图、`creations/*` | 实际媒体；只备份数据库不能恢复视频对象 |
| Python `CREATOR_DATA_DIR` | 操作、Checkpoint、事件和渲染数据；容器化时需持久卷 |
| Redis | 会话、锁与辅助状态；清空可能使用户重新登录并改变运行中的协调状态 |
| Qdrant 与解析版本配置 | 向量检索索引；重建需有可用的原始上下文与模型服务 |

SQLite 使用 WAL。备份时停止写入或使用 SQLite 一致性备份机制，不能只拷贝主文件而忽略尚未合并的 WAL。整理渲染缓存前确认没有活跃任务，并保留用户还需访问的成片和归属记录。

## 已知限制

- 当前 Python 仅支持单进程 worker，SQLite 不是已验证的横向扩展方案。
- 四小时音频预处理中发现一个 825 字节无效尾段，尚未修复；不要宣称四小时云端完整解析已通过。
- 来源时间戳跟随解析分段，不是词级精确定位；证据引用合法不能替代事实复核。
- 真实单样本分析出现过 Critic 提示，仍需用户确认内容，不能将渲染成功当作语义正确。
- 当前 Compose 主要面向本地开发；公网部署还需落实网络入口、数据备份、资源配额与链接下载出口控制。
