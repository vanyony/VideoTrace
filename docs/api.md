# 核心 API 契约

本文覆盖上传、解析及创作主路径。旧版问答、管理与诊断接口不在这里逐个列出；路由源代码见 [Controller 目录](../server/src/main/java/com/videotrace/controller)。默认 Java 地址为 `http://127.0.0.1:9090`。

## 响应与认证

Java JSON 接口统一响应：

```json
{"code":0,"message":"success","data":{}}
```

`code=0` 表示成功；异步请求返回 HTTP 202 只代表已受理，需继续查询状态。调用方同时检查 HTTP 状态和业务码。SSE、视频对象下载和 MP3 导出不是该 JSON 结构。Python 内部接口也不套 Java 的响应包装。

除注册、登录和健康检查等公开入口外，用户接口要求 `Authorization: Bearer <token>`；登录响应的 `data.token` 为会话凭据。业务接口依据认证用户检查素材及任务归属，不能通过请求里的 `ownerId` 取得其他用户权限。

| 方法与路径 | 参数 / 请求体 | 用途 |
| --- | --- | --- |
| `POST /user/register` | JSON：`username`、`password`，可选 `nickname` | 注册，账号 3～32 位字母数字下划线，密码 8～128 位 |
| `POST /user/login` | JSON：`username`、`password` | 返回用户信息和 token |
| `POST /user/logout` | Bearer token | 撤销当前会话 |
| `GET /health` | 无 | Java HTTP 存活检查，不证明全部依赖就绪 |

## 上传与原片

| 方法与路径 | 参数形式 | 返回 `data` / 行为 |
| --- | --- | --- |
| `POST /media/upload` | multipart：`file` | 小文件直接上传，返回媒体摘要 |
| `POST /media/init-upload` | query：`filename`、`totalBytes`、`totalChunks` | 返回上传会话字符串 `uploadId`；新客户端应总是提供 `totalBytes` |
| `POST /media/upload-chunk` | query/form：`uploadId`、`chunkIndex`、`totalChunks`；multipart：`file` | 保存分片，`chunkIndex` 从 0 开始 |
| `GET /media/upload-status` | query：`uploadId` | 已接收分片索引集合 |
| `POST /media/complete-upload` | query：`uploadId` | 完成合并并返回媒体摘要，重复完成可读取已有结果 |
| `GET /media/list` | 无 | 当前用户素材列表 |
| `GET /media/playback` | query：`id` | 原片可读 URL |
| `POST /media/upload-url` | query/form：`url` | 需启用受控出口代理的视频链接导入 |
| `DELETE /media/delete` | query：`id` | 删除自己拥有的原片 |

分片固定 5 MiB，最多 410 片，分片上传路径逻辑文件上限为 2050 MiB；末片按剩余长度上传。同一索引、相同内容可重试，冲突内容拒绝。会话属于创建它的用户。Java 从 MinIO 读取分片，在本地临时文件中拼接并校验，再上传完整对象；不是 MinIO 服务端分片合并。

直接上传还受单次 HTTP multipart 与部署入口限制，不能将分片逻辑上限套用到所有代理和所有上传方式。

## 解析与创作就绪

| 方法与路径 | 参数 | 用途 |
| --- | --- | --- |
| `POST /creation/prepare-source` | query：`mediaId` | 异步准备创作素材，返回 HTTP 202 与 `state/result/message`；重复提交返回已有状态 |
| `GET /creation/source-status` | query：`mediaId` | 素材准备状态，刷新后可恢复进度 |
| `POST /media-processing/transcribe` | query：`id` | 仅提取全文，不替代完整素材准备 |
| `GET /media-processing/transcription-status` | query：`id` | 文字提取状态 |
| `GET /media-processing/transcription-events` | query：`id` | 文字提取 SSE |
| `GET /media-processing/download` | query：`id` | 音频 MP3 导出 |
| `GET /creation/eligibility` | query：`mediaId` | 返回 `eligible/reason/sourceVersion` |

素材状态包括 `NOT_STARTED/QUEUED/PROCESSING/COMPLETED/FAILED`。创作页直接发起准备，不再调用独立分析。准备失败后可重试；已完成的 ASR/OCR 阶段及上下文复用，执行租约过期后自动恢复。所有素材接口校验登录与媒体归属。旧 `/analysis/*` 产品接口已移除。

## 创作对话与分镜

| 方法与路径 | JSON 请求 / query | 行为 |
| --- | --- | --- |
| `POST /creation/conversations` | `mediaId`、`requirement`、`durationSeconds`、`requestId` | 推荐入口；模型判断先讨论还是直接生成 |
| `POST /creation/tasks` | 同上 | 直接创建分镜生成操作，跳过初始对话路由 |
| `GET /creation/tasks` | query：`mediaId` | 最近最多 100 个创作任务 |
| `GET /creation/tasks/{id}` | 无 | 任务快照、消息、当前分镜、版本、状态及错误 |
| `POST /creation/tasks/{id}/messages` | `instruction`、`baseRevision`、`requestId`，可选 `targetSceneIds` | 继续讨论、生成或明确修改 |
| `POST /creation/tasks/{id}/revisions` | 同上 | 显式修订已有分镜 |
| `POST /creation/tasks/{id}/render` | `baseRevision`、`requestId` | 用户确认当前版本后触发渲染 |
| `POST /creation/tasks/{id}/retry` | `requestId` | 失败且可重试时重试原操作，不借此更改原需求 |
| `GET /creation/tasks/{id}/artifact` | 可选 query：`revision` | 对应版本成片元数据和可读 URL |

