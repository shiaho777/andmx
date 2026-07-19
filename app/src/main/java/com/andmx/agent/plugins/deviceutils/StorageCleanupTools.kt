package com.andmx.agent.plugins.deviceutils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.ui2.files.hasAllFilesAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.pow

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
private fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

class StorageCleanupToolset(private val context: Context) {
    fun tools(): List<Tool> = listOf(
        StorageOverviewTool(context),
        StorageScanTool(context),
        StorageFindLargeTool(context),
        StorageFindJunkTool(context),
        StorageFindDuplicatesTool(context),
        StorageAppUsageTool(context),
        StoragePreviewDeleteTool(context),
        StorageCleanTool(context),
        StorageCompareTool(context),
        StorageOpenSettingsTool(context),
    )
}

internal data class FileHit(
    val path: String,
    val size: Long,
    val kind: String,
    val tags: String,
    val mtime: Long,
    val category: String,
)

internal data class ScanPlan(
    val id: String,
    val createdAt: Long,
    val roots: List<String>,
    val large: List<FileHit>,
    val junk: List<FileHit>,
    val duplicates: List<List<FileHit>>,
    val categoryBytes: Map<String, Long>,
    val totalScannedFiles: Long,
    val totalScannedBytes: Long,
    val truncated: Boolean,
)

internal object StorageScanStore {
    private val plans = ConcurrentHashMap<String, ScanPlan>()
    fun put(plan: ScanPlan) {
        plans[plan.id] = plan
        if (plans.size > 8) {
            plans.entries.sortedBy { it.value.createdAt }.take(plans.size - 8).forEach { plans.remove(it.key) }
        }
    }
    fun get(id: String): ScanPlan? = plans[id]
    fun latest(): ScanPlan? = plans.values.maxByOrNull { it.createdAt }
}

internal data class StorageSnapshot(
    val id: String,
    val label: String,
    val createdAt: Long,
    val total: Long,
    val used: Long,
    val avail: Long,
    val categoryBytes: Map<String, Long> = emptyMap(),
    val note: String = "",
)

internal data class CleanupSession(
    val id: String,
    val before: StorageSnapshot,
    val after: StorageSnapshot? = null,
    val deletedFiles: Int = 0,
    val freedReported: Long = 0L,
    val failed: Int = 0,
    val dryRun: Boolean = false,
)

internal object StorageSnapshotStore {
    private val snaps = ConcurrentHashMap<String, StorageSnapshot>()
    private val sessions = ConcurrentHashMap<String, CleanupSession>()
    @Volatile var lastBeforeId: String? = null
    @Volatile var lastAfterId: String? = null
    @Volatile var lastSessionId: String? = null

    fun put(snap: StorageSnapshot) {
        snaps[snap.id] = snap
        if (snaps.size > 16) {
            snaps.entries.sortedBy { it.value.createdAt }.take(snaps.size - 16).forEach { snaps.remove(it.key) }
        }
    }

    fun get(id: String): StorageSnapshot? = snaps[id]
    fun latest(): StorageSnapshot? = snaps.values.maxByOrNull { it.createdAt }

    fun capture(label: String, categoryBytes: Map<String, Long> = emptyMap(), note: String = ""): StorageSnapshot {
        val (total, used, avail) = StorageScanner.volumeStats(StorageScanner.externalRoot())
        val snap = StorageSnapshot(
            id = "snap-" + UUID.randomUUID().toString().take(8),
            label = label,
            createdAt = System.currentTimeMillis(),
            total = total,
            used = used,
            avail = avail,
            categoryBytes = categoryBytes,
            note = note,
        )
        put(snap)
        return snap
    }

    fun putSession(session: CleanupSession) {
        sessions[session.id] = session
        lastSessionId = session.id
        if (sessions.size > 8) {
            sessions.entries.sortedBy { it.value.before.createdAt }.take(sessions.size - 8)
                .forEach { sessions.remove(it.key) }
        }
    }

    fun getSession(id: String): CleanupSession? = sessions[id]
    fun latestSession(): CleanupSession? = lastSessionId?.let { sessions[it] } ?: sessions.values.maxByOrNull { it.before.createdAt }

    fun compareData(
        before: StorageSnapshot,
        after: StorageSnapshot,
        deletedFiles: Int = 0,
        freedReported: Long = 0L,
        failed: Int = 0,
    ): String {
        val usedDelta = after.used - before.used
        val availDelta = after.avail - before.avail
        val usedRatioB = if (before.total > 0) before.used.toDouble() / before.total else 0.0
        val usedRatioA = if (after.total > 0) after.used.toDouble() / after.total else 0.0
        return buildString {
            appendLine("before:")
            appendLine("  snapshotId: ${before.id}")
            appendLine("  label: ${before.label}")
            appendLine("  totalBytes: ${before.total}")
            appendLine("  usedBytes: ${before.used}")
            appendLine("  availBytes: ${before.avail}")
            appendLine("  usedHuman: ${StorageScanner.formatBytes(before.used)}")
            appendLine("  availHuman: ${StorageScanner.formatBytes(before.avail)}")
            appendLine("  usedRatio: ${"%.4f".format(usedRatioB)}")
            appendLine("after:")
            appendLine("  snapshotId: ${after.id}")
            appendLine("  label: ${after.label}")
            appendLine("  totalBytes: ${after.total}")
            appendLine("  usedBytes: ${after.used}")
            appendLine("  availBytes: ${after.avail}")
            appendLine("  usedHuman: ${StorageScanner.formatBytes(after.used)}")
            appendLine("  availHuman: ${StorageScanner.formatBytes(after.avail)}")
            appendLine("  usedRatio: ${"%.4f".format(usedRatioA)}")
            appendLine("delta:")
            appendLine("  usedBytes: $usedDelta")
            appendLine("  usedHuman: ${StorageScanner.formatBytesSigned(usedDelta)}")
            appendLine("  availBytes: $availDelta")
            appendLine("  availHuman: ${StorageScanner.formatBytesSigned(availDelta)}")
            appendLine("deletedFiles: $deletedFiles")
            appendLine("failed: $failed")
            appendLine("freedByToolBytes: $freedReported")
            appendLine("freedByToolHuman: ${StorageScanner.formatBytes(freedReported)}")
        }.trimEnd()
    }
}

