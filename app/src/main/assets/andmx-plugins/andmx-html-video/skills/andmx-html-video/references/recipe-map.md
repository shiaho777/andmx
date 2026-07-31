# HTML 影片动效配方映射

下列配方已内化为本插件的审美与实现指引。**不依赖**仓库里任何外部 skill 目录；用 `html_video_apply_recipe` 或 `html_video_write` 在当前工作区落地为纯 HTML/CSS/JS（无 CDN）。

| 用户意图 / 关键词 | 配方 id | 落地 |
|-------------------|---------|------|
| 打字机、提示词演示、CLI | `typewriter` | `recipes/typewriter-cli.html` |
| 聚光灯扫字、发光揭示 | `spotlight` | `recipes/spotlight-text-reveal.html` |
| 尺子进度、loading 叙事 | `ruler` | `recipes/ruler-progress.html` |
| SVG 组装、零件拼合 | `assembly` | `recipes/svg-assembly.html` |
| Logo 显现、品牌 intro | `logo` | `recipes/logo-intro.html` |
| 动效僵/飘/没重量 | — | 读 `disney-principles.md` + `implementation-patterns.md` |
| 黑胶/3D ticker/地球/聊天风 | — | **气质降维**：CSS 3D / Canvas 近似；勿引入 Remotion/Three 工程依赖 |
| 循环角色 / 氛围层 | — | 周期路径 + overlap；幅度低于主信息 |

## 选用规则

1. 用户点名风格 → 上表配方优先，`html_video_apply_recipe`。
2. 未点名 → 自选最贴内容的 1～2 个配方，全片 design token 统一。
3. 多配方可分镜串联，禁止无主题堆特效。
4. 一切输出在当前工作区可播；禁止要求用户安装 Node/Remotion 才能看片。
5. `html_video_build` 的 `staticRisk` / `hardFail` 必须清零后再 deliver。
