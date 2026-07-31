# Disney 12 原则（HTML 影片程序动效用）

不要 12 条全塞进每一镜。只选能解决「僵、飘、糊、没重心、没性格」的最小集合。

## 1 Squash & Stretch
力/速度/撞击的形变证据；保体积感；锚接触点。

## 2 Anticipation
主动作前有可读预备（常反向/正交）；比例匹配力度。

## 3 Staging
同一时刻一个主导信息；剪影/对比/构图/时机服务主意。

## 4 Pose to Pose + Straight Ahead
先定事件 pose 与时间相，再在其间做程序细节。

## 5 Follow Through / Overlap
主质量改向后，松软部件滞后、分相位 settle。

## 6 Slow In / Slow Out
用间距塑造加减速；冲击可硬切，其余避免同款 ease 刷全场。

## 7 Arcs
有机运动走弧；直线只在机械/故意时用。

## 8 Secondary Action
从主动作或事件触发的次动作，不抢戏。

## 9 Timing
按时长与相位比表达质量/情绪；快慢对比造节奏。

## 10 Exaggeration
放大最能说清意图的变量（pose/间距/形变/停顿），别堆砌。

## 11 Solid Drawing
体积、支点、层级、安全边距在竖屏/横屏都成立。

## 12 Appeal
清晰态度 + 形语言 + 视觉层级；干净选择优于复杂堆叠。

## HTML/CSS/JS 落地口诀
- 时间轴驱动：`t = now - start`，相函数 `phase(t)`，状态 `getStateAtTime(t)`
- 渲染是状态投影：DOM/SVG/Canvas 只读状态，不各自偷偷计时
- 镜间转场与片内 ease 共用 design token
- `prefers-reduced-motion`：可降级，但不把成片做成静图
