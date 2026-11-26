---
name: design-guide
description: AndMX 设计资源指引：配色、字阶、间距与动效原则，对齐 ui2/theme 的 AndMXType/Motion token；用 design_palette/design_tokens 工具生成 CSS 或 Compose 片段。做界面、网页、海报、配色或排版设计时使用。
---

# AndMX 设计指引

为 AndMX 相关的界面、HTML 页面、图表与视觉产出提供统一设计语言。**唯一事实来源是 `runtime/tokens.json`**，其数值与 `com.andmx.ui2.theme` 的 `AndMXType`（Type.kt）、`Motion`（Motion.kt）、`Theme.kt` 色板、`Chrome` 间距保持一致。完整设计体系见插件根目录 `DESIGN.md`。不要凭空发明字号、颜色或时长。

## 工具

| 工具 | 用途 |
|------|------|
| `design_palette` | 读 token 色板，输出 CSS 变量或 Compose Color 常量。args 传 `css`（默认）或 `compose` |
| `design_tokens` | 读字阶/间距/圆角/阴影/动效 token，输出 CSS 变量+字阶类或 Compose TextStyle/dp 常量。args 传 `css`（默认）或 `compose` |
| `design_lint` | 检查产出的 CSS/Compose 片段是否用 token（字号/行高/间距/圆角/颜色/时长）。args 传 `{"code": "片段"}` 或 `{"file": "/guest/绝对路径"}`，输出 JSON 违规清单与最近 token 建议 |

会话开始时 design-rules SessionStart 钩子会注入 token 与设计规则摘要；需要精确数值时读 `runtime/tokens.json` 或调用上述工具，不要背摘要近似值。

## 配色原则

- 浅色主题的主操作前景是 `#0D0D0D`，链接使用 `#0066DD`。工作区底色使用中性色。
- 语义色固定：成功 `#46BF72`、警告 `#FF8A30`、危险 `#FF5C5C`；不要用语义色做装饰。
- 浅色底 `#F8F8F8`、卡片/表面 `#FFFFFF`、侧栏 `#F0F0F0`；深色底 `#161616`、头部 `#202020`、卡片 `#2B2B2B`、次级 `#363636`。
- 前景浅色 `#0D0D0D`、深色 `#F2F2F2`；正文对比度优先，弱化文本用透明度而非新灰阶。
- 中性色用于主要操作，蓝色用于链接，语义色表达活动状态。

## 字阶原则（sp，与 AndMXType 一致）

| Token | 字号/行高 | 字重 | 用途 |
|-------|-----------|------|------|
| chatBody | 15/22 | normal | 聊天正文、主阅读文本 |
| chatBodyMuted | 13.5/20 | normal | 次级说明 |
| cardTitle | 13/18 | medium | 卡片标题 |
| cardMeta | 12/16 | normal | 元信息、时间戳 |
| micro | 11/14 | normal | 角标、最小文本 |
| code | 13/19 | normal | 代码块（等宽字体） |
| stat | 17/22 | semibold | 数据指标 |
| statLarge | 18/24 | semibold | 大指标 |
| input | 14/20 | normal | 输入框 |

- 层级靠字号+字重表达，不靠颜色堆叠；一屏最多 3 个字号层级。
- 行高已随字号给定，不要另行压缩；中文正文不小于 13sp。

## 间距与圆角原则

- 页面左右留白（gutter）16dp；面板与聊天气泡使用 16dp 圆角，底部弹层顶部使用 20dp。
- 间距用 4dp 网格（xs 4 / sm 8 / md 12 / lg 16 / xl 24）；同级元素间距一致，组间距大于组内距。
- 圆角四档：xs 2dp（行内标记）、sm 8dp（代码块/列表项）、md 12dp（控件），配合面板、气泡与弹层的对称圆角。
- 阴影三档：rest 1dp（静置卡片）、raised 6dp（悬浮工具栏）、drag 8dp（拖拽态）；阴影表达层级不做装饰。

## 动效原则（与 Motion 一致）

- 时长四档：fast 120ms（微反馈）、base 160ms（常规过渡）、enter 200ms（进场）、ink 320ms（强调/涟漪）。
- 缓动 cubic-bezier(0.4, 0, 0.2, 1)；弹簧 damping 0.82、stiffness 380。
- 系统动画缩放为 0 时全部动效退化为瞬时（Motion.Reduced），产出 CSS 时尊重 `prefers-reduced-motion`。

## 工作流程

1. 明确产出目标（Compose 界面 / HTML 页面 / 图表配色）。
2. 调 `design_palette` 与 `design_tokens`（选 css 或 compose）拿片段，直接复用变量名。
3. 按上述原则组合；产出后调 `design_lint` 检查 token 一致性，修掉违规。
4. 偏离 token 体系需在回复中说明理由。