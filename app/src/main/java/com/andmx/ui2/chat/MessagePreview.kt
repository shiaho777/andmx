package com.andmx.ui2.chat

/**
 * 长回复渐进预览（ZCode chat.message.bodyPreview 对齐）。
 *
 * 超过 [PREVIEW_THRESHOLD] 字符的非流式回复只渲染前 [PREVIEW_CHARS] 字符，
 * 附「查看完整消息」展开。本地截断，无需后端；预览截断点尽量落在段落或
 * 代码块边界，避免把 Markdown 结构切一半。
 */
object MessagePreview {

    const val PREVIEW_THRESHOLD = 20_000
    const val PREVIEW_CHARS = 8_000

    data class Plan(val preview: String, val fullBytes: Int, val truncated: Boolean)

    fun plan(text: String, threshold: Int = PREVIEW_THRESHOLD, previewChars: Int = PREVIEW_CHARS): Plan {
        if (text.length <= threshold) return Plan(text, text.length, false)
        var cut = previewChars.coerceAtMost(text.length)
        // 回退到最近的段落边界（双换行），找不到再找单换行，避免切割行内语法。
        val hardFloor = previewChars - 2_000
        for (i in cut downTo hardFloor.coerceAtLeast(1)) {
            if (text.startsWith("\n\n", i)) { cut = i; break }
        }
        if (cut == previewChars.coerceAtMost(text.length)) {
            for (i in cut downTo hardFloor.coerceAtLeast(1)) {
                if (text[i - 1] == '\n') { cut = i; break }
            }
        }
        // 别把截断点落进未闭合的代码围栏里：如果预览内 ``` 数量为奇数，退到上一个围栏前。
        if (text.take(cut).count { it == '`' } % 4 == 2) {
            val fence = text.lastIndexOf("\n```", cut)
            if (fence > 0) cut = fence
        }
        return Plan(text.take(cut).trimEnd() + "\n\n…", text.length, true)
    }
}
