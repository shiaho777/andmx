---
name: andmx-html-video
description: 用户用文本描述影片时，在当前工作区用 HTML/CSS/JS + 程序音频（或用户指定音频）自迭代交付完整可播影片。短视频/宣传片/讲解动画/HTML 影片时使用。
---

# HTML 影片

当用户要做视频、短片、宣传片、讲解动画、HTML 影片——使用本技能与 `html_video_*` 工具。

## 硬原则

1. **当前工作区**：文件只写在当前会话 workspace。无全局默认目录。用户指哪做哪。
2. **一次交付完整成片**：不是方案卡，不是静态海报。要有运动、衔接、节奏、可播入口。
3. **少问多做**：能默认就不问。只在不问会做错时问一句（例如用户说「用这个音频」但没说 BGM/旁白/哪一段）。
4. **自动化 = 自迭代到满意再交付**，不是偷懒出破页面。
5. **工具只给数据**：禁止把工具 dump 原样甩给用户；用中文说明路径与如何预览。
6. **风格统一**（除非用户要求混乱）：色板/字体/动效语言一致。
7. **禁止**：整页静图、无时间轴、无转场、问卷轰炸。

## 工具

| 工具 | 用途 |
|------|------|
| `html_video_workspace_scan` | 扫工作区音视频素材与已有工程 |
| `html_video_init` | 在当前工作区建工程 |
| `html_video_write` | 写 html/css/js/json 等 |
| `html_video_build` | 校验并刷新入口；检查 staticRisk |
| `html_video_attach_audio` | 绑定音频（**必须** role + startMs） |
| `html_video_synth_audio` | 默认程序音效 / TTS |
| `html_video_fetch_audio` | 用户要求时 best-effort 拉取网络音频 |
| `html_video_preview` | 预览入口 |
| `html_video_deliver` | 交付清单 |

## 默认音频策略

1. 用户指定工作区文件 → `attach_audio`（用途不明先问）
2. 默认 → `synth_audio` 程序音效，并写入时间轴
3. 用户要求上网找音乐 → `fetch_audio`，失败回退程序音效

## 推荐流程（一次做完）

```
html_video_workspace_scan（若可能用本地素材）
→ html_video_init
→ 自定画幅/时长/色板/分镜/动效语言
→ html_video_write 场景与主播放器（强动效 + 转场 + 字幕）
→ html_video_synth_audio / attach_audio / fetch_audio
→ html_video_build（若 staticRisk 继续改）
→ html_video_preview
→ html_video_deliver
→ 用中文交付：入口路径、怎么打开、成片说明
```

## 视频感验收（交付前自检）

- 全程有设计过的运动，不是静图
- 镜间有衔接
- 有节奏与重点帧
- 有可播 `index.html`（或等价入口）
- 有声则与画面时间轴对齐

## 结构

单 HTML 或 多 scenes + 主时间轴均可；效果优先。可放在 `.andmx-html-video/<slug>/`，用户要求在根目录则 `inWorkspaceRoot`。