private object StorageScanner {
    private val SKIP_DIR_NAMES = setOf(
        ".", "..", "proc", "sys", "dev", "apex", "acct", "cfg",
    )
    private val SENSITIVE_HINTS = listOf(
        "/DCIM/", "/Pictures/", "/Movies/", "/Music/", "/Documents/",
        "/Android/media/", "/WhatsApp/", "/Telegram/", "/WeChat/", "/MicroMsg/",
        "/Download/Telegram/", "/Download/WhatsApp/",
    )

    fun externalRoot(): File =
        Environment.getExternalStorageDirectory() ?: File("/sdcard")

    fun defaultRoots(context: Context): List<File> {
        val root = externalRoot()
        val list = mutableListOf(
            root,
            File(root, "Download"),
            File(root, "Downloads"),
            File(root, "Android/data"),
            File(root, "Android/obb"),
            context.cacheDir,
            context.codeCacheDir,
            context.externalCacheDir,
            context.filesDir,
        )
        context.externalCacheDirs?.forEach { if (it != null) list += it }
        context.externalMediaDirs?.forEach { if (it != null) list += it }
        return list.filterNotNull().filter { it.exists() }.distinctBy { it.absolutePath }
    }

    fun hasPermission(): Boolean = hasAllFilesAccess()

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / 1024.0.pow(exp.toDouble())
        return if (exp == 0) "$bytes B" else String.format("%.2f %s", value, units[exp])
    }

    fun formatBytesSigned(bytes: Long): String {
        return when {
            bytes > 0L -> "+" + formatBytes(bytes)
            bytes < 0L -> "-" + formatBytes(-bytes)
            else -> "0 B"
        }
    }

    fun categoryOf(path: String): String {
        val p = path.replace('\\', '/')
        val lower = p.lowercase()
        return when {
            lower.contains("/dcim/") || lower.contains("/pictures/") -> "photos"
            lower.contains("/movies/") || lower.contains("/video") -> "videos"
            lower.contains("/music/") || lower.contains("/audio") -> "audio"
            lower.contains("/download") -> "download"
            lower.contains("/android/data/") -> "android_data"
            lower.contains("/android/obb/") -> "obb"
            lower.contains("/android/media/") -> "android_media"
            lower.contains("/documents/") -> "documents"
            lower.contains("/cache") || lower.endsWith("/cache") -> "cache"
            lower.contains("/.thumbnails") -> "thumbnails"
            else -> "other"
        }
    }

    fun tagsOf(file: File, kind: String): String {
        val name = file.name.lowercase()
        val path = file.absolutePath.lowercase()
        val tags = linkedSetOf<String>()
        tags += kind
        when {
            kind == "apk" || name.endsWith(".apk") -> tags += "install_package"
            kind == "tmp" || name.endsWith(".tmp") || name.endsWith(".temp") || name.endsWith(".part") || name.endsWith(".crdownload") -> tags += "temp"
            kind == "log" || name.endsWith(".log") -> tags += "log"
        }
        if (path.contains(".thumbnails")) tags += "thumbnail"
        if (path.contains("/cache") || path.contains("/code_cache")) tags += "cache"
        if (path.contains("/download")) tags += "download"
        if (path.contains("/android/obb/")) tags += "obb"
        if (path.contains("/android/data/")) tags += "app_data"
        if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z")) tags += "archive"
        if (isSensitive(file.absolutePath)) tags += "sensitive"
        if (isJunk(file)) tags += "junk_candidate"
        return tags.joinToString(",")
    }

    fun kindOf(file: File): String {
        val n = file.name.lowercase()
        return when {
            n.endsWith(".apk") -> "apk"
            n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".part") || n.endsWith(".crdownload") -> "tmp"
            n.endsWith(".log") -> "log"
            n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov") || n.endsWith(".webm") -> "video"
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".heic") || n.endsWith(".webp") || n.endsWith(".gif") -> "image"
            n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".tar") || n.endsWith(".gz") -> "archive"
            n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".wav") -> "audio"
            else -> "file"
        }
    }

    fun isSensitive(path: String): Boolean {
        val p = "/${path.replace('\\', '/').trim('/')}/"
        return SENSITIVE_HINTS.any { p.contains(it, ignoreCase = true) }
    }

    fun isJunk(file: File): Boolean {
        if (!file.isFile) return false
        val n = file.name.lowercase()
        val path = file.absolutePath.lowercase()
        if (n.endsWith(".apk")) return true
        if (n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".part") || n.endsWith(".crdownload") || n.endsWith(".download")) return true
        if (n.endsWith(".log") && file.length() > 64 * 1024) return true
        if (path.contains("/.thumbnails/")) return true
        if (path.contains("/cache/") || path.endsWith("/cache") || path.contains("/code_cache/")) return true
        if (n == ".ds_store" || n == "thumbs.db" || n.endsWith(".nomedia") && file.length() == 0L) return true
        if (n.startsWith("._")) return true
        return false
    }

    data class WalkResult(
        val files: List<File>,
        val categoryBytes: MutableMap<String, Long>,
        val totalFiles: Long,
        val totalBytes: Long,
        val truncated: Boolean,
    )

    fun walk(
        roots: List<File>,
        maxFiles: Int,
        minSize: Long = 0L,
        onFile: ((File) -> Boolean)? = null,
    ): WalkResult {
        val out = ArrayList<File>(minOf(maxFiles, 4096))
        val cat = linkedMapOf<String, Long>()
        var totalFiles = 0L
        var totalBytes = 0L
        var truncated = false
        val visited = HashSet<String>()

        fun walkDir(dir: File, depth: Int) {
            if (truncated || depth > 18) return
            val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visited.add(key)) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (truncated) return
                if (child.isDirectory) {
                    val name = child.name
                    if (name in SKIP_DIR_NAMES) continue
                    if (name.startsWith(".") && name !in setOf(".thumbnails", ".cache", ".tmp")) {
                        if (name != ".thumbnails" && name != ".cache" && !name.contains("cache")) {
                            if (!name.contains("tmp") && !name.contains("temp")) continue
                        }
                    }
                    walkDir(child, depth + 1)
                } else if (child.isFile) {
                    totalFiles++
                    val size = runCatching { child.length() }.getOrDefault(0L)
                    totalBytes += size
                    val catName = categoryOf(child.absolutePath)
                    cat[catName] = (cat[catName] ?: 0L) + size
                    if (size < minSize) continue
                    if (onFile != null && !onFile(child)) continue
                    if (out.size < maxFiles) {
                        out += child
                    } else {
                        truncated = true
                    }
                }
            }
        }

        for (root in roots) {
            if (!root.exists()) continue
            if (root.isFile) {
                totalFiles++
                val size = root.length()
                totalBytes += size
                cat[categoryOf(root.absolutePath)] = (cat[categoryOf(root.absolutePath)] ?: 0L) + size
                if (size >= minSize && (onFile == null || onFile(root)) && out.size < maxFiles) out += root
            } else {
                walkDir(root, 0)
            }
        }
        return WalkResult(out, cat, totalFiles, totalBytes, truncated)
    }

    fun toHit(file: File): FileHit {
        val size = runCatching { file.length() }.getOrDefault(0L)
        val kind = kindOf(file)
        return FileHit(
            path = file.absolutePath,
            size = size,
            kind = kind,
            tags = tagsOf(file, kind),
            mtime = file.lastModified(),
            category = categoryOf(file.absolutePath),
        )
    }

    fun volumeStats(root: File): Triple<Long, Long, Long> {
        return runCatching {
            val stat = StatFs(root.absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            val used = (total - avail).coerceAtLeast(0L)
            Triple(total, used, avail)
        }.getOrDefault(Triple(0L, 0L, 0L))
    }

    fun categoryData(categoryBytes: Map<String, Long>, baseTotal: Long = 0L): String {
        if (categoryBytes.isEmpty()) return "categories: []"
        val total = if (baseTotal > 0L) baseTotal else categoryBytes.values.sum().coerceAtLeast(1L)
        val sorted = categoryBytes.entries.sortedByDescending { it.value }
        return buildString {
            appendLine("categories:")
            for ((name, bytes) in sorted.take(20)) {
                val ratio = bytes.toDouble() / total.toDouble()
                appendLine("- name: $name")
                appendLine("  bytes: $bytes")
                appendLine("  human: ${formatBytes(bytes)}")
                appendLine("  ratio: ${"%.4f".format(ratio)}")
            }
        }.trimEnd()
    }

    fun hitsData(hits: List<FileHit>, key: String = "files"): String {
        if (hits.isEmpty()) return "$key: []"
        return buildString {
            appendLine("$key:")
            hits.forEach { h ->
                appendLine("- path: ${h.path}")
                appendLine("  bytes: ${h.size}")
                appendLine("  human: ${formatBytes(h.size)}")
                appendLine("  kind: ${h.kind}")
                appendLine("  category: ${h.category}")
                appendLine("  tags: ${h.tags}")
                appendLine("  mtime: ${h.mtime}")
                appendLine("  sensitive: ${isSensitive(h.path)}")
            }
        }.trimEnd()
    }

    fun md5(file: File, maxBytes: Long = 2L * 1024 * 1024): String {
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var remaining = maxBytes
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    remaining -= n
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("err")
    }
}

