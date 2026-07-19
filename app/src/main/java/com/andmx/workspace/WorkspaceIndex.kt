package com.andmx.workspace

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

enum class WorkspaceIndexPhase {
    IDLE,
    COUNTING,
    BUILDING,
    READY,
    SKIPPED,
    ERROR,
}

data class WorkspaceIndexStatus(
    val rootPath: String? = null,
    val phase: WorkspaceIndexPhase = WorkspaceIndexPhase.IDLE,
    val fileCount: Int = 0,
    val contentFiles: Int = 0,
    val builtAt: Long = 0L,
    val instantGrep: Boolean = false,
    val message: String = "",
)

class WorkspaceIndex private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = SettingsStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val rootDir = File(app.filesDir, "workspace-index").also { it.mkdirs() }

    private val _status = MutableStateFlow(WorkspaceIndexStatus())
    val status: StateFlow<WorkspaceIndexStatus> = _status.asStateFlow()

    fun onProjectSelected(path: String?) {
        val root = path?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() && !it.startsWith("ssh://") }
        if (root == null) {
            _status.value = WorkspaceIndexStatus(message = "未选择本地项目")
            return
        }
        scope.launch { ensureForRoot(root, force = false) }
    }

    fun rebuildCurrent() {
        val root = ProjectManager(app).hostPath.value?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() && !it.startsWith("ssh://") }
            ?: return
        scope.launch { ensureForRoot(root, force = true) }
    }

    fun clearCurrent() {
        val root = ProjectManager(app).hostPath.value?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() && !it.startsWith("ssh://") }
            ?: return
        scope.launch {
            mutex.withLock {
                jobs.remove(root)?.cancel()
                dirFor(root).deleteRecursively()
                _status.value = WorkspaceIndexStatus(
                    rootPath = root,
                    phase = WorkspaceIndexPhase.IDLE,
                    message = "已清除本地索引",
                )
            }
        }
    }

    suspend fun refreshStatus(root: String? = ProjectManager(app).hostPath.value) {
        val path = root?.trim()?.trimEnd('/')
        if (path.isNullOrBlank() || path.startsWith("ssh://")) {
            _status.value = WorkspaceIndexStatus(message = "未选择本地项目")
            return
        }
        val snap = readSnapshot(path)
        val settings = store.settings.first()
        if (snap != null) {
            _status.value = WorkspaceIndexStatus(
                rootPath = path,
                phase = WorkspaceIndexPhase.READY,
                fileCount = snap.fileCount,
                contentFiles = snap.contentFiles,
                builtAt = snap.builtAt,
                instantGrep = snap.instantGrep,
                message = statusMessage(snap, settings),
            )
        } else {
            _status.value = WorkspaceIndexStatus(
                rootPath = path,
                phase = WorkspaceIndexPhase.IDLE,
                message = if (settings.indexNewFolders && settings.indexNewFoldersUserConfigured) "尚未建立索引" else "索引已关闭",
            )
        }
    }

    suspend fun listIndexedFiles(root: String): List<String>? = withContext(Dispatchers.IO) {
        val snap = readSnapshot(root.trimEnd('/')) ?: return@withContext null
        snap.files
    }

    suspend fun globIndexed(
        root: String,
        pattern: String,
        max: Int,
    ): List<String>? = withContext(Dispatchers.IO) {
        val files = listIndexedFiles(root) ?: return@withContext null
        val regex = globToRegex(pattern)
        files.asSequence()
            .filter { regex.matches(it) || regex.matches(it.substringAfterLast('/')) }
            .take(max.coerceIn(1, 2000))
            .toList()
    }

    suspend fun grepIndexed(
        root: String,
        pattern: String,
        pathFilter: String? = null,
        glob: String? = null,
        ignoreCase: Boolean = false,
        maxResults: Int = 80,
        offset: Int = 0,
        outputMode: String = "content",
    ): List<String>? = withContext(Dispatchers.IO) {
        val normalized = root.trimEnd('/')
        val settings = store.settings.first()
        if (!settings.instantGrep) return@withContext null
        val snap = readSnapshot(normalized) ?: return@withContext null
        if (!snap.instantGrep) return@withContext null
        val dbFile = File(dirFor(normalized), DB_NAME)
        if (!dbFile.exists()) return@withContext null

        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        val regex = try {
            Pattern.compile(pattern, flags)
        } catch (_: PatternSyntaxException) {
            Pattern.compile(Pattern.quote(pattern), flags)
        }
        val globRegex = glob?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val prefix = pathFilter
            ?.takeIf { it.isNotBlank() }
            ?.let { relPath(normalized, it) }
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }

        val hits = ArrayList<String>(maxResults)
        var skipped = 0
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT path, body FROM content WHERE body IS NOT NULL AND body != ''",
                emptyArray(),
            )
            cursor.use { c ->
                val pathIdx = c.getColumnIndex("path")
                val bodyIdx = c.getColumnIndex("body")
                while (c.moveToNext()) {
                    val rel = c.getString(pathIdx) ?: continue
                    if (prefix != null && rel != prefix && !rel.startsWith("$prefix/")) continue
                    if (globRegex != null && !globRegex.matches(rel) && !globRegex.matches(rel.substringAfterLast('/'))) {
                        continue
                    }
                    val body = c.getString(bodyIdx) ?: continue
                    when (outputMode) {
                        "files_with_matches" -> {
                            if (regex.matcher(body).find()) {
                                if (skipped < offset) {
                                    skipped++
                                } else {
                                    hits.add(rel)
                                    if (hits.size >= maxResults) return@use
                                }
                            }
                        }
                        "count" -> {
                            var count = 0
                            body.lineSequence().forEach { line ->
                                if (regex.matcher(line).find()) count++
                            }
                            if (count > 0) {
                                if (skipped < offset) {
                                    skipped++
                                } else {
                                    hits.add("$rel:$count")
                                    if (hits.size >= maxResults) return@use
                                }
                            }
                        }
                        else -> {
                            var lineNo = 0
                            body.lineSequence().forEach { line ->
                                lineNo++
                                if (regex.matcher(line).find()) {
                                    if (skipped < offset) {
                                        skipped++
                                    } else {
                                        hits.add("$rel:$lineNo:$line")
                                        if (hits.size >= maxResults) return@use
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "grepIndexed failed: ${t.message}")
            return@withContext null
        } finally {
            db?.close()
        }
        hits
    }

    private suspend fun ensureForRoot(root: String, force: Boolean) {
        val settings = store.settings.first()
        val wantSnapshot = settings.indexNewFolders && settings.indexNewFoldersUserConfigured
        val wantGrep = settings.instantGrep
        if (!wantSnapshot && !wantGrep) {
            _status.value = WorkspaceIndexStatus(
                rootPath = root,
                phase = WorkspaceIndexPhase.IDLE,
                message = "索引已关闭",
            )
            return
        }
        if (!File(root).isDirectory) {
            _status.value = WorkspaceIndexStatus(
                rootPath = root,
                phase = WorkspaceIndexPhase.ERROR,
                message = "项目路径不可用",
            )
            return
        }

        jobs[root]?.cancel()
        val job = scope.launch {
            mutex.withLock {
                try {
                    buildIndex(root, settings, force)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.e(TAG, "index build failed", t)
                    _status.value = WorkspaceIndexStatus(
                        rootPath = root,
                        phase = WorkspaceIndexPhase.ERROR,
                        message = "索引失败: ${t.message ?: t.javaClass.simpleName}",
                    )
                }
            }
        }
        jobs[root] = job
        job.invokeOnCompletion { jobs.remove(root, job) }
    }

    private fun buildIndex(root: String, settings: ProviderSettings, force: Boolean) {
        val existing = readSnapshot(root)
        if (!force && existing != null &&
            existing.instantGrep == settings.instantGrep &&
            existing.rootPath == root &&
            File(root).lastModified() <= existing.builtAt + 2_000L
        ) {
            _status.value = WorkspaceIndexStatus(
                rootPath = root,
                phase = WorkspaceIndexPhase.READY,
                fileCount = existing.fileCount,
                contentFiles = existing.contentFiles,
                builtAt = existing.builtAt,
                instantGrep = existing.instantGrep,
                message = statusMessage(existing, settings) + " · 已是最新",
            )
            return
        }

        _status.value = WorkspaceIndexStatus(
            rootPath = root,
            phase = WorkspaceIndexPhase.COUNTING,
            message = "正在统计文件…",
            instantGrep = settings.instantGrep,
        )

        val limit = settings.indexFileLimit.coerceAtLeast(1)
        val collected = ArrayList<FileEntry>(minOf(limit, 4096))
        var totalSeen = 0
        val stack = ArrayDeque<File>()
        stack.add(File(root))
        var skippedTooLarge = false
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                val name = child.name
                if (name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (name in IGNORED_DIRS) continue
                    stack.add(child)
                } else if (child.isFile) {
                    totalSeen++
                    if (collected.size < limit) {
                        val rel = relPath(root, child.absolutePath) ?: continue
                        collected.add(
                            FileEntry(
                                path = rel,
                                size = child.length(),
                                mtime = child.lastModified(),
                            ),
                        )
                    } else {
                        skippedTooLarge = true
                        break
                    }
                }
            }
            if (skippedTooLarge) break
        }

        if (skippedTooLarge || totalSeen > limit) {
            _status.value = WorkspaceIndexStatus(
                rootPath = root,
                phase = WorkspaceIndexPhase.SKIPPED,
                fileCount = totalSeen.coerceAtLeast(limit),
                message = "文件数超过 $limit，已跳过自动索引",
                instantGrep = settings.instantGrep,
            )
            dirFor(root).deleteRecursively()
            return
        }

        _status.value = WorkspaceIndexStatus(
            rootPath = root,
            phase = WorkspaceIndexPhase.BUILDING,
            fileCount = collected.size,
            message = if (settings.instantGrep) "正在建立即时搜索索引…" else "正在建立文件索引…",
            instantGrep = settings.instantGrep,
        )

        val dir = dirFor(root)
        dir.mkdirs()
        val dbFile = File(dir, DB_NAME)
        if (dbFile.exists()) dbFile.delete()

        var contentFiles = 0
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            db.execSQL("CREATE TABLE IF NOT EXISTS files(path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS content(path TEXT PRIMARY KEY, body TEXT)")
            db.beginTransaction()
            try {
                for (entry in collected) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO files(path,size,mtime) VALUES(?,?,?)",
                        arrayOf(entry.path, entry.size, entry.mtime),
                    )
                    if (settings.instantGrep && shouldIndexContent(entry)) {
                        val abs = File(root, entry.path)
                        val body = runCatching {
                            abs.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                                buildString {
                                    var lines = 0
                                    var bytes = 0
                                    while (true) {
                                        val line = reader.readLine() ?: break
                                        lines++
                                        bytes += line.length + 1
                                        if (lines > MAX_CONTENT_LINES || bytes > MAX_CONTENT_BYTES) break
                                        append(line)
                                        append('\n')
                                    }
                                }
                            }
                        }.getOrNull()
                        if (!body.isNullOrEmpty() && looksLikeText(body)) {
                            db.execSQL(
                                "INSERT OR REPLACE INTO content(path,body) VALUES(?,?)",
                                arrayOf(entry.path, body),
                            )
                            contentFiles++
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db?.close()
        }

        val builtAt = System.currentTimeMillis()
        val snap = IndexSnapshot(
            rootPath = root,
            fileCount = collected.size,
            contentFiles = contentFiles,
            builtAt = builtAt,
            instantGrep = settings.instantGrep,
            files = collected.map { it.path },
        )
        writeSnapshot(root, snap)
        _status.value = WorkspaceIndexStatus(
            rootPath = root,
            phase = WorkspaceIndexPhase.READY,
            fileCount = snap.fileCount,
            contentFiles = snap.contentFiles,
            builtAt = snap.builtAt,
            instantGrep = snap.instantGrep,
            message = statusMessage(snap, settings),
        )
    }

    private fun statusMessage(snap: IndexSnapshot, settings: ProviderSettings): String {
        val parts = mutableListOf("已索引 ${snap.fileCount} 个文件")
        if (settings.instantGrep || snap.instantGrep) {
            parts.add("内容 ${snap.contentFiles}")
        }
        if (snap.builtAt > 0) {
            parts.add("本地存储")
        }
        return parts.joinToString(" · ")
    }

    private fun dirFor(root: String): File = File(rootDir, hash(root))

    private fun snapshotFile(root: String): File = File(dirFor(root), SNAPSHOT_NAME)

    private fun readSnapshot(root: String): IndexSnapshot? {
        val file = snapshotFile(root)
        if (!file.exists()) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            val arr = obj.optJSONArray("files") ?: JSONArray()
            val files = buildList {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
            IndexSnapshot(
                rootPath = obj.optString("rootPath", root),
                fileCount = obj.optInt("fileCount", files.size),
                contentFiles = obj.optInt("contentFiles", 0),
                builtAt = obj.optLong("builtAt", 0L),
                instantGrep = obj.optBoolean("instantGrep", false),
                files = files,
            )
        }.getOrNull()
    }

    private fun writeSnapshot(root: String, snap: IndexSnapshot) {
        val obj = JSONObject()
        obj.put("rootPath", snap.rootPath)
        obj.put("fileCount", snap.fileCount)
        obj.put("contentFiles", snap.contentFiles)
        obj.put("builtAt", snap.builtAt)
        obj.put("instantGrep", snap.instantGrep)
        val arr = JSONArray()
        snap.files.forEach { arr.put(it) }
        obj.put("files", arr)
        snapshotFile(root).writeText(obj.toString())
    }

    private data class FileEntry(val path: String, val size: Long, val mtime: Long)

    private data class IndexSnapshot(
        val rootPath: String,
        val fileCount: Int,
        val contentFiles: Int,
        val builtAt: Long,
        val instantGrep: Boolean,
        val files: List<String>,
    )

    companion object {
        private const val TAG = "WorkspaceIndex"
        private const val SNAPSHOT_NAME = "snapshot.json"
        private const val DB_NAME = "content.db"
        private const val MAX_CONTENT_BYTES = 512 * 1024
        private const val MAX_CONTENT_LINES = 8_000
        private const val MAX_FILE_SIZE_FOR_CONTENT = 768 * 1024L

        private val IGNORED_DIRS = setOf(
            "node_modules", ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle",
            "build", "dist", "out", "target", "Pods", "DerivedData", "__pycache__",
            ".next", ".nuxt", ".turbo", ".cache", "coverage", "vendor", ".dart_tool",
            "xcuserdata", "Carthage",
        )

        private val TEXT_EXT = setOf(
            "kt", "kts", "java", "xml", "gradle", "properties", "md", "txt", "json",
            "yml", "yaml", "toml", "ts", "tsx", "js", "jsx", "mjs", "cjs", "css", "scss",
            "less", "html", "htm", "vue", "svelte", "py", "rb", "go", "rs", "c", "cc",
            "cpp", "h", "hpp", "swift", "m", "mm", "cs", "php", "sh", "bash", "zsh",
            "sql", "proto", "graphql", "gql", "ini", "cfg", "conf", "env", "gitignore",
            "dockerignore", "editorconfig", "plist", "cmake", "makefile", "mk", "dart",
            "ktm", "r", "jl", "lua", "pl", "pm", "scala", "clj", "ex", "exs", "erl",
            "hs", "sbt", "pom", "lock", "sum", "mod", "svg", "csv", "tsv",
        )

        @Volatile private var instance: WorkspaceIndex? = null

        fun get(context: Context): WorkspaceIndex {
            return instance ?: synchronized(this) {
                instance ?: WorkspaceIndex(context.applicationContext).also { instance = it }
            }
        }

        private fun hash(root: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(root.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun relPath(root: String, absolute: String): String? {
            val r = root.trimEnd('/')
            val a = absolute.trimEnd('/')
            if (a == r) return ""
            if (!a.startsWith("$r/")) return null
            return a.removePrefix("$r/")
        }

        private fun shouldIndexContent(entry: FileEntry): Boolean {
            if (entry.size <= 0L || entry.size > MAX_FILE_SIZE_FOR_CONTENT) return false
            val name = entry.path.substringAfterLast('/')
            if (name.startsWith(".")) return false
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            if (ext.isEmpty()) return name in setOf("Dockerfile", "Makefile", "Gemfile", "Rakefile", "Procfile")
            return ext in TEXT_EXT || name.endsWith(".gradle.kts") || name.endsWith(".gitignore")
        }

        private fun looksLikeText(body: String): Boolean {
            if (body.isEmpty()) return false
            val sample = body.take(4096)
            var weird = 0
            for (ch in sample) {
                if (ch == '\u0000') return false
                if (ch < ' ' && ch != '\n' && ch != '\r' && ch != '\t') weird++
            }
            return weird * 20 < sample.length
        }

        private fun globToRegex(glob: String): Regex {
            val g = glob.trim()
            val sb = StringBuilder("^")
            var i = 0
            while (i < g.length) {
                when (val c = g[i]) {
                    '*' -> {
                        if (i + 1 < g.length && g[i + 1] == '*') {
                            sb.append(".*")
                            i += 2
                            if (i < g.length && g[i] == '/') i++
                        } else {
                            sb.append("[^/]*")
                            i++
                        }
                    }
                    '?' -> {
                        sb.append("[^/]")
                        i++
                    }
                    '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> {
                        sb.append('\\').append(c)
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            sb.append('$')
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}
