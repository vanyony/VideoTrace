# 二维图解动画渲染

更新日期：2026-10-19。

## 实现边界

Agent 生成结构化分镜，用户确认后，Pillow 绘制每一帧，MoviePy/FFmpeg 编码并拼接为 MP4。渲染阶段没有单独的模型 Prompt，不执行模型生成的绘图代码，也不调用外部视频生成 API。动画来自预设图形、布局和动作的组合。

| 项目 | 可选值 |
| --- | --- |
| 物体 `icon` | `video`、`audio`、`text`、`agent`、`cards`、`phone`、`server`、`database`、`person`、`file` |
| 布局 `layout` | `pipeline` 串联、`split` 分发、`merge` 汇聚、`compare` 对比、`bars` 比例柱图、`orbit` 中心关系 |
| 主题 `theme` | `dark` 深色、`light` 浅色、`warm` 暖色 |
| 动作 `motion` | `slide` 滑入、`pulse` 脉冲、`draw` 连线绘制、`reveal` 逐项出现 |
| 输出 | 720×1280 竖屏、24 FPS、30～60 秒；页面选择 30/45/60 秒 |
| 音乐 | 内置 `light_01` 或 `none` |

流程连线包含移动圆点；画面底部显示场景进度。对象是图标式示意图，不是任意物体绘画、写实产品图或复杂人物动作。原有 `title/points/flow` 文字卡片保持兼容；三套新主题作用于带 `visual` 的图解场景。

## 分镜结构

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

## 验证结果

- Python 全量 23 项通过，包含布局/主题/动作组合、帧差异、旧分镜兼容、非法物体拒绝、Agent 模拟工具生成及局部修改。
- Java 93 项中 92 项通过、1 项数据库集成测试跳过；增加图解回调白名单和比例边界检查。
- 前端 36 项通过，生产构建成功。
- 实际渲染 30 秒 MP4，校验分辨率、帧率、时长和可解码性，并人工检查五幕预览。

这些是功能回归与演示结果，没有大规模用户质量评测。运行中的服务需重新构建/重启才会加载对应代码。
