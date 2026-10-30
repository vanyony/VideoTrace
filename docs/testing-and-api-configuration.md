> 当前平台已移除独立视频分析。下列容量记录为历史测量；当前素材准备入口为创作页及 `POST /creation/prepare-source`，Java 使用 OkHttp 直接请求模型。

# 视频容量测试与 API 配置

记录日期：2026-10-19。容量测试对应实现包含提交 `0036b4f`，随后增加二维图解渲染。本文不包含任何真实 API Key。

## 测试范围与环境

Windows 本地环境，Ryzen 7 8845H（8 核 16 线程），约 28 GiB 系统可见内存；通过 HTTP 请求访问 Java 服务及本地 MinIO。使用 FFmpeg 和本地 Tesseract；本轮长视频测试没有调用云端 ASR、Embedding 或大模型 API。

公开源视频：[Blender Big Buck Bunny 320×180](https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4.zip)。长视频由公开视频循环生成；容量边界样本额外添加合法 MP4 free box 填充。因此这些测试不能证明高清长视频解析性能、真实四小时内容理解质量或公网吞吐量。

## 上传与存储结果

| 样本 | 时长 | 文件字节数 | 分片数 | 分片上传耗时 | 合并耗时 | 结果 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 原始公开视频 | 596.46 秒 | 64,657,027 | 13 | 2.44 秒 | 3.78 秒 | 通过 |
| 循环视频 | 3,600.02 秒 | 389,901,640 | 75 | 8.75 秒 | 20.02 秒 | 通过 |
| 容量边界（含填充） | 3,600.02 秒 | 2,149,580,800 | 410 | 42.97 秒 | 119.72 秒 | 通过 |
| 四小时循环视频（无填充） | 14,400.01 秒 | 1,560,164,609 | 298 | 31.53 秒 | 77.91 秒 | 通过 |

上述样本均验证了：上传会话分片状态恢复、重复分片幂等、冲突分片拒绝、重复完成请求幂等，以及下载文件与原文件 SHA-256 一致。超过上限 1 字节的初始化请求被拒绝。测试直接请求后端，未覆盖浏览器界面操作。

当前上限为 5 MiB × 410 = **2050 MiB（2,149,580,800 字节，约 2 GiB）**，属于业务配置选择，不是 Java 或 MinIO 的固有限制。分片大小及数量上限目前在代码中定义，并非通过 `.env` 动态修改；调整时需同步前后端约束并验证。

合并流程：分片先存储到 MinIO；Java 从 MinIO 读取分片，写入本地完整临时文件并校验，再上传完整文件到 MinIO，最后清理临时文件。当前没有使用 MinIO 服务端分片合并。因此扩大上限会增加本地磁盘及传输开销。本次测试不能证明高并发容量或最大可解析时长。

## 四小时本地预处理结果

| 阶段 | 结果 | 耗时与说明 |
| --- | --- | --- |
| 音频切片 | 存在失败项 | FFmpeg 执行完成，耗时 334.84 秒，生成 241 个 MP3；逐个解码检查，240 个正常，最后一个无效 |
| 抽帧 | 通过 | 24.61 秒，生成 3013 张原始帧，总计 47,662,628 字节；末帧时间戳 14,394.958333 秒 |
| 本地 OCR 抽样 | 调用正常 | 均匀抽取 12 张，2 路并发，总计 3.50 秒；单张约 0.50～0.66 秒，多数没有文字 |

音频尾段 `audio_240.mp3` 只有 825 字节，FFmpeg 提示找不到连续 MPEG 音频帧，无法解码。生产流程会遍历所有音频片段，因此该尾段可能导致 ASR 阶段失败及重试；本轮没有调用 ASR 服务确认供应商行为，也没有修改生产逻辑修复该问题。**不能将本轮结果描述为“四小时完整解析通过”。**

抽帧数量为相邻帧 dHash 去重前数量；OCR 仅做 12 张抽样，不是完整 OCR 测试或识别准确率评测。生产 ASR/OCR 分支并行执行，而本地测量分阶段进行，各耗时不能直接相加作为生产总耗时。

## 外部 API 与模型配置

以下为当前本地使用方式及可修改的配置入口。示例配置见仓库根目录 `.env.example`；真实密钥仅放本机 `.env` 或部署环境变量中。

