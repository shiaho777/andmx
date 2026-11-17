package com.andmx.agent.office

import android.content.Context
import com.andmx.workspace.WorkspaceAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class OfficeFormat(val extension: String) {
    DOCX("docx"),
    XLSX("xlsx"),
    PPTX("pptx"),
    PDF("pdf");

    companion object {
        fun from(raw: String): OfficeFormat? =
            entries.firstOrNull { it.extension == raw.trim().lowercase().removePrefix(".") }
    }
}

object OfficeWriter {
    fun render(format: OfficeFormat, model: DocumentModel): ByteArray = when (format) {
        OfficeFormat.DOCX -> DocxWriter.write(model)
        OfficeFormat.XLSX -> XlsxWriter.write(model)
        OfficeFormat.PPTX -> PptxWriter.write(model)
        OfficeFormat.PDF -> PdfWriter.write(model)
    }

    suspend fun write(context: Context, format: OfficeFormat, model: DocumentModel, path: String): String =
        withContext(Dispatchers.IO) {
            val bytes = render(format, model)
            val access = WorkspaceAccess(context)
            val resolved = access.resolvePath(path)
            if (access.isRemote) {
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val result = access.executeShell(
                    "mkdir -p $(dirname ${shq(resolved)}) && echo ${shq(b64)} | base64 -d > ${shq(resolved)} && echo OK",
                )
                if (!result.stdout.contains("OK")) {
                    error("远程写入失败: ${result.stdout} ${result.stderr}")
                }
            } else {
                val host = access.hostFile(resolved) ?: error("无法定位本地路径: $resolved")
                host.parentFile?.mkdirs()
                host.writeBytes(bytes)
            }
            com.andmx.workspace.WorkspaceIndex.get(context)
                .invalidatePath(access.hostFile(resolved)?.absolutePath, access.hostPath)
            resolved
        }

    private fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
