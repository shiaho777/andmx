# 程序动效实现模式（HTML 影片）

## 纯状态求值

```js
function getStateAtTime(t, inputs, params) {
  const phase = getPhase(t, params.timeline);
  const primary = getPrimary({ t, phase, inputs, params });
  const velocity = sampleVelocity(t, inputs, params);
  const pose = getPose({ t, phase, primary, velocity, params });
  const secondary = getSecondary({ t, phase, primary, velocity, params });
  return { phase, primary, pose, secondary };
}
```

每帧 / rAF：`const s = getStateAtTime(t); applyToDom(s)`。

## 语义时间轴

```js
const timeline = [
  { name: "anticipation", duration: 0.16 },
  { name: "commit", duration: 0.08 },
  { name: "travel", duration: 0.42 },
  { name: "settle", duration: 0.24 },
];
```

相位边界要对齐字幕 cue、音效 stinger、镜头切换。

## 边界连续性
相位切换时速度/透明度不要无故跳变（冲击除外）。

## 运动派生信号
模糊、残影、粒子、字幕弹入应绑定 `velocity` / `impact` / `phase enter`，不要独立无限 loop 抢戏。

## Follow-through 无物理引擎
`lag(t) = primary(t - delay)` 或指数平滑即可。

## 渲染策略
- 文字/UI 信息：DOM + CSS transform/opacity
- 形变/路径：SVG
- 粒子/光：Canvas 或 CSS 有限元素
- 忌每帧重写整页 innerHTML

## 调参顺序
1.  timing / phase 比例  
2.  主位移与 staging  
3.  ease / arc  
4.  secondary / FX  
5.  音效与字幕点
