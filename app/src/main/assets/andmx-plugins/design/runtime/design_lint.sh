#!/bin/sh
set -eu
cd "$(dirname "$0")"
ARGS=""
if [ "$#" -gt 0 ]; then ARGS="$*"; fi
INPUT=""
if [ ! -t 0 ]; then INPUT=$(cat || true); fi
if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "design_lint: python not available in guest" >&2
  exit 1
fi
ANDMX_LINT_INPUT="$INPUT" ANDMX_LINT_ARGS="$ARGS" "$PY" - <<'PYEOF'
import json
import os
import re
import sys

payload = os.environ.get("ANDMX_LINT_INPUT", "")
args = os.environ.get("ANDMX_LINT_ARGS", "")

with open("tokens.json", encoding="utf-8") as f:
    tokens = json.load(f)

allowed_sizes = sorted({float(s["fontSizeSp"]) for s in tokens["type"].values()} | {20.0, 16.0})
allowed_leading = sorted({float(s["lineHeightSp"]) for s in tokens["type"].values()} | {26.0})
allowed_space = sorted({float(v) for v in tokens["spacing"].values()} | {0.0})
allowed_radius = sorted({float(v) for v in tokens.get("radius", {}).values()} | {0.0})
allowed_ms = sorted({float(tokens["motion"][k]) for k in ("fastMs", "baseMs", "enterMs", "inkMs")} | {0.0})
palette = set()

def walk_colors(node):
    for value in node.values():
        if isinstance(value, dict):
            walk_colors(value)
        elif isinstance(value, str) and value.startswith("#"):
            palette.add(value.upper())

walk_colors(tokens["colors"])
neutrals = {"#FFF", "#FFFFFF", "#000", "#000000"}

source = payload
mode = "text"
target = ""
for cand in (payload, args):
    cand = cand.strip()
    if not cand:
        continue
    try:
        obj = json.loads(cand)
    except ValueError:
        continue
    if isinstance(obj, dict):
        if isinstance(obj.get("code"), str):
            source = obj["code"]
        elif isinstance(obj.get("file"), str):
            target = obj["file"]
            source = ""
        if obj.get("mode") in ("css", "compose"):
            mode = obj["mode"]
        break

if target:
    try:
        with open(target, encoding="utf-8", errors="replace") as f:
            source = f.read()
    except OSError as exc:
        print(json.dumps({"ok": False, "error": "cannot read file: %s" % exc}, ensure_ascii=False))
        sys.exit(2)

if mode == "text":
    if re.search(r"\b\d+(\.\d+)?\.(sp|dp)\b", source):
        mode = "compose"
    elif re.search(r"[a-z-]+\s*:\s*[^;]+;", source) or ":root" in source or "<style" in source:
        mode = "css"

if not source.strip():
    print(json.dumps({
        "ok": False,
        "error": "no input",
        "usage": "args 传 {\"code\": \"...css 或 compose 片段...\"} 或 {\"file\": \"/guest/绝对路径\"}，也可把片段直接写入 stdin",
    }, ensure_ascii=False))
    sys.exit(2)

issues = []

def add(line_no, rule, message, suggestion):
    issues.append({"line": line_no, "rule": rule, "message": message, "suggestion": suggestion})

def fmt(v):
    return str(int(v)) if float(v).is_integer() else str(v)

def near(value, allowed):
    return min(allowed, key=lambda a: abs(a - value))

def norm_hex(h):
    h = h.upper()
    if len(h) == 4:
        return "#" + "".join(c * 2 for c in h[1:])
    return h

NUM = r"(\d+(?:\.\d+)?)"

