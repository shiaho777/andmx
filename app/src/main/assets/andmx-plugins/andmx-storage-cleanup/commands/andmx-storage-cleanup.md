---
name: storage-cleanup
description: 扫描并清理本机存储空间
---

使用技能 `andmx-storage-cleanup`：先 `storage_overview` + `storage_scan` 拿到结构化数据，**由你用中文文本 UI** 说明大文件/垃圾/重复文件是什么、有什么用，询问清理范围；用户确认后 `storage_preview_delete` → `storage_clean(userConfirmed=true)`。禁止把工具原始 dump 原样甩给用户。