private class StorageOverviewTool(private val context: Context) : Tool {
    override val name = "storage_overview"
    override val description =
        "Return structured device storage stats: total/used/free, permission, top-level folder sizes. No charts. Model renders text UI."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val root = StorageScanner.externalRoot()
        val (total, used, avail) = StorageScanner.volumeStats(root)
        val ratio = if (total > 0) used.toDouble() / total.toDouble() else 0.0
        val children = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val size = roughDirSize(dir, maxFiles = 8000)
            if (size <= 0L) null else dir.name to size
        }?.sortedByDescending { it.second }?.take(15).orEmpty()
        val catMap = children.toMap()
        val snap = StorageSnapshotStore.capture("overview", categoryBytes = catMap)
        StorageSnapshotStore.lastBeforeId = snap.id
        val text = buildString {
            appendLine("type: storage_overview")
            appendLine("snapshotId: ${snap.id}")
            appendLine("root: ${root.absolutePath}")
            appendLine("allFilesAccess: ${StorageScanner.hasPermission()}")
            appendLine("runtime: Android ${Build.VERSION.RELEASE}")
            appendLine("api: ${Build.VERSION.SDK_INT}")
            appendLine("totalBytes: $total")
            appendLine("usedBytes: $used")
            appendLine("availBytes: $avail")
            appendLine("totalHuman: ${StorageScanner.formatBytes(total)}")
            appendLine("usedHuman: ${StorageScanner.formatBytes(used)}")
            appendLine("availHuman: ${StorageScanner.formatBytes(avail)}")
            appendLine("usedRatio: ${"%.4f".format(ratio)}")
            if (children.isNotEmpty()) {
                appendLine(StorageScanner.categoryData(catMap, total))
            } else {
                appendLine("categories: []")
            }
            appendLine("uiHint: Render Chinese text UI from numbers; explain space pressure dynamically; do not dump raw keys as the final user answer.")
        }
        ToolResult(text)
    }

    private fun roughDirSize(dir: File, maxFiles: Int): Long {
        var total = 0L
        var count = 0
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty() && count < maxFiles) {
            val d = stack.removeFirst()
            val list = d.listFiles() ?: continue
            for (f in list) {
                if (count >= maxFiles) break
                if (f.isDirectory) stack.addLast(f)
                else {
                    total += runCatching { f.length() }.getOrDefault(0L)
                    count++
                }
            }
        }
        return total
    }
}