for i, line in enumerate(source.splitlines(), 1):
    stripped = line.strip()
    if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
        continue
    if "var(--andmx" in line:
        continue
    if mode == "css":
        for m in re.finditer(r"font-size\s*:\s*" + NUM + r"(px|sp|rem|em|pt)\b", line):
            v = float(m.group(1))
            if v not in allowed_sizes:
                add(i, "font-size", "font-size %s%s 不在字阶 token" % (m.group(1), m.group(2)),
                    "改用 %spx（对应 token 字阶）或 var(--andmx-font-*)" % fmt(near(v, allowed_sizes)))
        for m in re.finditer(r"line-height\s*:\s*" + NUM + r"(px|sp|rem|em)\b", line):
            v = float(m.group(1))
            if v not in allowed_leading:
                add(i, "line-height", "line-height %s%s 不在行高 token" % (m.group(1), m.group(2)),
                    "改用 %spx 或 var(--andmx-leading-*)" % fmt(near(v, allowed_leading)))
        for m in re.finditer(r"(?<![-\w])(margin|padding|gap|row-gap|column-gap)(-(?:top|right|bottom|left|inline|block))?\s*:\s*([^;]+)", line):
            for num in re.finditer(NUM + r"(px|dp)\b", m.group(3)):
                v = float(num.group(1))
                if v not in allowed_space:
                    add(i, "spacing", "%s 使用 %s%s，不在间距 token (4dp 网格)" % (m.group(1), num.group(1), num.group(2)),
                        "改用 %spx 或 var(--andmx-lg) 等间距变量" % fmt(near(v, allowed_space)))
        for m in re.finditer(r"border-radius\s*:\s*([^;]+)", line):
            for num in re.finditer(NUM + r"(px|dp)\b", m.group(1)):
                v = float(num.group(1))
                if v not in allowed_radius:
                    add(i, "radius", "border-radius %s%s 不在圆角 token" % (num.group(1), num.group(2)),
                        "改用 %spx 或 var(--andmx-radius-*)" % fmt(near(v, allowed_radius)))
        for m in re.finditer(r"(?:transition|animation)(?:-duration)?\s*:\s*([^;]+)", line):
            for num in re.finditer(NUM + r"ms\b", m.group(1)):
                v = float(num.group(1))
                if v not in allowed_ms:
                    add(i, "motion", "时长 %sms 不在动效 token (120/160/200/320)" % num.group(1),
                        "改用 %sms 或 var(--andmx-motion-*)" % fmt(near(v, allowed_ms)))
        for m in re.finditer(r"#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})\b", line):
            h = norm_hex("#" + m.group(1))
            if h not in palette and h not in neutrals:
                add(i, "color", "颜色 %s 不在 AndMX 色板" % h,
                    "改用 token 色（brand #0D0D0D 等）或 var(--andmx-*)；透明请用 rgba/token 色加透明度")
    elif mode == "compose":
        for m in re.finditer(r"fontSize\s*=\s*" + NUM + r"\.sp", line):
            v = float(m.group(1))
            if v not in allowed_sizes:
                add(i, "font-size", "fontSize %s.sp 不在字阶 token" % m.group(1),
                    "改用 AndMXType.* 或 %s.sp" % fmt(near(v, allowed_sizes)))
        for m in re.finditer(r"lineHeight\s*=\s*" + NUM + r"\.sp", line):
            v = float(m.group(1))
            if v not in allowed_leading:
                add(i, "line-height", "lineHeight %s.sp 不在行高 token" % m.group(1),
                    "改用 AndMXType.* 或 %s.sp" % fmt(near(v, allowed_leading)))
        for m in re.finditer(r"(?:padding|spacer|size|width|height|offset)\s*\(([^)]*)\)", line, re.IGNORECASE):
            for num in re.finditer(NUM + r"\.dp", m.group(1)):
                v = float(num.group(1))
                if v not in allowed_space:
                    add(i, "spacing", "%s.dp 不在间距 token (4dp 网格)" % num.group(1),
                        "改用 %s.dp 或 Chrome.gutter 等 token" % fmt(near(v, allowed_space)))
        for m in re.finditer(r"RoundedCornerShape\(([^)]*)\)", line):
            for num in re.finditer(NUM + r"\.dp", m.group(1)):
                v = float(num.group(1))
                if v not in allowed_radius:
                    add(i, "radius", "圆角 %s.dp 不在圆角 token" % num.group(1),
                        "改用 %s.dp 或 Chrome.bubble/sheet" % fmt(near(v, allowed_radius)))
        for m in re.finditer(r"(?:durationMillis\s*=\s*|tween\(\s*)" + NUM, line):
            v = float(m.group(1))
            if v not in allowed_ms:
                add(i, "motion", "时长 %sms 不在动效 token" % m.group(1),
                    "改用 Motion.fastMs/baseMs/enterMs/inkMs 或 %s" % fmt(near(v, allowed_ms)))
        for m in re.finditer(r"Color\(0x([0-9A-Fa-f]{8})\)", line):
            h = "#" + m.group(1)[2:].upper()
            alpha = m.group(1)[:2].upper()
            if h not in palette and h not in neutrals and alpha == "FF":
                add(i, "color", "颜色 %s 不在 AndMX 色板" % h,
                    "改用 WorkspaceInk 等主题色常量；弱化用 copy(alpha=...)")

report = {
    "ok": not issues,
    "mode": mode,
    "issueCount": len(issues),
    "issues": issues[:50],
    "allowed": {
        "fontSizeSp": allowed_sizes,
        "spacingDp": allowed_space,
        "radiusDp": allowed_radius,
        "motionMs": allowed_ms,
    },
}
print(json.dumps(report, ensure_ascii=False))
PYEOF