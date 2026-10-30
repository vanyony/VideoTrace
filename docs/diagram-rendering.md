# 二维图解动画渲染

更新日期：2026-10-20。

本文保留已有字段和历史验收记录。2026-10-30 起，正式绘制改为 `creator/remotion/` 的 React 组件与 Remotion；Vue 主站及分镜 JSON 协议继续保留。旧样片和旧性能数字按原制作方式理解，不能当作新渲染器的效果保证。

## 实现边界

### GitHub 样片与模式对应

当前同时保留模板和自由画布实现。`motion` 中的 `launch`、`kinetic` 是预设视觉设计，Agent 决定内容、模板选择、场景时长和转场；`canvas` 则由 Agent 生成基础图形、坐标、层级和关键帧 JSON。两者均由程序绘制，不执行模型输出的绘图代码。同一场景的模式互斥，同一视频可按需求选择模式。

| 公开样片 | 输入 | 模式 | 文件 |
| --- | --- | --- | --- |
| 温室效应：设计模板版 | 真实 NASA 视频前 55 秒及真实 ASR/OCR | `motion.launch` / `motion.kinetic` | [15 秒 MP4](../examples/greenhouse-template/demo-15s.mp4)、[分镜](../examples/greenhouse-template/storyboard.json)、[验证记录](../examples/greenhouse-template/verification.json) |
| 温室效应：自由画布版 | 复用上行同一视频的持久化解析证据 | `canvas` | [15 秒 MP4](../examples/greenhouse-canvas/demo-15s.mp4)、[分镜](../examples/greenhouse-canvas/storyboard.json)、[验证记录](../examples/greenhouse-canvas/verification.json) |
| 视频如何变成短视频：设计模板演示 | 固定合成演示文本 | `motion.launch` / `motion.kinetic` | [15 秒 MP4](../examples/motion-demo/demo-15s.mp4) |

模板演示此前已公开；真实视频的模板样片此次单独补充，未覆盖原合成演示或自由画布视频。模板版适合视觉一致的大字信息表达，自由画布版适合用基础图形解释关系，两者都仍然可用。

Agent 生成结构化分镜，Python 验证并解析跨幕状态，React 组件呈现画面，Remotion 驱动时间轴并生成预览或 MP4；最终成片仍需用户确认。渲染阶段没有单独的创作 Prompt，不执行模型生成的绘图代码，也不调用外部视频生成 API。动画来自预置组件、布局和动作的组合。

| 项目 | 可选值 |
| --- | --- |
| 物体 `icon` | `video`、`audio`、`text`、`agent`、`cards`、`phone`、`server`、`database`、`person`、`file` |
| 布局 `layout` | `pipeline` 串联、`split` 分发、`merge` 汇聚、`compare` 对比、`bars` 比例柱图、`orbit` 中心关系 |
| 主题 `theme` | `dark` 深色、`light` 浅色、`warm` 暖色 |
| 动作 `motion` | `slide` 滑入、`pulse` 脉冲、`draw` 连线绘制、`reveal` 逐项出现 |
| 输出 | 720×1280 竖屏、24 FPS；页面选择 15/30/45/60 秒 |
| 音乐 | 内置 `light_01` 或 `none` |

流程连线包含移动圆点；历史样片的底部进度条在当前实验分支已移除。对象是图标式示意图，不是任意物体绘画、写实产品图或复杂人物动作。原有 `title/points/flow` 文字卡片保持兼容；三套新主题作用于带 `visual` 的图解场景。

## 分镜结构

### 发布会与动态文字风格

场景还可使用 `motion`，与 `visual`、`canvas` 互斥：

```json
{
  "style": "launch",
  "eyebrow": "STEP 01 / 提取内容",
  "words": ["长视频", "变成", "短视频"],
  "accent": "violet",
  "transition": "push"
}
```

`style` 为 `launch`（大标题、拼版卡片、渐变背景）或 `kinetic`（描边重复字、错峰文字揭示、强调线和扫过条带）；`words` 为 1～3 个大标题短语，每个最多 12 字。`accent` 为 `violet/cyan/lime`，`transition` 为 `fade/push/wipe`，`eyebrow` 是最多 28 字的栏目标签。场景的 `headline` 用作承接说明，`points` 最多 3 条、每条最多 28 字，用作卡片说明。

