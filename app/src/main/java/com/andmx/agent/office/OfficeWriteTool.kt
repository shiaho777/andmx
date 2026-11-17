package com.andmx.agent.office

import android.content.Context
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val OFFICE_WRITE_DESCRIPTION = listOf(
    "Generate an office document (docx / xlsx / pptx / pdf) in the workspace.",
    "",
    "- `content` accepts markdown (# headings, - bullets, | tables |, **bold**) or structured JSON:",
    "  {\"blocks\":[{\"type\":\"heading\",\"level\":1,\"text\":\"...\"},{\"type\":\"paragraph\",\"text\":\"...\",\"bold\":false},{\"type\":\"bullet\",\"text\":\"...\"},{\"type\":\"table\",\"rows\":[[\"a\",\"b\"],[\"1\",\"2\"]]}]}",
    "- docx renders paragraphs/headings/bold/bullets/tables; xlsx flattens blocks into rows (numbers stay numeric);",
    "  pptx starts a new slide at each H1/H2 heading (title + body text boxes); pdf paginates A4 with headings and paragraphs.",
    "- `path` is a workspace path; the matching extension is appended when missing.",
).joinToString("\n")

private val OFFICE_WRITE_PARAMETERS = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        putJsonObject("format") {
            put("type", "string")
            putJsonArray("enum") { add("docx"); add("xlsx"); add("pptx"); add("pdf") }
            put("description", "Output document format")
        }
        putJsonObject("path") {
            put("type", "string")
            put("description", "Workspace path of the file to create")
        }
        putJsonObject("content") {
            put("type", "string")
            put("description", "Document content as markdown or structured JSON blocks")
        }
    }
    putJsonArray("required") { add("format"); add("path"); add("content") }
}

class OfficeWriteTool(context: Context) : Tool {
    private val appContext = context.applicationContext
    override val name = "office_write"
    override val description: String = OFFICE_WRITE_DESCRIPTION
    override val parameters = OFFICE_WRITE_PARAMETERS
    override val risk = ToolRisk.WRITE
    override val readOnly = false
    override val destructive = false
    override val concurrentSafe = false
    override val permissionName = "edit"
    override val timeoutMs = 60_000L
    override val maxOutputBytes = 4_000

    override suspend fun execute(args: JsonObject): ToolResult {
        val formatRaw = args.str("format") ?: return ToolResult("format is required", isError = true)
        val format = OfficeFormat.from(formatRaw)
            ?: return ToolResult("format must be one of docx|xlsx|pptx|pdf", isError = true)
        val rawPath = args.str("path")?.trim().orEmpty()
        if (rawPath.isEmpty()) return ToolResult("path is required", isError = true)
        val content = args.str("content") ?: return ToolResult("content is required", isError = true)
        if (content.isBlank()) return ToolResult("content must not be blank", isError = true)
        val path = if (rawPath.substringAfterLast('.', "").lowercase() == format.extension) {
            rawPath
        } else {
            "$rawPath.${format.extension}"
        }
        return try {
            val model = DocumentModel.fromContent(content)
            val resolved = OfficeWriter.write(appContext, format, model, path)
            ToolResult("Document created successfully at: $resolved (${format.extension}, ${model.blocks.size} blocks)")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult("文档生成失败: ${error.message ?: "unknown"}", isError = true)
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
