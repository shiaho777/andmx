#!/bin/sh
set -eu
cd "$(dirname "$0")"
FORMAT=css
for arg in "$@"; do
  case "$arg" in
    *compose*) FORMAT=compose ;;
    *css*) FORMAT=css ;;
  esac
done
if [ -t 0 ]; then :; else
  INPUT=$(cat || true)
  case "$INPUT" in
    *compose*) FORMAT=compose ;;
  esac
fi
if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "design_tokens: python not available in guest" >&2
  exit 1
fi
"$PY" - "$FORMAT" <<'PYEOF'
import json
import sys

fmt = sys.argv[1] if len(sys.argv) > 1 else "css"
with open("tokens.json", encoding="utf-8") as f:
    tokens = json.load(f)

WEIGHTS = {"normal": 400, "medium": 500, "semibold": 600, "bold": 700}
COMPOSE_WEIGHTS = {
    "normal": "FontWeight.Normal",
    "medium": "FontWeight.Medium",
    "semibold": "FontWeight.SemiBold",
    "bold": "FontWeight.Bold",
}

def css_class(name):
    out = []
    for ch in name:
        if ch.isupper():
            out.append("-" + ch.lower())
        else:
            out.append(ch)
    return "".join(out)

if fmt == "compose":
    print("import androidx.compose.ui.text.TextStyle")
    print("import androidx.compose.ui.text.font.FontWeight")
    print("import androidx.compose.ui.unit.dp")
    print("import androidx.compose.ui.unit.sp")
    print()
    print("object AndMXDesignTokens {")
    for name, style in tokens["type"].items():
        print("    val %s = TextStyle(" % name)
        print("        fontSize = %s.sp," % style["fontSizeSp"])
        print("        lineHeight = %s.sp," % style["lineHeightSp"])
        print("        fontWeight = %s," % COMPOSE_WEIGHTS[style["weight"]])
        print("    )")
    for name, value in tokens["spacing"].items():
        print("    val %s = %s.dp" % (name, value))
    for name, value in tokens["motion"].items():
        if isinstance(value, (int, float)):
            print("    val %s = %s" % (name, value))
    print("}")
else:
    print(":root {")
    for name, style in tokens["type"].items():
        slug = css_class(name)
        print("  --andmx-font-%s: %spx;" % (slug, style["fontSizeSp"]))
        print("  --andmx-leading-%s: %spx;" % (slug, style["lineHeightSp"]))
        print("  --andmx-weight-%s: %s;" % (slug, WEIGHTS[style["weight"]]))
    for name, value in tokens["spacing"].items():
        print("  --andmx-%s: %spx;" % (css_class(name).replace("-dp", ""), value))
    for name, value in tokens["motion"].items():
        if isinstance(value, (int, float)):
            print("  --andmx-motion-%s: %s;" % (css_class(name).replace("-ms", "-ms"), value))
    ease = tokens["motion"]["easingCubicBezier"]
    print("  --andmx-ease: cubic-bezier(%s);" % ", ".join(str(v) for v in ease))
    print("}")
    print()
    for name, style in tokens["type"].items():
        slug = css_class(name)
        print(".andmx-%s {" % slug)
        print("  font-size: var(--andmx-font-%s);" % slug)
        print("  line-height: var(--andmx-leading-%s);" % slug)
        print("  font-weight: var(--andmx-weight-%s);" % slug)
        print("}")
PYEOF