模型负责提炼短词、选择样式、安排场景时长与转场，程序提供经过设计的字体、颜色和动作。15 秒样片采用 5 幕、每幕 3 秒，交替两种样式，保持内容推进；目前不是任意品牌素材生成，也没有产品摄影或三维材质。

历史 Python 转场在下一幕开始的 0.3 秒内合成末帧；该样片使用 `motion-v4.2`，后续实验版本为 `playground-v7.1`。当前 Remotion 对 motion 的 fade/push/wipe 在开头 0.45 秒衔接，不缩短总时长；它是视觉衔接，不是通用物体身份追踪。当前缓存按完整分镜与渲染器/字体/音乐指纹区分，不再按历史单场景缓存命中数验收。

公开演示：[15 秒 MP4](../examples/motion-demo/demo-15s.mp4) · [五幕预览](../examples/motion-demo/preview.png) · [分镜 JSON](../examples/motion-demo/storyboard.json)。真实 DeepSeek 生成分镜，使用固定合成证据，不经过真实视频解析链路。成片 1,259,356 字节，SHA-256 为 `d55b86fe6359fb1ad142a2dcfd1c92bd80156e15f5b224cbc23d1afdeb0c97ae`。

复现命令：`creator/.venv/Scripts/python.exe scripts/smoke-canvas-model.py --duration 15 --style motion --render`。会调用当前配置的外部模型并产生费用；脚本保留生成阶段报告，渲染失败后可复用已有分镜。

### 自由画布扩展

除原有 `visual` 模板之外，场景现在可使用 `canvas` 自由组合画面；两者互斥。标题和页脚仍由系统绘制，中间安全绘图区为 `x=40..680, y=360..980`（像素）。节点的 `x/y` 表示中心位置，`width/height` 表示尺寸，`z` 为层级，`color/fill` 为十六进制颜色。

默认 `canvas.layout=framed` 的 `canvas.elements` 每幕最多 20 项，支持 `circle`、`rectangle`、`text`、`icon`、`line`、`arrow`。连线使用 `fromId/toId` 引用两个非连线元素，端点随节点运动。图标复用原有 10 种图形，额外物体可由基础图形组合；不是任意绘图代码执行。图标标签使用单独文字元素，矩形和圆形可包含文字。

默认 framed 模式每元素最多 4 个 `keyframes`，包含 `timeMs` 与可选的 `x/y/scale/opacity`，时间严格递增且不超过当前场景时长；省略的属性沿用前值。`easing` 支持 `linear/easeInOut/easeOut`，决定到达该关键帧的插值方式。首个关键帧不是 0 时，从初始属性插值；最后一个关键帧之后保持状态。连线位置由引用决定，仅支持透明度关键帧。背景和文字颜色需保持可读对比度。

Python 检查元素 ID、引用、有限数值、尺寸、关键帧和全文字实际排版，检查初始状态及所有动画端点边界；所用缓动单调，插值期间也保持在端点范围内。Java 回调独立检查数量、类型、引用、参数和动画边界。不能自动保证元素之间不遮挡或内容事实完全准确，仍需预览和人工确认。

示例节点（放在 `canvas.elements` 中）：

```json
{
  "id": "agent",
  "kind": "rectangle",
  "x": 360, "y": 650,
  "width": 200, "height": 120,
  "text": "创作 Agent",
  "fontSize": 28,
  "keyframes": [
    {"timeMs": 0, "x": 240, "opacity": 0},
    {"timeMs": 1500, "x": 360, "opacity": 1, "easing": "easeOut"}
  ]
}
```

Agent 通过 `save_storyboard({storyboard: 完整分镜})` 提交画布（兼容旧版直接分镜参数），`patch_scenes` 可整体替换某一场景的 `canvas`；由 `visual` 切换时需同时将 `visual` 设为 `null`，反之亦然。旧分镜序列化保持原字段，局部修改仍校验版本并保护非目标场景。早期自由画布使用过 `canvas-v3`；当前实验分支缓存版本为 `playground-v7.1`。

