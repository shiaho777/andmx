package com.andmx.ui2.markdown

/**
 * 追加式流文本的增量块解析（dsh web incremental.ts 对齐）。
 *
 * 全量重解析是 O(n²)：每个 chunk 都要重解析整条已积累回复。块解析是行级的，
 * 追加文本只能重塑最后一个顶层块，更早的块已经定型——因此除尾部
 * [IncrementalMarkdown.UNSTABLE_TAIL_BLOCKS] 个块外全部冻结，每个 chunk 只
 * 重解析冻结点之后的源码尾片。未闭合的代码围栏不能冻结为块，但其已完成行走
 * 第二道边界：只对最后完成行 + 当前不完整行重跑语法。每个源区域在整个流周期
 * 内只被解析常数次。
 *
 * 块的渲染 key = 它在完整源码中的起始偏移，块跨入冻结区时 key 不变，
 * Compose 按 key reconcile 而不是重挂载。
 */
object IncrementalMarkdown {

    /** 尾部保持不稳定的块数；倒数第二块留作安全余量。 */
    const val UNSTABLE_TAIL_BLOCKS = 2

    data class PositionedBlock(
        val node: MdBlock,
        /** 块在完整源码中的起始偏移，跨 chunk 稳定。 */
        val key: Int,
    )

    data class Result(
        /** 不再变化的块；随流单调增长。 */
        val frozen: List<PositionedBlock>,
        /** 重解析的不稳定尾部。 */
        val tail: List<PositionedBlock>,
        /** 非追加输入会丢弃冻结前缀并递增；调用方据此丢弃按代缓存的产物。 */
        val generation: Int,
    )

    /** 未闭合围栏的增量状态。 */
    private class OpenFence(
        val marker: Char,
        val markerLength: Int,
        /** 重放语法时补回的合成开头（缩进+围栏行），保证 slice 仍解析为 code。 */
        val syntheticPrefix: String,
        val frozenSnapshot: List<PositionedBlock>,
        val tailBlockKey: Int,
        /** 围栏内容里已冻结完成的源码起点（全文偏移）。 */
        var pendingStart: Int,
        /** 已冻结内容解析出的 value 前缀（含行终止符）。 */
        var valuePrefix: String,
    )

    class Parser {
        private var prevText = ""
        private var tailStart = 0
        private var frozen = ArrayList<PositionedBlock>()
        private var generation = 0
        private var cached: Result? = null
        private var openFence: OpenFence? = null

        /**
         * 折叠当前累计文本并返回冻结/尾部切分。相同输入幂等（直接返回缓存），
         * 因此可以在重组路径上自由调用。
         */
        fun update(text: String): Result {
            cached?.let { if (text == prevText) return it }
            // O(前缀) memcmp：startsWith 比逐字节解析快两个量级，正是本类要省的成本。
            if (!text.startsWith(prevText)) {
                prevText = ""
                tailStart = 0
                frozen = ArrayList()
                openFence = null
                generation += 1
            }
            val previousText = prevText
            if (previousText.isNotEmpty()) {
                val fence = openFence
                if (fence != null) {
                    val incremental = updateOpenFence(fence, text)
                    if (incremental != null) {
                        prevText = text
                        cached = incremental
                        return incremental
                    }
                    openFence = null
                }
            }
            prevText = text
            val base = tailStart
            val source = text.substring(base)
            val blocks = MarkdownEngine.parseWithOffsets(source)
            var firstUnstable = (blocks.size - UNSTABLE_TAIL_BLOCKS).coerceAtLeast(0)
            if (firstUnstable > 0) {
                val cut = blocks[firstUnstable - 1]
                val cutEnd = cut.endOffset
                if (cutEnd < 0) {
                    firstUnstable = 0
                } else {
                    for (node in blocks.subList(0, firstUnstable)) {
                        frozen.add(PositionedBlock(node.block, base + node.startOffset))
                    }
                    tailStart = base + cutEnd
                }
            }
            val tail = blocks.drop(firstUnstable).mapIndexed { index, node ->
                PositionedBlock(node.block, base + node.startOffset)
            }
            val result = Result(frozen.toList(), tail, generation)
            cached = result
            openFence = openFenceState(text, base, tail)
            return result
        }

