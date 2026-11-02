package com.andmx.agent.permission

enum class CollaborationMode(val wire: String) {
    PLAN("plan"),
    BUILD("build"),
    EDIT("edit"),
    YOLO("yolo"),
    GUARDED("guarded"),
    AUTO("auto");

    companion object {
        fun fromWire(raw: String?): CollaborationMode {
            val s = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == s } ?: when (s) {
                "confirm", "ask", "autoedit" -> BUILD
                "auto_edit", "edit" -> EDIT
                "full", "full_access", "yolo" -> YOLO
                "readonly", "read_only", "read" -> PLAN
                else -> BUILD
            }
        }

        /**
         * Claude Code 风格子代理 permissionMode 映射；default/未知返回 null，
         * 表示沿用父会话的审批策略。
         */
        fun fromSubagentPermissionMode(raw: String?): CollaborationMode? {
            return when (raw?.trim()) {
                "acceptEdits" -> EDIT
                "auto" -> AUTO
                "bypassPermissions" -> YOLO
                "dontAsk" -> BUILD
                "plan" -> PLAN
                else -> null
            }
        }
    }
}
