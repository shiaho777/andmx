# AndMX DESIGN.md — 设计体系

AndMX 界面与视觉产出的设计体系文档。唯一数值事实来源是 `runtime/tokens.json`，与 `com.andmx.ui2.theme` 的 `Type.kt`（AndMXType）、`Motion.kt`、`Theme.kt` 色板、`Chrome.kt` 保持一致。本文说明原则与用法；精确数值以 tokens.json 与工具输出为准。

## 1. 设计语言

- 中性、克制、内容优先：大面积用中性底色，强调色只出现在动作与状态上。
- 一套界面同一时刻只有一个强调色（品牌蓝）；语义色只表达结果，不做装饰。
- 层级靠字号 + 字重 + 间距表达，不靠颜色堆叠；一屏最多 3 个字号层级。

## 2. 配色

| Token | 值 | 用途 |
|-------|----|------|
| brand | `#0EA5E9` | 强调、链接、主操作 |
| brandSoft | `#E0F2FE` | 品牌浅底、选中态背景 |
| success | `#46BF72` | 成功状态 |
| warning | `#FF8A30` | 警告状态 |
| destructive | `#FF5C5C` | 危险/删除 |

浅色模式：背景 `#F8F8F8`、侧栏 `#F0F0F0`、winAlt `#ECECEE`、表面 `#FFFFFF`、前景 `#262626`、error `#E03131`。
深色模式：背景 `#161616`、头部 `#202020`、卡片 `#2B2B2B`、次级 `#363636`、前景 `#D4D4D4`、error `#FF5C5C`。

规则：
- 正文对比度优先；弱化文本用前景色加透明度（约 60%），不引入新灰阶。
- 边框用低透明度前景（浅色 `rgba(13,13,13,0.10)`、深色 `rgba(255,255,255,0.10)` 量级），不用实灰线。
- 产出 CSS 用 `var(--andmx-*)`（`design_palette` 生成），Compose 用主题色常量；不写裸十六进制新色。

## 3. 字阶（sp，对齐 AndMXType）

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

Material 标题阶（AndMXTypography）：titleLarge 20/26 semibold、titleMedium 16/22 semibold、titleSmall 14/20 medium；页面级大标题可用 20/16sp 两档。

规则：
- 行高随字号给定，不另行压缩；中文正文不小于 13sp。
- 字重只用 normal/medium/semibold 三档；bold 仅特殊强调。
- HTML 产出用 `design_tokens` 的 `.andmx-*` 字阶类或 CSS 变量。

## 4. 间距与圆角（dp，4dp 网格）

| 间距 Token | 值 | 用途 |
|-----------|----|------|
| xs | 4 | 图标与文字、组内最小间隙 |
| sm | 8 | 组内元素间距 |
| md | 12 | 卡片内分区间距 |
| lg | 16 | 页面 gutter（= Chrome.gutter）、卡片内边距 |
| xl | 24 | 组间/章节间距 |

| 圆角 Token | 值 | 依据 |
|-----------|----|------|
| xs | 2 | 行内小标记（MarkdownView 等） |
| sm | 8 | 代码块、输入附件、列表项 |
| md | 12 | 卡片、弹层容器（ThemeCard/SelectionToolbar） |
| bubble | 20 | 聊天气泡（Chrome.bubble） |
| sheet | 20 | 底部弹层顶部圆角（Chrome.sheet） |

规则：同级元素间距一致；组间距大于组内距；不出现 5/7/10/18 这类网格外值（既有历史代码除外，新产出一律取 token）。

## 5. 阴影（dp elevation）

| Token | 值 | 依据 |
|-------|----|------|
| rest | 1 | 静置卡片（ModelPage 卡片默认） |
| raised | 6 | 悬浮工具栏（SelectionToolbar） |
| drag | 8 | 拖拽中的卡片（ModelPage 拖拽态） |

规则：阴影表达高度层级而非装饰；静置内容尽量用边框/底色分层，浮层才用 raised 以上；HTML 产出用 `--andmx-shadow-*` 映射为轻量 box-shadow。

## 6. 动效（对齐 Motion）

- 时长四档：fast 120ms（微反馈）、base 160ms（常规过渡）、enter 200ms（进场）、ink 320ms（强调/涟漪）。
- 缓动统一 `cubic-bezier(0.22, 0.61, 0.36, 1)`；弹簧 damping 0.82、stiffness 380（尺寸弹簧 damping 0.9）。
- 无限循环动画：光标脉冲 480ms、shimmer 1300ms、think 扫光 1400ms（LinearEasing）。
- 系统动画缩放为 0 时全部动效退化为瞬时（Motion.Reduced）；CSS 产出必须带 `@media (prefers-reduced-motion: reduce)` 降级。
- 动画只服务于状态变化（进/出场、展开、反馈），不做纯装饰性持续动画。

## 7. 工具与检查

| 工具 | 用途 |
|------|------|
| `design_palette` | 色板 → CSS 变量或 Compose Color 常量（args: css/compose） |
| `design_tokens` | 字阶/间距/圆角/阴影/动效 → CSS 变量+字阶类或 Compose 常量（args: css/compose） |
| `design_lint` | 检查产出是否用 token：字号/行高/间距/圆角/颜色/时长。args 传 `{"code": "..."}` 或 `{"file": "/guest/路径"}`，输出 JSON 违规清单与最近 token 建议 |

SessionStart 的 design-rules 钩子会注入 token 与设计规则摘要；精确数值读 `runtime/tokens.json` 或调工具，不要背摘要近似值。

## 8. 工作流程

1. 明确产出目标（Compose 界面 / HTML 页面 / 图表配色）。
2. 调 `design_palette` 与 `design_tokens` 拿片段，直接复用变量名。
3. 按本文原则组合；产出后跑 `design_lint`，修掉全部违规或在回复中说明偏离理由。