private class StorageScanTool(private val context: Context) : Tool {
    override val name = "storage_scan"
    override val description =
        "Full accessible-disk scan. Returns planId and structured lists: large/junk/duplicates/categories. No charts. Does NOT delete."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("roots") {
                put("type", "array")
                put("description", "Optional absolute roots. Default = external storage + app caches.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("minLargeBytes") {
                put("type", "integer")
                put("description", "Large-file threshold in bytes. Default 50MB.")
            }
            putJsonObject("maxFiles") {
                put("type", "integer")
                put("description", "Max files to materialize in result lists. Default 400.")
            }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val rootsArg = args["roots"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }
        val roots = if (rootsArg.isNullOrEmpty()) {
            StorageScanner.defaultRoots(context)
        } else {
            rootsArg.map { File(it) }.filter { it.exists() }
        }
        if (roots.isEmpty()) return@withContext ToolResult("no readable roots", isError = true)
        val minLarge = args.long("minLargeBytes") ?: (50L * 1024 * 1024)
        val maxFiles = (args.int("maxFiles") ?: 400).coerceIn(50, 2000)

        val walk = StorageScanner.walk(roots, maxFiles = 50_000, minSize = 0L)
        val allSample = walk.files
        val large = allSample
            .asSequence()
            .filter { it.isFile && it.length() >= minLarge }
            .sortedByDescending { it.length() }
            .take(maxFiles / 2)
            .map { StorageScanner.toHit(it) }
            .toList()
        val junk = allSample
            .asSequence()
            .filter { StorageScanner.isJunk(it) }
            .sortedByDescending { it.length() }
            .take(maxFiles / 2)
            .map { StorageScanner.toHit(it) }
            .toList()

        val candidates = allSample
            .filter { it.isFile && it.length() >= 1024 * 1024 }
            .sortedByDescending { it.length() }
            .take(1500)
        val bySize = candidates.groupBy { it.length() }.filter { it.value.size >= 2 }
        val dupGroups = mutableListOf<List<FileHit>>()
        for ((_, group) in bySize.entries.sortedByDescending { it.key }.take(80)) {
            val hashed = group.take(12).groupBy { StorageScanner.md5(it) }.filter { it.value.size >= 2 }
            for ((_, files) in hashed) {
                dupGroups += files.map { StorageScanner.toHit(it) }
                if (dupGroups.size >= 30) break
            }
            if (dupGroups.size >= 30) break
        }

        val plan = ScanPlan(
            id = "scan-" + UUID.randomUUID().toString().take(8),
            createdAt = System.currentTimeMillis(),
            roots = roots.map { it.absolutePath },
            large = large,
            junk = junk,
            duplicates = dupGroups,
            categoryBytes = walk.categoryBytes.toMap(),
            totalScannedFiles = walk.totalFiles,
            totalScannedBytes = walk.totalBytes,
            truncated = walk.truncated,
        )
        StorageScanStore.put(plan)
        val snap = StorageSnapshotStore.capture("scan", categoryBytes = plan.categoryBytes, note = plan.id)
        StorageSnapshotStore.lastBeforeId = snap.id

        val junkBytes = junk.sumOf { it.size }
        val largeBytes = large.sumOf { it.size }
        val dupBytes = dupGroups.sumOf { g -> g.drop(1).sumOf { it.size } }
        val (total, used, avail) = StorageScanner.volumeStats(StorageScanner.externalRoot())
        val usedRatio = if (total > 0) used.toDouble() / total else 0.0

        val text = buildString {
            appendLine("type: storage_scan")
            appendLine("planId: ${plan.id}")
            appendLine("snapshotId: ${snap.id}")
            appendLine("permission: ${if (StorageScanner.hasPermission()) "all_files" else "limited"}")
            appendLine("roots:")
            plan.roots.forEach { appendLine("- $it") }
            appendLine("scannedFiles: ${plan.totalScannedFiles}")
            appendLine("scannedBytes: ${plan.totalScannedBytes}")
            appendLine("scannedHuman: ${StorageScanner.formatBytes(plan.totalScannedBytes)}")
            appendLine("truncated: ${plan.truncated}")
            appendLine("totalBytes: $total")
            appendLine("usedBytes: $used")
            appendLine("availBytes: $avail")
            appendLine("totalHuman: ${StorageScanner.formatBytes(total)}")
            appendLine("usedHuman: ${StorageScanner.formatBytes(used)}")
            appendLine("availHuman: ${StorageScanner.formatBytes(avail)}")
            appendLine("usedRatio: ${"%.4f".format(usedRatio)}")
            appendLine("junkCount: ${junk.size}")
            appendLine("junkBytes: $junkBytes")
            appendLine("junkHuman: ${StorageScanner.formatBytes(junkBytes)}")
            appendLine("largeCount: ${large.size}")
            appendLine("largeBytes: $largeBytes")
            appendLine("largeHuman: ${StorageScanner.formatBytes(largeBytes)}")
            appendLine("duplicateGroups: ${dupGroups.size}")
            appendLine("duplicateReclaimableBytes: $dupBytes")
            appendLine("duplicateReclaimableHuman: ${StorageScanner.formatBytes(dupBytes)}")
            appendLine(StorageScanner.categoryData(plan.categoryBytes, plan.totalScannedBytes.coerceAtLeast(1L)))
            appendLine(StorageScanner.hitsData(junk.take(25), "junkFiles"))
            appendLine(StorageScanner.hitsData(large.take(20), "largeFiles"))
            if (dupGroups.isEmpty()) {
                appendLine("duplicates: []")
            } else {
                appendLine("duplicates:")
                dupGroups.take(10).forEachIndexed { i, g ->
                    appendLine("- group: ${i + 1}")
                    appendLine("  copies: ${g.size}")
                    appendLine("  bytesEach: ${g.first().size}")
                    appendLine("  humanEach: ${StorageScanner.formatBytes(g.first().size)}")
                    appendLine("  reclaimableBytes: ${g.drop(1).sumOf { it.size }}")
                    appendLine("  paths:")
                    g.forEach { appendLine("  - ${it.path}") }
                }
            }
            appendLine("uiHint: Render Chinese text UI; explain tags/purposes dynamically; ask what to delete; never paste this dump raw to the user.")
        }
        ToolResult(text)
    }
}