        /** 识别尾部最终未闭合围栏并建立其增量内容边界。 */
        private fun openFenceState(text: String, base: Int, tail: List<PositionedBlock>): OpenFence? {
            val last = tail.lastOrNull() ?: return null
            val code = last.node as? MdBlock.Code ?: return null
            if (!code.unclosed) return null
            val openingLine = code.openingLine ?: return null
            val m = Regex("^( {0,3})(`{3,}|~{3,})").find(openingLine) ?: return null
            val indent = m.groupValues[1]
            val run = m.groupValues[2]
            val marker = run[0]
            if (containsClosingFence(code.code, marker, run.length)) return null
            // 已完成内容 = 最后一个完成行之前的部分。
            val stableLength = committableLinePrefixLength(code.code)
            val stableValue = if (stableLength == 0) "" else code.code.substring(0, stableLength)
            val syntheticPrefix = "$indent$run\n"
            return OpenFence(
                marker = marker,
                markerLength = run.length,
                syntheticPrefix = syntheticPrefix,
                frozenSnapshot = frozen.toList(),
                tailBlockKey = last.key,
                pendingStart = base + code.contentStartOffset + stableLength,
                valuePrefix = if (stableLength == 0) {
                    ""
                } else {
                    stableValue + trailingLineTerminator(code.code.substring(0, stableLength))
                },
            )
        }

        /** 直接扩展已识别的未闭合围栏，不重解析其已完成内容前缀。 */
        private fun updateOpenFence(state: OpenFence, text: String): Result? {
            val content = text.substring(state.pendingStart)
            if (containsClosingFence(content, state.marker, state.markerLength)) return null
            val parsed = MarkdownEngine.parse(state.syntheticPrefix + content)
                .firstOrNull() as? MdBlock.Code ?: return null
            val stableLength = committableLinePrefixLength(content)
            val stableValue = if (stableLength == 0) {
                ""
            } else {
                val node = MarkdownEngine.parse(state.syntheticPrefix + content.substring(0, stableLength))
                    .firstOrNull() as? MdBlock.Code ?: return null
                if (node.unclosed) return null
                node.code
            }
            val tailBlock = MdBlock.Code(
                lang = "",
                code = state.valuePrefix + parsed.code,
                unclosed = true,
                openingLine = null,
            )
            val tail = listOf(PositionedBlock(tailBlock, state.tailBlockKey))
            val result = Result(state.frozenSnapshot, tail, generation)
            state.pendingStart += stableLength
            state.valuePrefix = if (stableLength == 0) {
                state.valuePrefix
            } else {
                state.valuePrefix + stableValue + trailingLineTerminator(content.substring(0, stableLength))
            }
            return result
        }
    }

    /** 最后一个完成行之前的前缀长度（不含其终止符），供冻结决策使用。 */
    fun committableLinePrefixLength(text: String): Int {
        var previousEnd = 0
        var end = 0
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '\n' -> { previousEnd = end; end = i + 1 }
                '\r' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') i += 1
                    previousEnd = end
                    end = i + 1
                }
            }
            i += 1
        }
        return previousEnd
    }

    private fun trailingLineTerminator(text: String): String = when {
        text.endsWith("\r\n") -> "\r\n"
        text.endsWith("\r") -> "\r"
        else -> "\n"
    }

    /** text 的逻辑行里是否出现 CommonMark 收尾围栏。 */
    fun containsClosingFence(text: String, marker: Char, markerLength: Int): Boolean {
        var start = 0
        while (start <= text.length) {
            var end = start
            while (end < text.length && text[end] != '\n' && text[end] != '\r') end += 1
            val line = text.substring(start, end)
            var indent = 0
            while (indent < 3 && indent < line.length && line[indent] == ' ') indent += 1
            var run = indent
            while (run < line.length && line[run] == marker) run += 1
            if (run - indent >= markerLength && line.substring(run).isBlank()) return true
            if (end >= text.length) break
            start = if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') end + 2 else end + 1
        }
        return false
    }
}
