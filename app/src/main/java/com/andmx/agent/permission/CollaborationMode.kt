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
    }
}