private class StorageFindLargeTool(private val context: Context) : Tool {
    override val name = "storage_find_large"
    override val description = "Find largest files. Returns structured path/size/kind/tags list. No charts."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("minBytes") { put("type", "integer") }
            putJsonObject("limit") { put("type", "integer") }
            putJsonObject("root") { put("type", "string") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val min = args.long("minBytes") ?: (80L * 1024 * 1024)
        val limit = (args.int("limit") ?: 40).coerceIn(5, 200)
        val roots = args.str("root")?.let { listOf(File(it)) }?.filter { it.exists() }
            ?: StorageScanner.defaultRoots(context)
        val walk = StorageScanner.walk(roots, maxFiles = 20_000, minSize = min)
        val hits = walk.files.sortedByDescending { it.length() }.take(limit).map { StorageScanner.toHit(it) }
        val total = hits.sumOf { it.size }
        ToolResult(
            buildString {
                appendLine("type: storage_find_large")
                appendLine("thresholdBytes: $min")
                appendLine("thresholdHuman: ${StorageScanner.formatBytes(min)}")
                appendLine("hits: ${hits.size}")
                appendLine("totalBytes: $total")
                appendLine("totalHuman: ${StorageScanner.formatBytes(total)}")
                appendLine(StorageScanner.hitsData(hits, "files"))
                appendLine("uiHint: Render ranked Chinese text UI; explain each file dynamically; ask whether to delete.")
            },
        )
    }
}

private class StorageFindJunkTool(private val context: Context) : Tool {
    override val name = "storage_find_junk"
    override val description =
        "Find junk candidates: APKs, temp, caches, thumbnails, logs. Download/*.apk included. Structured data only."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("limit") { put("type", "integer") }
            putJsonObject("root") { put("type", "string") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val limit = (args.int("limit") ?: 80).coerceIn(10, 500)
        val roots = args.str("root")?.let { listOf(File(it)) }?.filter { it.exists() }
            ?: StorageScanner.defaultRoots(context)
        val walk = StorageScanner.walk(roots, maxFiles = 30_000, minSize = 0L) { StorageScanner.isJunk(it) }
        val hits = walk.files.sortedByDescending { it.length() }.take(limit).map { StorageScanner.toHit(it) }
        val total = hits.sumOf { it.size }
        val byKind = hits.groupBy { it.kind }.mapValues { e -> e.value.sumOf { it.size } }
        ToolResult(
            buildString {
                appendLine("type: storage_find_junk")
                appendLine("candidates: ${hits.size}")
                appendLine("reclaimableBytes: $total")
                appendLine("reclaimableHuman: ${StorageScanner.formatBytes(total)}")
                appendLine(StorageScanner.categoryData(byKind, total.coerceAtLeast(1L)))
                appendLine(StorageScanner.hitsData(hits, "files"))
                appendLine("uiHint: Render Chinese junk summary; explain tags dynamically; suggest safe default packages then ask confirmation.")
            },
        )
    }
}

