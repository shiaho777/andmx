---
name: andmx-html-video
description: 用户用文本描述影片时，在当前工作区用 HTML/CSS/JS + 程序音频（或用户指定音频）自迭代交付完整可播影片。内置 getStateAtTime 播放器、动效配方（打字机、聚光灯、进度尺、SVG 组装、Logo 运动）与迪士尼程序动效原则。短视频/宣传片/讲解动画/HTML 影片/品牌 intro 时使用。
---

# HTML 影片

当用户要做视频、短片、宣传片、讲解动画、HTML 影片、品牌 intro、Logo 动效、打字机/聚光灯/进度类演示——使用本技能与 `html_video_*` 工具。

成片一律落在 **当前会话工作区**，用纯 HTML/CSS/JS（+ 程序/用户音频）交付。动效配方与原则已写在本技能的 `references/`、`recipes/` 中，**自包含**，不要去读或依赖仓库其它 skill 文件夹。

## 硬原则

1. **当前工作区**：无全局默认目录。用户指哪做哪。
2. **一次交付完整成片**：可播入口 + 动感 + 衔接 + 节奏；不是方案卡/静海报。
3. **少问多做**：能默认就不问。用户音频用途不明时必问。
4. **自动化 = 自迭代到满意**：对照视频感验收与动效原则自检，改文件再 build。
5. **工具只给数据**：禁止 dump 原样甩用户。
6. **风格统一**（除非用户要求混乱）。
7. **禁止**：静图充数、无时间轴、无转场、问卷轰炸、引入桌面视频框架作为运行依赖、CDN 动画库依赖。

## 动效体系（本插件内）

| 文件 | 何时读 |
|------|--------|
| `references/recipe-map.md` | 选题/分镜：意图 → 配方 |
| `references/disney-principles.md` | 动效发僵/飘/没性格时 |
| `references/implementation-patterns.md` | 写 JS 时间轴与状态机时 |
| `recipes/typewriter-cli.html` | 打字机/CLI |
| `recipes/spotlight-text-reveal.html` | 聚光灯扫字 |
| `recipes/ruler-progress.html` | 进度尺叙事 |
| `recipes/svg-assembly.html` | 零件组装 |
| `recipes/logo-intro.html` | Logo intro |

### 快速配方

- **Typewriter / CLI**：`html_video_apply_recipe recipe=typewriter`
- **Spotlight reveal**：`recipe=spotlight`
- **Ruler / Progress**：`recipe=ruler`
- **SVG assembly**：`recipe=assembly`
- **Logo intro**：`recipe=logo`
- **Ambient loop layer**：低频背景，不抢主信息

实现优先 **语义时间轴 + `getStateAtTime(t)`**。

## 工具

| 工具 | 用途 |
|------|------|
| `html_video_workspace_scan` | 扫工作区音视频与已有工程 |
| `html_video_init` | 建工程（多镜 storyboard/timeline/player 壳） |
| `html_video_list_recipes` | 列内置配方 |
| `html_video_apply_recipe` | 配方写入 scene 或 entry（离线纯 HTML） |
| `html_video_write` | 写 html/css/js/json；深度定制 |
| `html_video_build` | 运动 QA（staticRisk/hardFail/weakMotion） |
| `html_video_attach_audio` | 绑音频（**必须** role + startMs） |
| `html_video_synth_audio` | 默认程序音效 / TTS |
| `html_video_fetch_audio` | 用户要求时拉取网络音频 |
| `html_video_preview` | 预览 |
| `html_video_deliver` | 交付清单 |

## 默认音频策略

1. 用户指定文件 → `attach_audio`（用途不明先问）
2. 默认 → `synth_audio`，贴相位
3. 用户要求找音乐 → `fetch_audio`，失败回退程序音效

## 推荐流程（一次做完）

```
html_video_workspace_scan
→ html_video_init
→ html_video_list_recipes / recipe-map：选定 1～2 个主配方 + design token
→ html_video_apply_recipe（钩/中段/尾）
→ html_video_write 细化分镜与主时间轴
→ 音频层
→ html_video_build（staticRisk/hardFail → 再写运动）
→ 原则自检并迭代
→ html_video_preview → html_video_deliver
```

## 视频感验收

- 全程设计运动，非静图（build 的 motionChecks 须过）
- 镜间衔接 + 片内节奏
- 主信息 staging 清晰
- 可播入口；声画对齐

## 结构

单 HTML 或 scenes + 主时间轴均可。默认 `.andmx-html-video/<slug>/`；用户要求根目录则 `inWorkspaceRoot`。
init 会种子：`index.html`（多镜 getStateAtTime 播放器）、`js/timeline-engine.js`、`css/tokens.css`、三镜 scene stub、storyboard/timeline/audio plan。