| 用途 | 当前方式 / 接口形式 | 配置项 | 修改约束 |
| --- | --- | --- | --- |
| Java 素材摘要、检索意图 | DeepSeek 官方；Chat Completions | `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`、`LLM_TIMEOUT_SECONDS` | 可修改；需兼容现有文本聊天接口及结构化输出要求 |
| 创作讨论、分镜 Agent | DeepSeek 官方；Chat Completions + Tool Calling | `CREATOR_LLM_BASE_URL`、`CREATOR_LLM_API_KEY`、`CREATOR_LLM_MODEL`、`CREATOR_LLM_OPTIONS`、`CREATOR_MODEL_TIMEOUT_SECONDS` | 可独立修改；模型需支持工具调用；未设置的覆盖项回退到相应 `LLM_*` 配置 |
| 按需原视频画面理解 / 实验分镜预览检查 | DeepSeek 官方；包含图片的 Chat Completions | `CREATOR_VISION_BASE_URL`、`CREATOR_VISION_API_KEY`、`CREATOR_VISION_MODEL` | 可独立修改；模型需支持图片输入；Key 未设置时优先复用 `LLM_API_KEY`，随后回退到创作模型 Key |
| ASR | 硅基流动；`POST https://api.siliconflow.cn/v1/audio/transcriptions`，multipart 音频 | `ASR_URL`、`ASR_MODEL`、`SILICONFLOW_API_KEY` | 可修改；替代服务需兼容当前请求与转写响应格式 |
| Embedding | 硅基流动；`POST https://api.siliconflow.cn/v1/embeddings` | `SILICONFLOW_BASE_URL`、`SILICONFLOW_API_KEY`、`EMBEDDING_MODEL` | 可修改；替换向量模型需重新生成已有向量，并核对 Qdrant 集合维度 |
| 基础 OCR | 本地 Tesseract，无外部 API | `OCR_PROVIDER=local`、`OCR_COMMAND`、`TESSDATA_PREFIX` | 可修改可执行程序与语言包路径；当前使用中文和英文语言包 |
| 可选多模态 OCR | `OCR_PROVIDER=multimodal`；图片 Chat Completions | `OCR_URL`、`OCR_MODEL`、`OCR_API_KEY` | 切换后才调用外部服务；Key 未设置时复用 `SILICONFLOW_API_KEY`；与按需画面理解工具分别配置 |
| BM25 关键词检索 | Java 本地计算，无外部 API | 不使用模型 Key | 与 Embedding 检索融合；不需要付费模型调用 |
| MP4 渲染 | 本地 React / Remotion / Chromium / FFmpeg，无视频生成 API | `REMOTION_BROWSER_EXECUTABLE`、`CREATOR_FONT_PATH`、`CREATOR_RENDER_TIMEOUT_SECONDS` | Python 保留结构/文字校验和成片验证；需安装 Node.js、锁定渲染依赖和浏览器 |

当前 `.env.example` 的文本和画面理解模型默认值均为 `deepseek-flash`，Embedding 为 `BAAI/bge-m3`，ASR 为 `TeleAI/TeleSpeechASR`。多模态 OCR 示例值为 `zai-org/GLM-5.3-Flash`，但 `OCR_PROVIDER=local` 时该模型配置不会生效。模型名称是否可用取决于提供商账户与接口支持。

`ASR_URL` 是独立完整地址，修改 `SILICONFLOW_BASE_URL` 不会自动改变它。ASR 和 Embedding 当前共用 `SILICONFLOW_API_KEY`，没有分别独立的 Key 配置项。创作模型选项用于传递供应商参数，不代表任意模型都能无需适配直接替换。

## 生效方式与内部接口

配置在服务启动时读取，**不支持页面热切换**。修改 Java 使用的变量后重启 Java；修改 `CREATOR_*` 模型、字体或渲染配置后重启 Python。修改共享 `LLM_*` 且创作侧使用其回退值时，需同时重启两侧。修改前端 `VITE_*` 后需重启开发服务或重新构建部署。

Java 与 Python 使用内部 HTTP API 交互，`CREATOR_URL` 和 `CREATOR_JAVA_URL` 分别设置对端地址，`CREATOR_INTERNAL_TOKEN` 用于内部认证，两侧必须一致。修改地址不等于修改协议。MinIO 地址、Bucket 和凭据也可配置；Python 成品上传使用独立的 `CREATOR_MINIO_*` 配置，需提供受限账户，不能仅配置 Java 的 MinIO 凭据就认为渲染存储已就绪。

更改解析策略后可调整 `VIDEO_PARSE_VERSION`，用于区分阶段结果版本；该配置不能修复既有不兼容数据或自动迁移向量。

## 复现入口与原始记录

二维动画的分镜字段、10 种物体、6 种布局、3 套主题、演示成片与新增回归结果见 [二维图解渲染说明](diagram-rendering.md)。渲染示例使用人工分镜，无模型调用，与本文的长视频容量测试分别验收。

- `scripts/measure-upload-capacity.py`：原视频、一小时循环、容量边界上传测试；`--hours 4` 测试四小时循环视频。
- `scripts/measure-video-preprocessing.py`：四小时音频切片、逐段解码校验、抽帧及抽样 OCR；`--resume` 复用已有音频阶段记录。
- 本地原始结果：`creator/data/upload-capacity/result.json`、`result-4h.json`、`creator/data/preprocessing-4h/result.json`。容量测试媒体、原始结果及运行日志留在本机，不上传 GitHub；另行公开的二维演示样片位于 `examples/diagram-demo/`。

脚本依赖项目本地服务、Python 虚拟环境、FFmpeg、Tesseract 及相应本地配置。生成大文件和上传会消耗磁盘及本地服务资源；运行脚本前可先查看脚本参数和所需环境。


## 视觉实验补充（2026-10-20）

2026-10-30 的 Remotion 正式链路、持久会话模式和工具暴露评测见 [Agent 与渲染验收](agent-routing-evaluation.md)。下面的样片和数字为历史记录。

全屏画布、组件动画、多模态预览检查及独立 Remotion 样片见 [视觉实验说明](visual-playground.md)。预览检查复用 `CREATOR_VISION_*`，默认使用官方 DeepSeek 图片输入接口，可独立修改；抽样图片会发送给配置的模型服务。没有新增视频生成 API。

这批样片与上述长视频容量测试独立：使用合成水循环文本、真实模型工具调用或开发时手工编排，不能并入真实上传解析的端到端测试结论。前三条样片早于设计 Prompt 调整；新增 Prompt v1 样片单独记录了一次同条件测试，不能据此声称稳定质量提升。