`requirement`、`instruction` 最多 2000 字。页面提供 15/30/45/60 秒；接口兼容 30～60 秒整数，另外支持 15 秒。修改现有任务时保持已确认总时长，改变选择器需新建任务。

模板版或自由画布版通过创作需求指定，例如“使用发布会大字模板”或“用自由画布解释物体关系”；当前没有独立的模式选择控件。实际返回模式以分镜字段为准，客户端不能仅凭需求文本判定已生成的画面类型。

`requestId` 最多 128 字符，仅字母、数字、下划线、连字符；同一用户同一请求意图复用 ID，内容变更使用新 ID。`baseRevision` 必须等于当前任务版本。首次讨论尚未产生分镜时通常为 0，首次分镜为 1；根据查询结果取值，不在客户端自行猜测递增。

示例：

```json
{
  "mediaId": 42,
  "requirement": "提炼这个视频的核心观点，做成15秒发布会风格短视频",
  "durationSeconds": 15,
  "requestId": "create_demo_001"
}
```

生成后修改指定场景：

```json
{
  "instruction": "第二幕缩短文案，保留事实和时长",
  "baseRevision": 1,
  "targetSceneIds": ["s2"],
  "requestId": "revise_demo_001"
}
```

`s2` 必须取自返回的分镜 ID，不能假设所有任务都采用该名称。用户通过自然语言修订，不直接向公开接口提交任意绘图代码或整份模型分镜。

主要状态为 `QUEUED/DRAFTING/REVISING/DISCUSSED/AWAITING_CONFIRMATION/RENDERING/COMPLETED/FAILED`。讨论结束不会自动创建视频；是否有成片还应检查 `artifactRevision`，需要时显式查询那个版本。`GET artifact` 未指定版本时按当前分镜版本查找，不保证该版本已有作品。签名链接过期后重新查询，勿持久化旧 URL 代替任务和版本。

## 内部服务协议

内部接口使用 `X-Creator-Token`，不是用户 Bearer token。两端 `CREATOR_INTERNAL_TOKEN` 必须一致且非空；Python 要求配置的令牌至少 32 字符。内部接口应限制在服务网络，不作为客户端模型工具接口直接暴露。

| 方向 | 路径 | 内容 |
| --- | --- | --- |
| Java → Python | `POST /internal/operations` | `taskId/operationId/attempt/ownerId/mediaId/sourceVersion/type/parameters`；返回 HTTP 202 |
| Java → Python | `GET /internal/operations/{operation_id}` | 接收和执行状态；未接收的操作可返回 404 |
| Python → Java | `POST /internal/creation/tasks/{taskId}/evidence-search` | `operationId/attempt/query/limit` |
| Python → Java | `POST /internal/creation/tasks/{taskId}/context` | `operationId/attempt/evidenceId/windowMs` |
| Python → Java | `POST /internal/creation/tasks/{taskId}/frame` | `operationId/attempt/evidenceId/timestampMs`；时间点需位于检索证据区间 |
| Python → Java | `POST /internal/creation/tasks/{taskId}/events` | 操作、尝试次数、事件序号、阶段状态及可选结果 |

这些接口还检查当前操作、素材归属和解析版本。模型的 `inspect_video_frame` 工具接收的问题由 Python 处理，Java 帧接口不负责调用视觉模型。

## 错误处理

401 检查登录/内部令牌；403 检查归属；404 检查任务或作品版本；409 常见于版本冲突、素材未就绪或操作仍在执行；429 表示限流。网络超时后先查询状态，用原 `requestId` 重试同一提交，不应立即创建新意图。错误信息与具体 HTTP 状态以服务响应为准。

完整分镜字段、模式互斥与校验约束见 [渲染协议](diagram-rendering.md)，任务保护机制见 [架构说明](architecture.md)。


## 视觉实验：模型工具协议

这些是 Python Agent 的 Tool Calling 能力，不是新增的公开 HTTP 接口。最终视频仍由已有确认接口触发。

| 工具 | 参数 | 行为 |
| --- | --- | --- |
| `review_storyboard_preview` | `{storyboard: 完整候选分镜, sceneIds?: 1到3个场景ID, focus?: 最多400字}` | 校验证据与修改范围，渲染抽样图片并获取多模态建议；不保存业务分镜，不生成MP4 |
| `save_storyboard` | `{reviewedPreviewId: 最新成功预览ID}` 或 `{storyboard: 完整分镜}` | 引用已检查快照或提交同指纹分镜；兼容旧版直接传分镜，full与已开始检查的操作必须匹配成功检查 |
| `patch_scenes` | 原有修订参数，或 `{baseRevision, reviewedPreviewId}` | 保存已检查的修订，保持版本与目标场景校验 |

预览反馈含 `previewId`、`draftSha256`、采样时间与图片哈希、`summary`、`issues`、`advisoryOnly` 和缓存标识；问题包含场景ID、抽样时间、相关元素ID、类别、严重程度、建议和置信度。模型响应引用未采样场景/时间会被拒绝。保存工具的追踪结果可含 `previewReview.matchesSavedDraft`，它只表示版本匹配，不代表视觉质量合格；full或已启用预览的操作不允许保存不匹配的分镜。[完整说明](visual-playground.md)。
