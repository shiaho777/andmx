---
name: andmx-dev-forge
description: 配置开发环境与工程脚手架：扫描已就绪/可安装能力，按项目类型推荐并在当前工作区逐步落地。新项目、配环境、工程拆解时使用。
---

# 工程 / 开发环境（Dev Forge）

用于：刚装 App 要配环境、新开项目、查看已搭建能力、一键脚手架。

## 与 andmx-android-dev 分工

| 本插件 forge_* | andmx-android-dev android_* |
|----------------|-------------------------------|
| 环境扫描、配方、脚手架 | 编/装/跑/截图/UI 自动化 |
| 问类型 → 推荐 → 写工程骨架 | 已有 Android 工程后的构建运行 |

Android 构建运行**委托** `android_*`，不要重复造 adb 轮子。

## 硬原则

1. **当前工作区**，无全局默认项目目录。
2. **少问**：只问项目类型与关键约束；能扫到的不重问。
3. **诚实能力层级**：装不了的给 guide，不假装已安装。
4. **工具只给数据**，模型用中文呈现矩阵/计划/结果。
5. 写操作前简述将改什么；脚手架可直接做（用户已表达要开工时）。

## 工具

| 工具 | 用途 |
|------|------|
| `forge_env_scan` | 能力矩阵 |
| `forge_list_profiles` | 内置配方 |
| `forge_recipe_recommend` | 按类型推荐 |
| `forge_recipe_plan` | 分步计划（不执行） |
| `forge_check_step` | 单步检测 |
| `forge_scaffold_project` | 脚手架写入当前工作区 |
| `forge_write_tooling` | gitignore/editorconfig 等 |
| `forge_project_status` | 验收 |

## 流程

```
forge_env_scan
→（类型不明才问）项目类型
→ forge_recipe_recommend
→ forge_recipe_plan
→ forge_scaffold_project / forge_write_tooling
→ forge_project_status
→ 若 Android：引导 andmx-android-dev
```

## 配方

web-static / web-node / android-app / python-script / docs-markdown / html-video-ready / agent-skill-pack
