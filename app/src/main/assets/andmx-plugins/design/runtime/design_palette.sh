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
  echo "design_palette: python not available in guest" >&2
  exit 1
fi
"$PY" - "$FORMAT" <<'PYEOF'
import json
import sys

fmt = sys.argv[1] if len(sys.argv) > 1 else "css"
with open("tokens.json", encoding="utf-8") as f:
    tokens = json.load(f)
colors = tokens["colors"]

def walk(node, prefix=""):
    for key, value in node.items():
        name = prefix + key
        if isinstance(value, dict):
            yield from walk(value, name + "-")
        else:
            yield name, value

if fmt == "compose":
    print("import androidx.compose.ui.graphics.Color")
    print()
    print("object AndMXDesignPalette {")
    for name, value in walk(colors):
        const = "AndMX" + "".join(part.capitalize() for part in name.split("-"))
        print('    val %s = Color(0xFF%s)' % (const, value.lstrip("#").upper()))
    print("}")
else:
    print(":root {")
    for name, value in walk(colors):
        print("  --andmx-%s: %s;" % (name, value))
    print("}")
PYEOF