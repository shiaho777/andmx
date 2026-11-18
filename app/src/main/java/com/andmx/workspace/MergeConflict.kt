package com.andmx.workspace

data class ConflictFile(
    val path: String,
    val status: String,
) {
    val statusLabel: String
        get() = when (status) {
            "UU" -> "双方修改"
            "AA" -> "双方新增"
            "DD" -> "双方删除"
            "AU" -> "本地新增未合并"
            "UA" -> "传入新增未合并"
            "DU" -> "本地删除"
            "UD" -> "传入删除"
            else -> "冲突"
        }

    val deletedByUs: Boolean get() = status == "DU" || status == "DD"
    val deletedByThem: Boolean get() = status == "UD" || status == "DD"
}

object MergeConflicts {
    private val unmergedCodes = setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU")

    fun isUnmerged(code: String): Boolean = code in unmergedCodes

    fun parsePorcelain(output: String): List<ConflictFile> =
        output.lines().mapNotNull { line ->
            if (line.length < 4) return@mapNotNull null
            val code = line.take(2)
            if (!isUnmerged(code)) return@mapNotNull null
            var path = line.drop(3).trim()
            val arrow = path.indexOf(" -> ")
            if (arrow >= 0) path = path.substring(arrow + 4)
            path = path.trim('"')
            if (path.isEmpty()) null else ConflictFile(path, code)
        }
}

enum class ConflictResolution { OURS, THEIRS, BOTH }

data class ConflictLine(val number: Int, val text: String)

sealed class ConflictSegment {
    data class Context(val lines: List<ConflictLine>) : ConflictSegment()

    data class Block(
        val index: Int,
        val startLine: Int,
        val endLine: Int,
        val oursLabel: String,
        val baseLabel: String?,
        val theirsLabel: String,
        val ours: List<ConflictLine>,
        val base: List<ConflictLine>?,
        val theirs: List<ConflictLine>,
        val raw: List<String>,
    ) : ConflictSegment()
}

data class ConflictDocument(
    val segments: List<ConflictSegment>,
    val malformed: Boolean,
    val endsWithNewline: Boolean,
) {
    val blocks: List<ConflictSegment.Block> = segments.filterIsInstance<ConflictSegment.Block>()
    val hasConflicts: Boolean get() = blocks.isNotEmpty()
}

object ConflictParser {
    private val startMarker = Regex("^<<<<<<<(?: (.*))?$")
    private val baseMarker = Regex("^\\|\\|\\|\\|\\|\\|\\|(?: (.*))?$")
    private val midMarker = Regex("^=======$")
    private val endMarker = Regex("^>>>>>>>(?: (.*))?$")

    fun parse(text: String): ConflictDocument {
        val split = text.split("\n")
        val endsWithNewline = text.endsWith("\n")
        val lines = if (endsWithNewline) split.dropLast(1) else split
        val segments = mutableListOf<ConflictSegment>()
        val context = mutableListOf<ConflictLine>()
        var malformed = false
        var blockIndex = 0
        var state = 0
        var startLine = 0
        var oursLabel = ""
        var baseLabel: String? = null
        var hasBase = false
        val ours = mutableListOf<ConflictLine>()
        val base = mutableListOf<ConflictLine>()
        val theirs = mutableListOf<ConflictLine>()
        val raw = mutableListOf<String>()

        fun flushContext() {
            if (context.isNotEmpty()) {
                segments += ConflictSegment.Context(context.toList())
                context.clear()
            }
        }

        fun clearBlock() {
            raw.clear(); ours.clear(); base.clear(); theirs.clear()
            hasBase = false; baseLabel = null
        }

        fun abortBlock() {
            malformed = true
            raw.forEachIndexed { i, l -> context += ConflictLine(startLine + i, l) }
            clearBlock()
            state = 0
        }

        for ((i, line) in lines.withIndex()) {
            val no = i + 1
            when (state) {
                0 -> {
                    val m = startMarker.matchEntire(line)
                    if (m != null) {
                        flushContext()
                        clearBlock()
                        state = 1
                        startLine = no
                        oursLabel = m.groupValues[1]
                        raw += line
                    } else {
                        context += ConflictLine(no, line)
                    }
                }
                1 -> {
                    raw += line
                    val bm = baseMarker.matchEntire(line)
                    when {
                        midMarker.matches(line) -> state = 3
                        bm != null -> {
                            hasBase = true
                            baseLabel = bm.groupValues[1]
                            state = 2
                        }
                        startMarker.matches(line) || endMarker.matches(line) -> abortBlock()
                        else -> ours += ConflictLine(no, line)
                    }
                }
                2 -> {
                    raw += line
                    when {
                        midMarker.matches(line) -> state = 3
                        startMarker.matches(line) || endMarker.matches(line) ||
                            baseMarker.matches(line) -> abortBlock()
                        else -> base += ConflictLine(no, line)
                    }
                }
                else -> {
                    raw += line
                    val em = endMarker.matchEntire(line)
                    when {
                        em != null -> {
                            segments += ConflictSegment.Block(
                                index = blockIndex++,
                                startLine = startLine,
                                endLine = no,
                                oursLabel = oursLabel,
                                baseLabel = if (hasBase) baseLabel else null,
                                theirsLabel = em.groupValues[1],
                                ours = ours.toList(),
                                base = if (hasBase) base.toList() else null,
                                theirs = theirs.toList(),
                                raw = raw.toList(),
                            )
                            clearBlock()
                            state = 0
                        }
                        startMarker.matches(line) || midMarker.matches(line) ||
                            baseMarker.matches(line) -> abortBlock()
                        else -> theirs += ConflictLine(no, line)
                    }
                }
            }
        }
        if (state != 0) abortBlock()
        flushContext()
        return ConflictDocument(segments, malformed, endsWithNewline)
    }

    fun resolve(document: ConflictDocument, choices: Map<Int, ConflictResolution>): String {
        val out = mutableListOf<String>()
        for (segment in document.segments) {
            when (segment) {
                is ConflictSegment.Context -> segment.lines.forEach { out += it.text }
                is ConflictSegment.Block -> when (choices[segment.index]) {
                    ConflictResolution.OURS -> segment.ours.forEach { out += it.text }
                    ConflictResolution.THEIRS -> segment.theirs.forEach { out += it.text }
                    ConflictResolution.BOTH -> {
                        segment.ours.forEach { out += it.text }
                        segment.theirs.forEach { out += it.text }
                    }
                    null -> out += segment.raw
                }
            }
        }
        val joined = out.joinToString("\n")
        return if (document.endsWithNewline && joined.isNotEmpty()) joined + "\n" else joined
    }

    fun resolveText(text: String, strategy: ConflictResolution): String {
        val document = parse(text)
        if (document.malformed) return text
        return resolve(document, document.blocks.associate { it.index to strategy })
    }

    fun isFullyChosen(document: ConflictDocument, choices: Map<Int, ConflictResolution>): Boolean =
        !document.malformed && document.blocks.all { choices.containsKey(it.index) }

    fun hasConflictMarkers(text: String): Boolean {
        val document = parse(text)
        return document.blocks.isNotEmpty() || document.malformed
    }

    fun canMarkResolved(text: String): Boolean = !hasConflictMarkers(text)
}