package com.andmx.agent.multi

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.andmx.settings.CustomSubAgent
import java.io.File

object SubagentStorage {
    fun agentsDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        val dir = if (external != null) {
            File(external, "agents")
        } else {
            File(context.filesDir, "agents")
        }
        dir.mkdirs()
        migrateFromInternal(context, dir)
        return dir
    }

    private fun migrateFromInternal(context: Context, target: File) {
        val legacy = File(context.filesDir, "agents")
        if (!legacy.isDirectory || legacy.absolutePath == target.absolutePath) return
        legacy.listFiles()?.forEach { src ->
            if (!src.isFile) return@forEach
            val dest = File(target, src.name)
            if (!dest.exists()) {
                runCatching { src.copyTo(dest, overwrite = false) }
            }
        }
    }

    fun listAgentFiles(context: Context): List<File> {
        val dir = agentsDir(context)
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedWith(compareBy({ it.name.equals("README.md", true).not() }, { it.name.lowercase() }))
            .orEmpty()
    }

    fun syncUserAgents(context: Context, agents: List<CustomSubAgent>) {
        val dir = agentsDir(context)
        agents.filter { it.scope == "user" || it.source == "user" }.forEach { agent ->
            val name = agent.name.trim().lowercase().ifBlank { agent.id }
            val file = File(dir, "$name.md")
            file.writeText(SubagentCatalog.serializeMarkdown(agent), Charsets.UTF_8)
        }
        val readme = File(dir, "README.md")
        if (!readme.exists()) {
            readme.writeText(
                """
                # AndMX 子智能体目录

                将用户级子智能体 Markdown 放在此目录（与 ZCode `~/.zcode/agents` 对应）。
                也可在 App 设置中创建；保存时会同步到这里。

                路径：
                ${dir.absolutePath}
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )
        } else {
            // keep path up to date at bottom is optional; leave file stable once created
        }
    }

    fun fileProviderUri(context: Context, file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun openAgentsFolder(context: Context) {
        val dir = agentsDir(context)
        syncUserAgents(context, emptyList())
        val readme = File(dir, "README.md")
        if (!readme.exists()) {
            syncUserAgents(context, emptyList())
        }

        val uri = runCatching { fileProviderUri(context, readme) }.getOrElse {
            Toast.makeText(context, "无法暴露目录：${it.message}", Toast.LENGTH_LONG).show()
            copyPath(context, dir)
            return
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "agents-readme", uri)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "AndMX 用户子智能体目录\n${dir.absolutePath}",
            )
            putExtra(Intent.EXTRA_SUBJECT, "AndMX agents")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "agents-readme", uri)
        }

        val files = listAgentFiles(context).filter { !it.name.equals("README.md", true) }
        val multiUris = ArrayList<Uri>()
        files.take(20).forEach { f ->
            runCatching { multiUris.add(fileProviderUri(context, f)) }
        }
        val shareAllIntent = if (multiUris.isNotEmpty()) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, multiUris)
                putExtra(Intent.EXTRA_TEXT, "AndMX 用户子智能体（${multiUris.size} 个文件）\n${dir.absolutePath}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val clip = ClipData.newUri(context.contentResolver, "agents", multiUris.first())
                multiUris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                clipData = clip
            }
        } else null

        val extras = buildList {
            add(shareIntent)
            if (shareAllIntent != null) add(shareAllIntent)
        }.toTypedArray()

        val chooser = Intent.createChooser(viewIntent, "打开用户子智能体目录").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, extras)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        grantUri(context, viewIntent, uri)
        grantUri(context, shareIntent, uri)
        multiUris.forEach { u ->
            grantUri(context, shareAllIntent ?: shareIntent, u)
        }

        runCatching {
            context.startActivity(chooser)
        }.onFailure {
            copyPath(context, dir)
            Toast.makeText(
                context,
                "无法调起打开方式：${it.message}\n路径已复制",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun openAgentFile(context: Context, file: File) {
        if (!file.exists()) return
        val uri = runCatching { fileProviderUri(context, file) }.getOrElse {
            Toast.makeText(context, "无法打开：${it.message}", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = when {
            file.name.endsWith(".md", true) -> "text/markdown"
            file.name.endsWith(".txt", true) -> "text/plain"
            else -> "text/plain"
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        }
        grantUri(context, view, uri)
        grantUri(context, share, uri)
        val chooser = Intent.createChooser(view, "打开 ${file.name}").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(share))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure {
                Toast.makeText(context, "没有可打开的应用", Toast.LENGTH_SHORT).show()
            }
    }

    private fun grantUri(context: Context, intent: Intent, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val matches = context.packageManager.queryIntentActivities(intent, 0)
        for (info in matches) {
            runCatching {
                context.grantUriPermission(info.activityInfo.packageName, uri, flags)
            }
        }
    }

    private fun copyPath(context: Context, dir: File) {
        val path = dir.absolutePath
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("agents", path))
        Toast.makeText(context, "路径已复制\n$path", Toast.LENGTH_LONG).show()
    }

    fun loadMarkdownAgents(context: Context): List<CustomSubAgent> {
        val dir = agentsDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md", true) && !it.name.equals("README.md", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { f ->
                runCatching {
                    SubagentCatalog.parseMarkdown(f.readText(Charsets.UTF_8), f.absolutePath, "user")
                }.getOrNull()
            }
            .orEmpty()
    }
}