private class StorageFindDuplicatesTool(private val context: Context) : Tool {
    override val name = "storage_find_duplicates"
    override val description = "Find duplicate file groups by size + partial hash. Structured groups only."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("minBytes") { put("type", "integer") }
            putJsonObject("limitGroups") { put("type", "integer") }
            putJsonObject("root") { put("type", "string") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val min = args.long("minBytes") ?: (1L * 1024 * 1024)
        val limitGroups = (args.int("limitGroups") ?: 20).coerceIn(3, 50)
        val roots = args.str("root")?.let { listOf(File(it)) }?.filter { it.exists() }
            ?: StorageScanner.defaultRoots(context)
        val walk = StorageScanner.walk(roots, maxFiles = 20_000, minSize = min)
        val bySize = walk.files.groupBy { it.length() }.filter { it.value.size >= 2 }
        val groups = mutableListOf<List<FileHit>>()
        for ((_, files) in bySize.entries.sortedByDescending { it.key }.take(100)) {
            val hashed = files.take(16).groupBy { StorageScanner.md5(it) }.filter { it.value.size >= 2 }
            for ((_, g) in hashed) {
                groups += g.map { StorageScanner.toHit(it) }
                if (groups.size >= limitGroups) break
            }
            if (groups.size >= limitGroups) break
        }
        val save = groups.sumOf { g -> g.drop(1).sumOf { it.size } }
        ToolResult(
            buildString {
                appendLine("type: storage_find_duplicates")
                appendLine("groups: ${groups.size}")
                appendLine("reclaimableIfKeepOneBytes: $save")
                appendLine("reclaimableIfKeepOneHuman: ${StorageScanner.formatBytes(save)}")
                if (groups.isEmpty()) {
                    appendLine("duplicates: []")
                } else {
                    appendLine("duplicates:")
                    groups.forEachIndexed { i, g ->
                        appendLine("- group: ${i + 1}")
                        appendLine("  copies: ${g.size}")
                        appendLine("  bytesEach: ${g.first().size}")
                        appendLine("  humanEach: ${StorageScanner.formatBytes(g.first().size)}")
                        appendLine("  reclaimableBytes: ${g.drop(1).sumOf { it.size }}")
                        appendLine("  paths:")
                        g.forEachIndexed { j, h ->
                            appendLine("  - path: ${h.path}")
                            appendLine("    suggestedKeep: ${j == 0}")
                            appendLine("    tags: ${h.tags}")
                        }
                    }
                }
                appendLine("uiHint: Render Chinese duplicate groups; warn partial-hash confidence; ask which copies to keep.")
            },
        )
    }
}

