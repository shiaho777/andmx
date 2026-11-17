#!/bin/sh
set -eu
cd "$(dirname "$0")"
if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "AndMX design tokens: brand #0EA5E9; chatBody 15/22sp; cardTitle 13/18sp; gutter 16dp; motion 120/160/200/320ms."
  exit 0
fi
"$PY" - <<'PYEOF'
import json

with open("tokens.json", encoding="utf-8") as f:
    tokens = json.load(f)
colors = tokens["colors"]
typo = tokens["type"]
spacing = tokens["spacing"]
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
    "间距(dp): gutter %s, bubbleRadius %s, sheetRadius %s"
    % (spacing["gutterDp"], spacing["bubbleRadiusDp"], spacing["sheetRadiusDp"]),
    "动效(ms): fast %s / base %s / enter %s / ink %s"
    % (motion["fastMs"], motion["baseMs"], motion["enterMs"], motion["inkMs"]),
    "生成片段用 design_palette / design_tokens 工具（args 传 css 或 compose）。",
]
print(json.dumps({"decision": "continue", "prompt_fragment": "\n".join(lines)}, ensure_ascii=False))
PYEOF