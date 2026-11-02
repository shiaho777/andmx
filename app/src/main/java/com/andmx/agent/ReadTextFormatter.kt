package com.andmx.agent

object ReadTextFormatter {
    const val MAX_OUTPUT_TOKENS = 25_000
    const val MAX_FILE_BYTES = 256 * 1024
    private const val PARTIAL_TARGET = (MAX_OUTPUT_TOKENS * 0.85).toInt()

    const val UNCHANGED =
        "Wasted call — file unchanged since your last Read. Refer to that earlier tool_result instead."

    const val FRESHNESS_SUFFIX =
        " (file state is current in your context — no need to Read it back)"

    fun tooLarge(sizeBytes: Long): String =
        "File content ($sizeBytes bytes) exceeds maximum allowed size ($MAX_FILE_BYTES bytes). " +
            "Use offset and limit parameters to read specific portions of the file, " +
            "or search for specific content instead of reading the whole file."

    data class View(
        val output: String,
        val stored: String,
        val partial: Boolean,
        val isError: Boolean = false,
    )

    fun format(text: String, offsetZeroBased: Int, limit: Int?): View {
        if (text.isEmpty()) {
            return View(
                output = warn("Warning: the file exists but the contents are empty."),
                stored = "",
                partial = false,
            )
        }
        val lines = text.split('\n')
        return formatLines(lines, lines.size, offsetZeroBased, limit, alreadySliced = false)
    }

    fun formatWindow(slice: List<String>, totalLines: Int, offsetZeroBased: Int): View =
        formatLines(slice, totalLines, offsetZeroBased, limit = slice.size, alreadySliced = true)

    private fun formatLines(
        lines: List<String>,
        totalLines: Int,
        offsetZeroBased: Int,
        limit: Int?,
        alreadySliced: Boolean,
    ): View {
        if (offsetZeroBased >= totalLines) {
            return View(
                output = warn(
                    "Warning: the file exists but is shorter than the provided offset " +
                        "(${offsetZeroBased + 1}). The file has $totalLines lines.",
                ),
                stored = "",
                partial = true,
            )
        }
        val source = if (alreadySliced) {
            lines
        } else {
            val end = if (limit == null) lines.size else (offsetZeroBased + limit).coerceAtMost(lines.size)
            lines.subList(offsetZeroBased, end)
        }
        if (limit == null && !alreadySliced) {
            val joined = source.joinToString("\n")
            val tokens = TokenEstimate.estimate(joined)
            if (tokens > MAX_OUTPUT_TOKENS) {
                return tokenCapped(source, totalLines, offsetZeroBased, tokens)
            }
        }
        val moreAfter = offsetZeroBased + source.size < totalLines
        val numbered = number(source, offsetZeroBased)
        val notice = if (limit != null && moreAfter) {
            val shownEnd = offsetZeroBased + source.size
            warn(
                "Showing lines ${offsetZeroBased + 1}-$shownEnd of $totalLines. " +
                    "Use Read with offset ${shownEnd + 1} to continue.",
            )
        } else {
            null
        }
        val output = if (notice == null) numbered else "$numbered\n\n$notice"
        return View(
            output = output,
            stored = source.joinToString("\n"),
            partial = offsetZeroBased > 0 || moreAfter,
        )
    }

    private fun tokenCapped(
        lines: List<String>,
        totalLines: Int,
        offsetZeroBased: Int,
        tokens: Int,
    ): View {
        val keep = largestPrefix(lines, PARTIAL_TARGET)
        if (keep > 0) {
            val slice = lines.subList(0, keep)
            val startLine = offsetZeroBased + 1
            val endLine = offsetZeroBased + keep
            val notice = warn(
                "The file is too large to display in full ($tokens estimated tokens, limit $MAX_OUTPUT_TOKENS). " +
                    "Showing a partial view of lines $startLine-$endLine of $totalLines. " +
                    "Use Read with offset ${endLine + 1} and limit ${ReadFileTool.DEFAULT_LINE_LIMIT} to continue, " +
                    "or use a search tool to find a specific section.",
            )
            return View(
                output = "$notice\n\n${number(slice, offsetZeroBased)}",
                stored = slice.joinToString("\n"),
                partial = true,
            )
        }
        val chars = largestChars(lines.first(), PARTIAL_TARGET)
        if (chars <= 0) {
            return View(
                output = "File content ($tokens tokens) exceeds maximum allowed tokens ($MAX_OUTPUT_TOKENS). " +
                    "Use offset and limit parameters to read specific portions of the file, " +
                    "or search for specific content instead of reading the whole file.",
                stored = "",
                partial = true,
                isError = true,
            )
        }
        val content = lines.first().take(chars)
        val notice = warn(
            "The file is too large to display in full ($tokens estimated tokens, limit $MAX_OUTPUT_TOKENS). " +
                "Showing a partial view of the first line because the first line alone exceeds the token budget. " +
                "Use Read with a smaller range or use a search tool to find a specific section.",
        )
        return View(
            output = "$notice\n\n${offsetZeroBased + 1}\t$content",
            stored = content,
            partial = true,
        )
    }

    private fun number(lines: List<String>, offsetZeroBased: Int): String =
        lines.mapIndexed { i, line -> "${offsetZeroBased + i + 1}\t$line" }.joinToString("\n")

    private fun warn(body: String): String =
        SystemReminder.wrap(SystemReminder.Source.TOOL_RESULT_WARNING, body)

    private fun largestPrefix(lines: List<String>, tokenBudget: Int): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val mid = (low + high + 1) / 2
            val n = TokenEstimate.estimate(lines.subList(0, mid).joinToString("\n"))
            if (n <= tokenBudget) low = mid else high = mid - 1
        }
        return low
    }

    private fun largestChars(line: String, tokenBudget: Int): Int {
        var low = 0
        var high = line.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (TokenEstimate.estimate(line.take(mid)) <= tokenBudget) low = mid else high = mid - 1
        }
        return low
    }
}
