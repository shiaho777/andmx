#!/bin/sh
set -eu
cd "$(dirname "$0")"
if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "AndMX design tokens: brand #0EA5E9; chatBody 15/22sp; cardTitle 13/18sp; gutter 16dp; radius 2/8/12/20dp; shadow 1/6/8dp; motion 120/160/200/320ms."
  echo "设计规则: 字号/间距/颜色/圆角/时长一律取 token；间距用 4dp 网格；品牌蓝仅用于强调；产出后用 design_lint 检查。"
  exit 0
fi
"$PY" - <<'PYEOF'
import json

with open("tokens.json", encoding="utf-8") as f:
    tokens = json.load(f)
colors = tokens["colors"]
typo = tokens["type"]
spacing = tokens["spacing"]
radius = tokens.get("radius", {})
shadow = tokens.get("shadow", {})
motion = tokens["motion"]
lines = [
    "## AndMX Design Tokens (design plugin)",
    "配色: brand %s / success %s / warning %s / destructive %s; light bg %s, dark bg %s"
    % (colors["brand"], colors["success"], colors["warning"], colors["destructive"],
       colors["light"]["background"], colors["dark"]["background"]),
    "字阶(sp): " + "; ".join(
        "%s %s/%s" % (name, style["fontSizeSp"], style["lineHeightSp"])
        for name, style in typo.items()
    ),
    "间距(dp): " + ", ".join("%s %s" % (name, value) for name, value in spacing.items()),
    "圆角(dp): " + ", ".join("%s %s" % (name, value) for name, value in radius.items()),
    "阴影(dp): " + ", ".join("%s %s" % (name, value) for name, value in shadow.items()),
    "动效(ms): fast %s / base %s / enter %s / ink %s"
    % (motion["fastMs"], motion["baseMs"], motion["enterMs"], motion["inkMs"]),
    "设计规则: 字号/间距/颜色/圆角/时长一律取 token，不发明新值；品牌蓝仅用于强调与主操作；"
    "语义色不做装饰；间距用 4dp 网格；一屏最多 3 个字号层级；动画尊重系统缩放与 prefers-reduced-motion。",
    "生成片段用 design_palette / design_tokens 工具（args 传 css 或 compose）；"
    "产出 HTML/CSS/Compose 后用 design_lint 工具检查 token 一致性。",
]
print(json.dumps({"decision": "continue", "prompt_fragment": "\n".join(lines)}, ensure_ascii=False))
PYEOF