private class StoragePreviewDeleteTool(private val context: Context) : Tool {
    override val name = "storage_preview_delete"
    override val description =
        "Preview delete plan from planId/categories/paths. Returns confirmToken and structured file list. Must show to user before storage_clean."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("planId") { put("type", "string") }
            putJsonObject("categories") {
                put("type", "array")
                put("description", "junk | large | duplicates | apk | tmp | cache | log | all_junk")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("paths") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("includeSensitive") {
                put("type", "boolean")
                put("description", "Include sensitive paths (DCIM/chat media). Default false.")
            }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val plan = args.str("planId")?.let { StorageScanStore.get(it) } ?: StorageScanStore.latest()
            ?: return@withContext ToolResult("no scan plan; run storage_scan first", isError = true)
        val cats = args["categories"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }.orEmpty().toSet()
        val extraPaths = args["paths"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val includeSensitive = args.bool("includeSensitive") == true

        val selected = linkedMapOf<String, FileHit>()
        fun addAll(list: List<FileHit>) {
            list.forEach { selected[it.path] = it }
        }
        if (cats.isEmpty() && extraPaths.isEmpty()) {
            addAll(plan.junk)
        } else {
            if ("junk" in cats || "all_junk" in cats) addAll(plan.junk)
            if ("large" in cats) addAll(plan.large)
            if ("duplicates" in cats) plan.duplicates.forEach { g -> addAll(g.drop(1)) }
            if ("apk" in cats) addAll(plan.junk.filter { it.kind == "apk" })
            if ("tmp" in cats) addAll(plan.junk.filter { it.kind == "tmp" })
            if ("cache" in cats) addAll(plan.junk.filter { it.category == "cache" || it.path.contains("/cache", true) })
            if ("log" in cats) addAll(plan.junk.filter { it.kind == "log" })
        }
        extraPaths.forEach { p ->
            val f = File(p)
            if (f.isFile) selected[p] = StorageScanner.toHit(f)
        }

        val finalList = selected.values.filter { h ->
            includeSensitive || !StorageScanner.isSensitive(h.path)
        }.sortedByDescending { it.size }

        val blocked = selected.values.filter { StorageScanner.isSensitive(it.path) && !includeSensitive }
        val total = finalList.sumOf { it.size }
        val confirmToken = "confirm-" + plan.id + "-" + Integer.toHexString(finalList.map { it.path }.sorted().hashCode())

        ConfirmTokens.put(confirmToken, finalList.map { it.path })

        ToolResult(
            buildString {
                appendLine("type: storage_preview_delete")
                appendLine("planId: ${plan.id}")
                appendLine("confirmToken: $confirmToken")
                appendLine("files: ${finalList.size}")
                appendLine("reclaimableBytes: $total")
                appendLine("reclaimableHuman: ${StorageScanner.formatBytes(total)}")
                appendLine("includeSensitive: $includeSensitive")
                appendLine("excludedSensitiveCount: ${blocked.size}")
                appendLine(StorageScanner.hitsData(finalList.take(60), "willDelete"))
                if (finalList.size > 60) {
                    appendLine("omittedFiles: ${finalList.size - 60}")
                }
                appendLine("uiHint: Present delete preview in Chinese text UI; require explicit user confirm; then storage_clean(confirmToken, userConfirmed=true).")
            },
        )
    }
}

private object ConfirmTokens {
    private val map = ConcurrentHashMap<String, List<String>>()
    fun put(token: String, paths: List<String>) {
        map[token] = paths
    }
    fun get(token: String): List<String>? = map[token]
    fun remove(token: String) {
        map.remove(token)
    }
}

private class StorageCleanTool(private val context: Context) : Tool {
    override val name = "storage_clean"
    override val description =
        "Delete files from a previewed plan. Requires userConfirmed=true and confirmToken. Returns structured before/after stats, not a chart."
    override val risk = ToolRisk.WRITE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("confirmToken") { put("type", "string") }
            putJsonObject("userConfirmed") {
                put("type", "boolean")
                put("description", "Must be true only after the user explicitly confirmed in chat.")
            }
            putJsonObject("paths") {
                put("type", "array")
                put("description", "Optional explicit subset of paths from the preview.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("dryRun") { put("type", "boolean") }
        }
        putJsonArray("required") { add("confirmToken"); add("userConfirmed") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val confirmed = args.bool("userConfirmed") == true
        if (!confirmed) {
            return@withContext ToolResult("userConfirmed must be true after the user explicitly confirms in chat", isError = true)
        }
        val token = args.str("confirmToken")?.trim().orEmpty()
        if (token.isBlank()) return@withContext ToolResult("confirmToken required", isError = true)
        val planned = ConfirmTokens.get(token)
            ?: return@withContext ToolResult("confirmToken invalid or expired; run storage_preview_delete again", isError = true)
        val subset = args["paths"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
        val targets = if (subset.isNullOrEmpty()) planned else planned.filter { it in subset }
        if (targets.isEmpty()) return@withContext ToolResult("no files to delete", isError = true)
        val dry = args.bool("dryRun") == true

        var deleted = 0
        var freed = 0L
        var failed = 0
        val errors = mutableListOf<String>()
        for (path in targets) {
            val f = File(path)
            if (!f.exists()) continue
            if (!f.isFile) {
                failed++
                errors += "skip non-file: $path"
                continue
            }
            val size = f.length()
            if (dry) {
                deleted++
                freed += size
                continue
            }
            val ok = runCatching { f.delete() }.getOrDefault(false)
            if (ok) {
                deleted++
                freed += size
            } else {
                failed++
                errors += "failed: $path"
            }
        }
        if (!dry && deleted > 0) ConfirmTokens.remove(token)

        val before = StorageSnapshotStore.lastBeforeId?.let { StorageSnapshotStore.get(it) }
            ?: StorageSnapshotStore.latest()
            ?: StorageSnapshotStore.capture("before-clean")
        val after = StorageSnapshotStore.capture("after-clean")
        StorageSnapshotStore.lastAfterId = after.id
        val session = CleanupSession(
            id = "clean-" + UUID.randomUUID().toString().take(8),
            before = before,
            after = after,
            deletedFiles = deleted,
            freedReported = freed,
            failed = failed,
            dryRun = dry,
        )
        StorageSnapshotStore.putSession(session)

        ToolResult(
            buildString {
                appendLine("type: storage_clean")
                appendLine("sessionId: ${session.id}")
                appendLine("dryRun: $dry")
                appendLine("deletedFiles: $deleted")
                appendLine("failed: $failed")
                appendLine("freedByToolBytes: $freed")
                appendLine("freedByToolHuman: ${StorageScanner.formatBytes(freed)}")
                appendLine(StorageSnapshotStore.compareData(before, after, deleted, freed, failed))
                if (errors.isNotEmpty()) {
                    appendLine("errors:")
                    errors.take(20).forEach { appendLine("- $it") }
                } else {
                    appendLine("errors: []")
                }
                appendLine("uiHint: Render Chinese before/after text UI from numbers; explain outcome; suggest next steps if still full.")
            },
            isError = deleted == 0 && failed > 0,
        )
    }
}

private class StorageAppUsageTool(private val context: Context) : Tool {
    override val name = "storage_app_usage"
    override val description =
        "Rank packages by external storage under Android/data, obb, media. Structured ranking only."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("limit") { put("type", "integer") }
            putJsonObject("includeObb") { put("type", "boolean") }
            putJsonObject("minBytes") { put("type", "integer") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val limit = (args.int("limit") ?: 30).coerceIn(5, 100)
        val includeObb = args.bool("includeObb") != false
        val minBytes = args.long("minBytes") ?: (10L * 1024 * 1024)
        val root = StorageScanner.externalRoot()
        val dataRoot = File(root, "Android/data")
        val obbRoot = File(root, "Android/obb")
        val mediaRoot = File(root, "Android/media")
        val pm = context.packageManager

        data class AppUsage(val pkg: String, val label: String, val data: Long, val obb: Long, val media: Long) {
            val total: Long get() = data + obb + media
        }

        fun dirSize(dir: File, maxFiles: Int = 12_000): Long {
            if (!dir.exists()) return 0L
            var total = 0L
            var count = 0
            val stack = ArrayDeque<File>()
            stack.add(dir)
            while (stack.isNotEmpty() && count < maxFiles) {
                val d = stack.removeFirst()
                val list = d.listFiles() ?: continue
                for (f in list) {
                    if (count >= maxFiles) break
                    if (f.isDirectory) stack.addLast(f)
                    else {
                        total += runCatching { f.length() }.getOrDefault(0L)
                        count++
                    }
                }
            }
            return total
        }

        val pkgs = linkedSetOf<String>()
        if (dataRoot.isDirectory) dataRoot.list()?.forEach { pkgs += it }
        if (includeObb && obbRoot.isDirectory) obbRoot.list()?.forEach { pkgs += it }
        if (mediaRoot.isDirectory) mediaRoot.list()?.forEach { pkgs += it }

        val usages = ArrayList<AppUsage>()
        for (pkg in pkgs) {
            val data = dirSize(File(dataRoot, pkg))
            val obb = if (includeObb) dirSize(File(obbRoot, pkg)) else 0L
            val media = dirSize(File(mediaRoot, pkg))
            if (data + obb + media < minBytes) continue
            val label = runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            }.getOrDefault(pkg)
            usages += AppUsage(pkg, label, data, obb, media)
        }
        val ranked = usages.sortedByDescending { it.total }.take(limit)
        val sum = ranked.sumOf { it.total }.coerceAtLeast(1L)
        val text = buildString {
            appendLine("type: storage_app_usage")
            appendLine("roots: Android/data" + (if (includeObb) ",Android/obb" else "") + ",Android/media")
            appendLine("permission: ${if (StorageScanner.hasPermission()) "all_files" else "limited"}")
            appendLine("thresholdBytes: $minBytes")
            appendLine("thresholdHuman: ${StorageScanner.formatBytes(minBytes)}")
            appendLine("shown: ${ranked.size}")
            appendLine("apps:")
            if (ranked.isEmpty()) {
                appendLine("[]")
            } else {
                ranked.forEachIndexed { i, u ->
                    val ratio = u.total.toDouble() / sum.toDouble()
                    appendLine("- rank: ${i + 1}")
                    appendLine("  label: ${u.label}")
                    appendLine("  package: ${u.pkg}")
                    appendLine("  totalBytes: ${u.total}")
                    appendLine("  totalHuman: ${StorageScanner.formatBytes(u.total)}")
                    appendLine("  dataBytes: ${u.data}")
                    appendLine("  dataHuman: ${StorageScanner.formatBytes(u.data)}")
                    appendLine("  obbBytes: ${u.obb}")
                    appendLine("  obbHuman: ${StorageScanner.formatBytes(u.obb)}")
                    appendLine("  mediaBytes: ${u.media}")
                    appendLine("  mediaHuman: ${StorageScanner.formatBytes(u.media)}")
                    appendLine("  shareOfList: ${"%.4f".format(ratio)}")
                }
            }
            appendLine("uiHint: Render Chinese app ranking text UI; this is external storage only, not full system app size; suggest next cleanup targets.")
        }
        ToolResult(text)
    }
}

private class StorageCompareTool(private val context: Context) : Tool {
    override val name = "storage_compare"
    override val description =
        "Return structured before/after cleanup stats from session or snapshotIds. No chart cards."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") { put("type", "string") }
            putJsonObject("beforeSnapshotId") { put("type", "string") }
            putJsonObject("afterSnapshotId") { put("type", "string") }
            putJsonObject("recaptureAfter") {
                put("type", "boolean")
                put("description", "If true, take a fresh after snapshot now. Default false.")
            }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val session = args.str("sessionId")?.let { StorageSnapshotStore.getSession(it) }
            ?: StorageSnapshotStore.latestSession()
        val beforeId = args.str("beforeSnapshotId")
        val afterId = args.str("afterSnapshotId")
        val recapture = args.bool("recaptureAfter") == true