### 固定图解结构

每个场景可增加 `visual`；旧分镜无需补字段。示例：

```json
{
  "sceneId": "scene_1",
  "template": "flow",
  "durationMs": 6000,
  "headline": "让 Agent 汇集内容证据",
  "points": ["根据需求检索与核实内容"],
  "animation": "fade",
  "sourceRefs": ["e_0_10000"],
  "visual": {
    "layout": "merge",
    "theme": "dark",
    "motion": "draw",
    "items": [
      {"icon": "audio", "label": "语音转写", "value": 50},
      {"icon": "text", "label": "画面文字", "value": 50},
      {"icon": "agent", "label": "创作 Agent", "value": 50}
    ]
  }
}
```

每个图解含 2～4 个元素，标签最多 12 字；最多两条短要点，每条排版不超过两行。`split` 的第一项连接其余项，`merge` 的前几项连接最后一项，`pipeline` 按顺序连接。`value` 为 0～100，仅比例柱图使用；非柱图默认值没有数据含义。Agent 提示词要求柱图数值有证据支持，当前没有独立的数值事实校验器。

提示词位于 `creator/agent.py`，规定模型结合内容选择图解、保持主题一致，并通过检索证据填写 `sourceRefs`。Python 使用 Pydantic 白名单和排版校验，Java 回调再次验证图解配置、物体、比例、证据及版本。`patch_scenes` 支持修改 `visual`；指定场景修改时仍保护其他场景。前端展示布局、主题、动作和元素标签，最终效果通过成片预览查看。

修改配色与图标绘制代码见 `creator/visuals.py`；修改输出与缓存版本见 `creator/renderer.py`。这些不是 `.env` 参数；修改渲染规则后需更新 `TEMPLATE_VERSION`，避免复用旧样式缓存。

## 示例与复现

[30 秒示例视频](../examples/diagram-demo/demo.mp4) · [场景预览](../examples/diagram-demo/preview.png) · [完整演示分镜](../examples/diagram-demo/storyboard.json)

演示内容：原始视频 → 语音和画面文字 → Agent 汇集证据 → 三张分镜 → 本地渲染并输出手机成片。5 个场景各 6 秒，文件 1,533,203 字节，SHA-256：`85449cc75720bdb30846cdab7d30e834d3a1084984af1caf07a476da7f9c015c`。

在项目根目录执行：

```powershell
creator/.venv/Scripts/python.exe scripts/render-diagram-demo.py
```

结果位于 `creator/data/diagram-demo/`。脚本不依赖 Java、MinIO 或模型 API；需要项目 Python 依赖和中文字体。字体不同或编码环境不同，文件哈希和大小可能变化。

样片使用人工构造的演示分镜，证据 ID 是合成占位符，不代表真实视频检索结果；该脚本直接调用渲染器，用于展示渲染能力，不走线上分镜证据审核。音轨复用项目内置音乐，没有使用上传视频或用户私有素材。不能将本样片描述为真实模型端到端生成验收。

## 固定图解验证结果

- Python 全量 23 项通过，包含布局/主题/动作组合、帧差异、旧分镜兼容、非法物体拒绝、Agent 模拟工具生成及局部修改。
- Java 93 项中 92 项通过、1 项数据库集成测试跳过；增加图解回调白名单和比例边界检查。
- 前端 36 项通过，生产构建成功。
- 实际渲染 30 秒 MP4，校验分辨率、帧率、时长和可解码性，并人工检查五幕预览。

这些是功能回归与演示结果，没有大规模用户质量评测。运行中的服务需重新构建/重启才会加载对应代码。

## 自由画布验证结果

