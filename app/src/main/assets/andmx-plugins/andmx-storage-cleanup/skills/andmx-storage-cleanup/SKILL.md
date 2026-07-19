---
name: andmx-storage-cleanup
description: 全盘扫描 Android 本机存储，定位大文件、垃圾文件（含 Download APK）、重复文件；由你用文本 UI 解释用途；用户确认后自主清理。空间不够/清理缓存/删安装包时使用。
---

# 清理存储空间

当用户提到：空间不够、清理存储、清缓存、删 APK、找大文件、重复文件、手机满了——**必须使用本技能与 `storage_*` 工具**，不要只用 shell 盲删。

## 核心原则：工具只给数据，你来画 UI

- `storage_*` 工具返回的是**结构化数据**（字节数、路径、tags、ratio 等），**不是**给用户看的最终界面。
- **禁止**把工具原始 dump（`type:` / `bytes:` / `uiHint:` / 字段键名）原样甩给用户。
- **必须**根据数据用中文动态组织回复：容量条、分类占用、文件清单、对比卡、选项问题都由你现场用文本画出来。
- 条形图、表格、emoji、标题样式由你按当前数据与对话语境决定，不要套死模板；不同场景可以不同画法。
- 文件用途、删了会怎样、是否建议删：根据 `kind` / `tags` / 路径 / 目录语义**动态判断与解释**，不要背诵固定话术。

## 工具

| 工具 | 返回什么（数据） |
|------|------------------|
| `storage_overview` | 总量/已用/可用、权限、顶层目录占用 |
| `storage_scan` | planId + 垃圾/大文件/重复 + 分类占用 |
| `storage_find_large` | 大文件列表 |
| `storage_find_junk` | 垃圾候选（含 Download APK） |
| `storage_find_duplicates` | 重复文件组 |
| `storage_app_usage` | App 外部目录占用排行 |
| `storage_preview_delete` | confirmToken + 将删列表 + 预计释放 |
| `storage_clean` | 删除结果 + 前后快照数字 |
| `storage_compare` | 前后对比数字 |
| `storage_open_settings` | 打开授权设置页 |

## 强制工程流程

```
storage_overview
    → storage_scan（主路径；必要时 find_large / find_junk / find_duplicates 补刀）
    → 你用文本 UI 解释数据，并主动提问
    → 用户明确确认后
    → storage_preview_delete(planId, categories/paths)
    → 你再次用文本 UI 简述将删内容与预计释放量
    → storage_clean(confirmToken, userConfirmed=true)
    → 你用文本 UI 展示前后对比（数字来自工具）
    → 可选 storage_compare / storage_overview / storage_app_usage
```

### 硬性规则

1. **可以自主清理**，但清理前必须在对话里说明准备删什么，并得到用户明确确认。
2. **禁止**在未确认时调用 `storage_clean`。
3. `storage_clean` 必须带上 `storage_preview_delete` 返回的 `confirmToken`，且 `userConfirmed=true`。
4. Download 下的 **APK 默认是垃圾候选**，确认后可删。
5. `sensitive: true` 的路径默认不进删除列表；用户点名要删时 preview 设 `includeSensitive=true` 并再次确认。
6. 无「所有文件访问」时先 `storage_open_settings`，说明扫不全。
7. 工具输出里的 `uiHint` 只是给你的提示，不要转述给用户。

## 你对用户的呈现方式（动态文本 UI）

扫描或预览后，用中文把数据变成可读界面，例如可以包含（按需选择，不要机械照抄）：

- 容量总览：总量 / 已用 / 可用 / 占用比例，可用文本进度条
- 分类占用：按 `categories` 的 name + human + ratio 画排行
- Top 文件：路径、大小、你对用途的解释、是否建议删
- 行动选项：只清垃圾 / 清指定大文件 / 按用户点名路径 / 暂不清理

对每个重点文件动态说明：

- **是什么**（路径 + 类型 tags）
- **有什么用**（根据 tags/路径推理，不要写死模板）
- **删了会怎样**

然后主动问用户要不要删、删哪些。

## 推荐默认清理包（用户只说「帮我清理」时）

先建议并等待确认：

1. 扫描到的 **APK**
2. **临时文件**（tmp/part/crdownload）
3. **缓存与缩略图**
4. **大日志**

不要默认删相册原图、视频库、文档。

## 失败与复测

- 删除失败：汇总失败路径，解释权限/占用可能
- 释放量低于预期：用 compare/clean 返回的 before/after 数字解释；可再 `storage_app_usage` / `storage_find_large`
- 可用净增约 0：可能删的是应用私有区或系统统计未刷新，可 `storage_compare(recaptureAfter=true)` 或再 overview