        val before = when {
            beforeId != null -> StorageSnapshotStore.get(beforeId)
            session != null -> session.before
            else -> StorageSnapshotStore.lastBeforeId?.let { StorageSnapshotStore.get(it) }
                ?: StorageSnapshotStore.latest()
        } ?: return@withContext ToolResult("no before snapshot; run storage_overview or storage_scan first", isError = true)

        val after = when {
            recapture -> StorageSnapshotStore.capture("compare-after")
            afterId != null -> StorageSnapshotStore.get(afterId)
            session?.after != null -> session.after
            StorageSnapshotStore.lastAfterId != null -> StorageSnapshotStore.get(StorageSnapshotStore.lastAfterId!!)
            else -> StorageSnapshotStore.capture("compare-after")
        } ?: return@withContext ToolResult("no after snapshot", isError = true)

        StorageSnapshotStore.lastAfterId = after.id
        val deleted = session?.deletedFiles ?: 0
        val freed = session?.freedReported ?: 0L
        val failed = session?.failed ?: 0
        ToolResult(
            buildString {
                appendLine("type: storage_compare")
                if (session != null) {
                    appendLine("sessionId: ${session.id}")
                    appendLine("dryRun: ${session.dryRun}")
                }
                appendLine(StorageSnapshotStore.compareData(before, after, deleted, freed, failed))
                appendLine("uiHint: Render Chinese before/after text UI; explain whether free space improved and what to do next.")
            },
        )
    }
}

private class StorageOpenSettingsTool(private val context: Context) : Tool {
    override val name = "storage_open_settings"
    override val description = "Open Android settings pages for All Files Access or app storage settings so the user can grant permissions."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("page") {
                put("type", "string")
                put("description", "all_files | app_storage | internal_storage")
            }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val page = args.str("page")?.lowercase() ?: "all_files"
        val app = context.applicationContext
        val intent = when (page) {
            "app_storage" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${app.packageName}")
            }
            "internal_storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${app.packageName}")
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${app.packageName}")
                    }
                }
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            app.startActivity(intent)
            ToolResult(
                buildString {
                    appendLine("type: storage_open_settings")
                    appendLine("page: $page")
                    appendLine("opened: true")
                    appendLine("hasAllFiles: ${hasAllFilesAccess()}")
                    appendLine("uiHint: Tell the user in Chinese which setting was opened and what to enable.")
                },
            )
        }.getOrElse {
            ToolResult("open settings failed: ${it.message}", isError = true)
        }
    }
}