- 手工演示分镜：`scripts/render-canvas-demo.py`，5 幕 30 秒，720×1280、24 FPS，成片 1,360,207 字节；检查了五幕排版和编码元信息。结果在本地 `creator/data/canvas-demo/`。
- 真实模型：`scripts/smoke-canvas-model.py --render`，本轮使用配置中的 `deepseek-flash`，约 11.88 秒生成 3 幕自由画布分镜。真实模型调用了两次 `search_video`、一次 `read_context`、一次 `save_storyboard`，无结构修复错误；分镜由模型生成，未使用手工布局替代。
- 真实模型生成的分镜成功渲染为 30 秒视频，964,635 字节。该次记录在本地 `creator/data/canvas-model-smoke/a26bd18c/`。检索结果来自合成演示文本，未经过真实 Java 解析或真实视频检索，不能宣称完整端到端验收。
- Java 完整回归 95 项，无失败，1 项数据库集成测试跳过；前端 36 项通过且生产构建通过。Python 新增插值、全部基础元素、非法引用/时间/越界/文本拒绝、Agent 修复、局部修改和旧分镜兼容测试。

新代码上线时需重新构建 Java 并重启两侧，开发前端会按现有开发模式加载更新。当前本地长期运行的服务尚未因本轮变化重启；演示通过独立测试进程生成。

## 15 秒档位与模型对照

发布会与动态文字本轮验证：Python 全量 31 项通过，前端 37 项通过且构建成功；Java 创作协议与画布校验相关 12 项通过。15 秒成片已检查分辨率、帧率、总时长和转场时刻的实际解码帧。没有据此宣称视觉质量达到参考图的所有细节。

前端新增 15 秒，保留 30/45/60 秒。Java 任务创建、分镜与成片回调，以及 Python 总时长校验均已支持 15 秒；每幕仍至少 2 秒，3～8 幕，分镜总时长必须等于选择器时长。已有接口的 30～60 秒整数时长兼容保留，新增支持 15 秒，不开放 16～29 秒任务。

曾使用本机订阅登录的 Codex CLI 默认 `gpt-6-astra` 生成 30 秒自由画布样片，验证了 CLI → JSON → 本地渲染流程，未接入生产 Agent 的 API Tool Calling 链路。按后续用户偏好，未来 GPT 测试固定为 `gpt-6.1-sol`、`low`，不能将此前样片标为 6.1 sol。最新版公开风格样片使用 DeepSeek。

## 真实视频自由画布验收

公开样片：[15 秒 MP4](../examples/greenhouse-canvas/demo-15s.mp4) · [预览](../examples/greenhouse-canvas/preview.png) · [分镜](../examples/greenhouse-canvas/storyboard.json) · [验证摘要](../examples/greenhouse-canvas/verification.json)。

源视频为 [NASA《What Is the Greenhouse Effect?》](https://science.nasa.gov/kids/videos/video-what-is-the-greenhouse-effect/)，原片约 149.95 秒；测试取前 55.02 秒并缩放到 854×480，保留真实画面和英语旁白。源视频和本地运行数据不上传仓库。

第一次完整测试经过真实 HTTP 上传、RocketMQ 解析调度、ASR/OCR、Java 内容分析与证据检索、DeepSeek 分镜、显式渲染请求、MinIO 上传下载；处理约 97 秒（不含启动和素材下载），生成发布会/动态文字样片。随后本次自由画布测试复用该素材的持久化解析结果，在同一独立测试数据库创建新创作任务，未重复 ASR/OCR。

自由画布成功尝试中，分镜约 27.31 秒，渲染及结果下载约 26.95 秒；模型调用两次 `search_video`、一次 `read_context`、一次 `save_storyboard`。5 幕各 3 秒，每幕 5～7 个元素，只使用 `canvas`，没有手工修改模型坐标或换成设计模板。输出为 720×1280、24 FPS、15 秒，1,239,551 字节；下载文件哈希与对象存储元数据一致。

首次尝试发现文字框溢出，模型逐个修正后耗尽原有预算。修复后校验会一次汇总多个场景和元素的问题，并反馈所需文字框高度；提示词补充文字留白规则，让模型集中处理错误。保持原有修复次数与排版约束，没有通过放宽校验接受错误结果。重试成功，成功尝试未触发排版修复错误。针对批量反馈、画布和其他动画模式的 10 项回归检查通过。

本次为单样本功能验收：原分析阶段存在部分结论需人工核验的提示；证据时间戳来自转写分段，不代表词级定位。自由画布主体仍为基础图形，不能据此宣称复杂美术生成能力。测试服务运行在独立数据库、MQ 主题、向量集合和端口，测试结束后停止；主服务未因本次测试重启。
