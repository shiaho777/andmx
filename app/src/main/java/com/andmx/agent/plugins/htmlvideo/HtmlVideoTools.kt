package com.andmx.agent.plugins.htmlvideo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.core.content.FileProvider
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.workspace.WorkspaceAccess
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
private fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

class HtmlVideoToolset(private val context: Context) {
    fun tools(): List<Tool> = listOf(
        HtmlVideoWorkspaceScanTool(context),
        HtmlVideoInitTool(context),
        HtmlVideoWriteTool(context),
        HtmlVideoBuildTool(context),
        HtmlVideoAttachAudioTool(context),
        HtmlVideoSynthAudioTool(context),
        HtmlVideoFetchAudioTool(context),
        HtmlVideoPreviewTool(context),
        HtmlVideoDeliverTool(context),
    )
}

private object HtmlVideoPaths {
    const val MARKER = "project.json"
    val AUDIO_EXT = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus")
    val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "svg")
    val VIDEO_EXT = setOf("mp4", "webm", "mov", "mkv")

    fun defaultProjectRel(slug: String): String = ".andmx-html-video/$slug"
}

private class HtmlVideoFs(context: Context) {
    val access = WorkspaceAccess(context)
    private val guest = GuestFs(ProotRuntime(context.applicationContext))

    fun cwd(): String = access.guestCwd()
    fun resolve(path: String): String = access.resolvePath(path)

    suspend fun exists(path: String): Boolean = access.exists(path)
    suspend fun isDir(path: String): Boolean = access.isDirectory(path)
    suspend fun readText(path: String, limit: Int = 512 * 1024): String = access.readText(path, limit)
    suspend fun writeText(path: String, content: String) = access.writeText(path, content)
    suspend fun list(path: String): List<String> = runCatching { access.list(path) }.getOrDefault(emptyList())

    suspend fun writeBytes(path: String, bytes: ByteArray) {
        val resolved = resolve(path)
        if (access.isRemote) {
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val res = access.executeShell(
                "mkdir -p $(dirname ${shq(resolved)}) && echo ${shq(b64)} | base64 -d > ${shq(resolved)} && echo OK",
            )
            if (!res.stdout.contains("OK")) error("remote writeBytes failed: ${res.stdout} ${res.stderr}")
            return
        }
        val f = guest.resolve(toGuest(resolved))
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    suspend fun hostFile(path: String): File? {
        if (access.isRemote) return null
        return runCatching { guest.resolve(toGuest(resolve(path))) }.getOrNull()
    }

    private fun toGuest(path: String): String {
        val p = path.trim()
        if (p.startsWith("/root")) return p
        val host = access.hostPath
        if (host != null && p.startsWith(host)) {
            val rel = p.removePrefix(host).trimStart('/')
            return if (rel.isEmpty()) "/root/project" else "/root/project/$rel"
        }
        if (p.startsWith("/")) return p
        return resolve(p)
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    suspend fun findProject(explicit: String?): String? {
        if (!explicit.isNullOrBlank()) {
            val p = resolve(explicit.trimEnd('/'))
            if (exists("$p/${HtmlVideoPaths.MARKER}")) return p
            if (exists(p) && p.endsWith(HtmlVideoPaths.MARKER)) return p.substringBeforeLast('/')
        }
        val root = cwd()
        val direct = "$root/${HtmlVideoPaths.MARKER}"
        if (exists(direct)) return root
        val nest = "$root/.andmx-html-video"
        if (isDir(nest)) {
            list(nest).filter { it.endsWith("/") }.forEach { entry ->
                val dir = "$nest/${entry.trimEnd('/')}"
                if (exists("$dir/${HtmlVideoPaths.MARKER}")) return dir
            }
        }
        return null
    }

    suspend fun requireProject(explicit: String?): String =
        findProject(explicit) ?: error("no html video project; call html_video_init first in current workspace")
}

private class HtmlVideoWorkspaceScanTool(private val context: Context) : Tool {
    private val fs = HtmlVideoFs(context)
    override val name = "html_video_workspace_scan"
    override val description =
        "Scan current workspace for audio/image/video assets and existing html-video projects. Use before attaching user media."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("root") { put("type", "string"); put("description", "Optional subpath under workspace") }
            putJsonObject("maxFiles") { put("type", "integer") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val root = fs.resolve(args.str("root") ?: fs.cwd())
            val max = (args.int("maxFiles") ?: 400).coerceIn(20, 2000)
            val audio = mutableListOf<String>()
            val images = mutableListOf<String>()
            val videos = mutableListOf<String>()
            val projects = mutableListOf<String>()
            walk(root, max) { path, name, isDirectory ->
                if (isDirectory) {
                    if (name == ".andmx-html-video" || fs.exists("$path/${HtmlVideoPaths.MARKER}")) {
                        if (fs.exists("$path/${HtmlVideoPaths.MARKER}")) projects += path
                    }
                    return@walk
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                when (ext) {
                    in HtmlVideoPaths.AUDIO_EXT -> audio += path
                    in HtmlVideoPaths.IMAGE_EXT -> images += path
                    in HtmlVideoPaths.VIDEO_EXT -> videos += path
                }
            }
            val nested = "$root/.andmx-html-video"
            if (fs.isDir(nested)) {
                fs.list(nested).filter { it.endsWith("/") }.forEach { e ->
                    val d = "$nested/${e.trimEnd('/')}"
                    if (fs.exists("$d/${HtmlVideoPaths.MARKER}") && d !in projects) projects += d
                }
            }
            buildString {
                appendLine("type: html_video_workspace_scan")
                appendLine("workspace: ${fs.cwd()}")
                appendLine("scanRoot: $root")
                appendLine("audioCount: ${audio.size}")
                appendLine("imageCount: ${images.size}")
                appendLine("videoCount: ${videos.size}")
                appendLine("projectCount: ${projects.size}")
                appendLine("audio:")
                if (audio.isEmpty()) appendLine("[]") else audio.take(80).forEach { appendLine("- $it") }
                appendLine("images:")
                if (images.isEmpty()) appendLine("[]") else images.take(80).forEach { appendLine("- $it") }
                appendLine("videos:")
                if (videos.isEmpty()) appendLine("[]") else videos.take(40).forEach { appendLine("- $it") }
                appendLine("projects:")
                if (projects.isEmpty()) appendLine("[]") else projects.forEach { appendLine("- $it") }
                appendLine("uiHint: List useful assets briefly; if user said use audio without role/timing, ask before attach.")
            }.let { ToolResult(it) }
        }.getOrElse { ToolResult("scan failed: ${it.message}", isError = true) }
    }

    private suspend fun walk(root: String, maxFiles: Int, onEntry: suspend (path: String, name: String, isDir: Boolean) -> Unit) {
        val stack = ArrayDeque<String>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < maxFiles) {
            val dir = stack.removeFirst()
            val entries = fs.list(dir)
            for (e in entries) {
                if (seen >= maxFiles) break
                val name = e.trimEnd('/')
                if (name.startsWith(".") && name != ".andmx-html-video") continue
                val path = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
                val isDirectory = e.endsWith("/")
                seen++
                onEntry(path, name, isDirectory)
                if (isDirectory) stack.addLast(path)
            }
        }
    }
}

private class HtmlVideoInitTool(private val context: Context) : Tool {
    private val fs = HtmlVideoFs(context)
    override val name = "html_video_init"
    override val description =
        "Create an HTML video project in the current workspace. No global default folder beyond workspace."
    override val risk = ToolRisk.WRITE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string") }
            putJsonObject("slug") { put("type", "string") }
            putJsonObject("dir") { put("type", "string"); put("description", "Optional project dir relative/absolute in workspace") }
            putJsonObject("width") { put("type", "integer") }
            putJsonObject("height") { put("type", "integer") }
            putJsonObject("fps") { put("type", "integer") }
            putJsonObject("durationMs") { put("type", "integer") }
            putJsonObject("inWorkspaceRoot") {
                put("type", "boolean")
                put("description", "If true, place project files directly in workspace root")
            }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val title = args.str("title")?.trim().orEmpty().ifBlank { "Untitled Film" }
            val slug = (args.str("slug")?.trim().orEmpty().ifBlank {
                title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "film" }
            } + "-" + UUID.randomUUID().toString().take(6)).take(48)
            val inRoot = args.bool("inWorkspaceRoot") == true
            val dirArg = args.str("dir")?.trim()
            val projectDir = when {
                !dirArg.isNullOrBlank() -> fs.resolve(dirArg)
                inRoot -> fs.cwd()
                else -> fs.resolve(HtmlVideoPaths.defaultProjectRel(slug))
            }
            val width = args.int("width") ?: 1080
            val height = args.int("height") ?: 1920
            val fps = args.int("fps") ?: 30
            val durationMs = args.long("durationMs") ?: 30000L
            val now = System.currentTimeMillis()
            val projectJson = """
                {
                  "id": "$slug",
                  "title": ${jsonStr(title)},
                  "createdAt": $now,
                  "updatedAt": $now,
                  "width": $width,
                  "height": $height,
                  "fps": $fps,
                  "durationMs": $durationMs,
                  "workspace": ${jsonStr(fs.cwd())},
                  "entry": "index.html",
                  "audioPlan": "audio/plan.json",
                  "storyboard": "storyboard.json",
                  "timeline": "timeline.json"
                }
            """.trimIndent()
            val storyboard = """
                {
                  "version": 1,
                  "scenes": []
                }
            """.trimIndent()
            val timeline = """
                {
                  "version": 1,
                  "clips": [],
                  "subtitles": [],
                  "audio": []
                }
            """.trimIndent()
            val audioPlan = """
                {
                  "version": 1,
                  "defaultStrategy": "procedural",
                  "clips": []
                }
            """.trimIndent()
            fs.writeText("$projectDir/project.json", projectJson)
            fs.writeText("$projectDir/storyboard.json", storyboard)
            fs.writeText("$projectDir/timeline.json", timeline)
            fs.writeText("$projectDir/audio/plan.json", audioPlan)
            fs.writeText("$projectDir/scenes/.gitkeep", "")
            fs.writeText("$projectDir/css/.gitkeep", "")
            fs.writeText("$projectDir/js/.gitkeep", "")
            fs.writeText("$projectDir/assets/.gitkeep", "")
            fs.writeText("$projectDir/audio/.gitkeep", "")
            val player = defaultPlayerHtml(title, width, height, durationMs)
            fs.writeText("$projectDir/index.html", player)
            ToolResult(
                buildString {
                    appendLine("type: html_video_init")
                    appendLine("projectId: $slug")
                    appendLine("projectDir: $projectDir")
                    appendLine("entry: $projectDir/index.html")
                    appendLine("width: $width")
                    appendLine("height: $height")
                    appendLine("fps: $fps")
                    appendLine("durationMs: $durationMs")
                    appendLine("title: $title")
                    appendLine("uiHint: Proceed to write storyboard/scenes/audio and build; minimize questions; deliver full film.")
                },
            )
        }.getOrElse { ToolResult("init failed: ${it.message}", isError = true) }
    }
}

private class HtmlVideoWriteTool(private val context: Context) : Tool {
    private val fs = HtmlVideoFs(context)
    override val name = "html_video_write"
    override val description =
        "Write or overwrite a text file inside an HTML video project (html/css/js/json/md). Prefer this for scenes and styles."
    override val risk = ToolRisk.WRITE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectDir") { put("type", "string") }
            putJsonObject("path") {
                put("type", "string")
                put("description", "Relative to projectDir or absolute under workspace")
            }
            putJsonObject("content") { put("type", "string") }
        }
        putJsonArray("required") { add("path"); add("content") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val project = fs.requireProject(args.str("projectDir"))
            val rel = args.str("path")?.trim().orEmpty()
            if (rel.isBlank()) return@withContext ToolResult("path required", isError = true)
            val content = args.str("content") ?: return@withContext ToolResult("content required", isError = true)
            if (content.length > 2_000_000) return@withContext ToolResult("content too large", isError = true)
            val target = if (rel.startsWith("/") || rel.startsWith(project)) fs.resolve(rel) else "$project/${rel.trimStart('/')}"
            if (!target.startsWith(project) && !target.contains("/.andmx-html-video/") && target != project) {
                // allow writing under project only when project is workspace root
                if (project != fs.cwd() && !target.startsWith(fs.cwd())) {
                    return@withContext ToolResult("path outside project/workspace rejected", isError = true)
                }
            }
            fs.writeText(target, content)
            ToolResult(
                buildString {
                    appendLine("type: html_video_write")
                    appendLine("projectDir: $project")
                    appendLine("path: $target")
                    appendLine("bytes: ${content.toByteArray().size}")
                    appendLine("uiHint: Continue engineering; keep motion/transitions consistent.")
                },
            )
        }.getOrElse { ToolResult("write failed: ${it.message}", isError = true) }
    }
}

private class HtmlVideoBuildTool(private val context: Context) : Tool {
    private val fs = HtmlVideoFs(context)
    override val name = "html_video_build"
    override val description =
        "Validate project files and refresh index.html entry metadata. Call after writing scenes/timeline/audio."
    override val risk = ToolRisk.WRITE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectDir") { put("type", "string") }
            putJsonObject("ensurePlayerShell") {
                put("type", "boolean")
                put("description", "If index.html missing, write default player shell. Default true.")
            }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val project = fs.requireProject(args.str("projectDir"))
            val ensure = args.bool("ensurePlayerShell") != false
            val files = listOf("project.json", "storyboard.json", "timeline.json", "index.html")
            val present = files.associateWith { fs.exists("$project/$it") }
            if (ensure && present["index.html"] != true) {
                fs.writeText("$project/index.html", defaultPlayerHtml("Film", 1080, 1920, 30000))
            }
            val sceneDir = "$project/scenes"
            val scenes = if (fs.isDir(sceneDir)) {
                fs.list(sceneDir).filter { it.endsWith(".html") || it.endsWith(".htm") }
            } else emptyList()
            val audioDir = "$project/audio"
            val audioFiles = if (fs.isDir(audioDir)) {
                fs.list(audioDir).filter { f ->
                    val ext = f.substringAfterLast('.', "").lowercase()
                    ext in HtmlVideoPaths.AUDIO_EXT
                }
            } else emptyList()
            val index = "$project/index.html"
            val indexText = runCatching { fs.readText(index, 64_000) }.getOrDefault("")
            val motionHints = listOf("animation", "@keyframes", "transition", "requestAnimationFrame", "Web Audio", "webaudio")
            val motionScore = motionHints.count { indexText.contains(it, ignoreCase = true) }
            val staticRisk = indexText.isNotBlank() && motionScore == 0 && scenes.isEmpty()
            ToolResult(
                buildString {
                    appendLine("type: html_video_build")
                    appendLine("projectDir: $project")
                    appendLine("entry: $index")
                    appendLine("files:")
                    present.forEach { (k, v) -> appendLine("  $k: $v") }
                    appendLine("sceneFiles: ${scenes.size}")
                    scenes.take(40).forEach { appendLine("- scenes/$it") }
                    appendLine("audioFiles: ${audioFiles.size}")
                    audioFiles.take(40).forEach { appendLine("- audio/$it") }
                    appendLine("motionHintScore: $motionScore")
                    appendLine("staticRisk: $staticRisk")
                    if (staticRisk) {
                        appendLine("warning: entry looks static; add keyframes/timeline motion before deliver")
                    }
                    appendLine("uiHint: If staticRisk, improve motion then rebuild; else preview and deliver.")
                },
            )
        }.getOrElse { ToolResult("build failed: ${it.message}", isError = true) }
    }
}

private class HtmlVideoAttachAudioTool(private val context: Context) : Tool {
    private val fs = HtmlVideoFs(context)
    override val name = "html_video_attach_audio"
    override val description =
        "Attach an audio file to the project timeline. role and timing are required. Do not guess when user omitted placement."
    override val risk = ToolRisk.WRITE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectDir") { put("type", "string") }
            putJsonObject("path") { put("type", "string"); put("description", "Audio file path in workspace") }
            putJsonObject("role") {
                put("type", "string")
                put("description", "bgm | narration | sfx | stinger | ambient")
            }
            putJsonObject("startMs") { put("type", "integer") }
            putJsonObject("endMs") { put("type", "integer") }
            putJsonObject("volume") { put("type", "number") }
            putJsonObject("loop") { put("type", "boolean") }
            putJsonObject("note") { put("type", "string") }
        }
        putJsonArray("required") { add("path"); add("role"); add("startMs") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val project = fs.requireProject(args.str("projectDir"))
            val path = args.str("path")?.trim().orEmpty()
            val role = args.str("role")?.trim()?.lowercase().orEmpty()
            val startMs = args.long("startMs")
            if (path.isBlank()) return@withContext ToolResult("path required", isError = true)
            if (role.isBlank()) {
                return@withContext ToolResult(
                    "role required (bgm|narration|sfx|stinger|ambient). Ask the user where/how to use this audio if unknown.",
                    isError = true,
                )
            }
            if (startMs == null) {
                return@withContext ToolResult(
                    "startMs required. Ask the user for timing if not specified.",
                    isError = true,
                )
            }
            val src = fs.resolve(path)
            if (!fs.exists(src)) return@withContext ToolResult("audio not found: $src", isError = true)
            val endMs = args.long("endMs")
            val volume = args.double("volume") ?: 1.0
            val loop = args.bool("loop") == true
            val note = args.str("note").orEmpty()
            val planPath = "$project/audio/plan.json"
            val old = runCatching { fs.readText(planPath) }.getOrDefault("""{"version":1,"clips":[]}""")
            val clipId = "clip-" + UUID.randomUUID().toString().take(8)
            val clipJson = buildString {
                append("{")
                append("\"id\":${jsonStr(clipId)},")
                append("\"path\":${jsonStr(src)},")
                append("\"role\":${jsonStr(role)},")
                append("\"startMs\":$startMs,")
                append("\"endMs\":${endMs ?: -1},")
                append("\"volume\":$volume,")
                append("\"loop\":$loop,")
                append("\"note\":${jsonStr(note)}")
                append("}")
            }
            val updated = if (old.contains("\"clips\"")) {
                val insertAt = old.lastIndexOf(']')
                if (insertAt > 0) {
                    val before = old.substring(0, insertAt).trimEnd()
                    val needsComma = before.contains('{') && !before.trimEnd().endsWith('[') && !before.trimEnd().endsWith(',')
                    buildString {
                        append(before)
                        if (needsComma && !before.endsWith("[")) append(',')
                        append('\n')
                        append(clipJson)
                        append('\n')
                        append(old.substring(insertAt))
                    }
                } else old
            } else {
                """{"version":1,"clips":[$clipJson]}"""
            }
            fs.writeText(planPath, updated)
            ToolResult(
                buildString {
                    appendLine("type: html_video_attach_audio")
                    appendLine("projectDir: $project")
                    appendLine("clipId: $clipId")
                    appendLine("path: $src")
                    appendLine("role: $role")
                    appendLine("startMs: $startMs")
                    appendLine("endMs: ${endMs ?: -1}")
                    appendLine("volume: $volume")
                    appendLine("loop: $loop")
                    appendLine("plan: $planPath")
                    appendLine("uiHint: Wire this clip into player timeline code if not already dynamic.")
                },
            )
        }.getOrElse { ToolResult("attach_audio failed: ${it.message}", isError = true) }
    }
}

private class HtmlVideoSynthAudioTool(private val context: Context) : Tool {
    private val fs = HtmlVideoFs(context)
    override val name = "html_video_synth_audio"
    override val description =
        "Synthesize procedural audio (wav) or system TTS into the project. Default path for film sound when user has no file."
    override val risk = ToolRisk.WRITE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectDir") { put("type", "string") }
            putJsonObject("kind") {
                put("type", "string")
                put("description", "whoosh | click | hit | drone | blip | riser | tts")
            }
            putJsonObject("filename") { put("type", "string") }
            putJsonObject("durationMs") { put("type", "integer") }
            putJsonObject("frequencyHz") { put("type", "number") }
            putJsonObject("text") { put("type", "string"); put("description", "For kind=tts") }
            putJsonObject("role") { put("type", "string") }
            putJsonObject("startMs") { put("type", "integer") }
            putJsonObject("autoAttach") { put("type", "boolean") }
        }
        putJsonArray("required") { add("kind") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val project = fs.requireProject(args.str("projectDir"))
            val kind = args.str("kind")?.trim()?.lowercase() ?: "whoosh"
            val durationMs = (args.int("durationMs") ?: when (kind) {
                "drone" -> 4000
                "riser" -> 1500
                "tts" -> 0
                else -> 350
            }).coerceIn(40, 30_000)
            val freq = args.double("frequencyHz") ?: when (kind) {
                "click" -> 1800.0
                "hit" -> 90.0
                "drone" -> 110.0
                "blip" -> 880.0
                "riser" -> 220.0
                else -> 420.0
            }
            val filename = args.str("filename")?.trim().orEmpty().ifBlank {
                "${kind}-${UUID.randomUUID().toString().take(6)}.wav"
            }.let { if (it.endsWith(".wav")) it else "$it.wav" }
            val outPath = "$project/audio/$filename"
            if (kind == "tts") {
                val text = args.str("text")?.trim().orEmpty()
                if (text.isBlank()) return@withContext ToolResult("text required for tts", isError = true)
                val ok = synthTtsToFile(context, text, outPath, fs)
                if (!ok) {
                    return@withContext ToolResult(
                        buildString {
                            appendLine("type: html_video_synth_audio")
                            appendLine("kind: tts")
                            appendLine("ok: false")
                            appendLine("error: TTS unavailable on device")
                            appendLine("fallback: strengthen subtitles; use procedural sfx")
                            appendLine("uiHint: Continue delivery with subtitles + procedural audio.")
                        },
                        isError = true,
                    )
                }
            } else {
                val wav = WavSynth.render(kind, durationMs, freq)
                fs.writeBytes(outPath, wav)
            }
            val role = args.str("role")?.trim()?.lowercase()
            val startMs = args.long("startMs")
            val auto = args.bool("autoAttach") == true
            if (auto) {
                if (role.isNullOrBlank() || startMs == null) {
                    return@withContext ToolResult(
                        buildString {
                            appendLine("type: html_video_synth_audio")
                            appendLine("ok: true")
                            appendLine("path: $outPath")
                            appendLine("attached: false")
                            appendLine("error: autoAttach requires role and startMs")
                        },
                        isError = true,
                    )
                }
                // reuse attach logic by writing plan entry
                val attachArgs = buildJsonObject {
                    put("projectDir", project)
                    put("path", outPath)
                    put("role", role)
                    put("startMs", startMs)
                }
                val attach = HtmlVideoAttachAudioTool(context).execute(attachArgs)
                return@withContext ToolResult(
                    buildString {
                        appendLine("type: html_video_synth_audio")
                        appendLine("kind: $kind")
                        appendLine("path: $outPath")
                        appendLine("durationMs: $durationMs")
                        appendLine("attached: ${!attach.isError}")
                        appendLine(attach.output)
                    },
                    isError = attach.isError,
                )
            }
            ToolResult(
                buildString {
                    appendLine("type: html_video_synth_audio")
                    appendLine("kind: $kind")
                    appendLine("path: $outPath")
                    appendLine("durationMs: $durationMs")
                    appendLine("frequencyHz: $freq")
                    appendLine("attached: false")
                    appendLine("uiHint: Attach with role+startMs or embed path in player.")
                },
            )
        }.getOrElse { ToolResult("synth_audio failed: ${it.message}", isError = true) }
    }

    private suspend fun synthTtsToFile(context: Context, text: String, outPath: String, fs: HtmlVideoFs): Boolean {
        val host = fs.hostFile(outPath)
        if (host == null) {
            fs.writeText(outPath.removeSuffix(".wav") + ".tts.txt", text)
            return false
        }
        host.parentFile?.mkdirs()
        val latch = CountDownLatch(1)
        val holder = arrayOf<TextToSpeech?>(null)
        var ok = false
        try {
            holder[0] = TextToSpeech(context.applicationContext) { status ->
                val engine = holder[0]
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    latch.countDown()
                    return@TextToSpeech
                }
                engine.language = Locale.getDefault()
                val result = engine.synthesizeToFile(text, null, host, "andmx-html-video-tts")
                ok = result == TextToSpeech.SUCCESS
                Thread {
                    repeat(50) {
                        if (host.exists() && host.length() > 44) {
                            ok = true
                            latch.countDown()
                            return@Thread
                        }
                        Thread.sleep(100)
                    }
                    latch.countDown()
                }.start()
            }
            latch.await(10, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            ok = false
        } finally {
            runCatching { holder[0]?.shutdown() }
        }
        return ok && host.exists() && host.length() > 0L
    }
}

private class HtmlVideoFetchAudioTool(private val context: Context) : Tool {
    private val fs = HtmlVideoFs(context)
    override val name = "html_video_fetch_audio"
    override val description =
        "Best-effort download of an audio URL into the project (user requested web music/sfx). Prefer free/open sources."
    override val risk = ToolRisk.NETWORK
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectDir") { put("type", "string") }
            putJsonObject("url") { put("type", "string") }
            putJsonObject("filename") { put("type", "string") }
            putJsonObject("sourceNote") { put("type", "string") }
            putJsonObject("role") { put("type", "string") }
            putJsonObject("startMs") { put("type", "integer") }
            putJsonObject("autoAttach") { put("type", "boolean") }
        }
        putJsonArray("required") { add("url") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val project = fs.requireProject(args.str("projectDir"))
            val url = args.str("url")?.trim().orEmpty()
            if (url.isBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) {
                return@withContext ToolResult("https url required", isError = true)
            }
            val name = args.str("filename")?.trim().orEmpty().ifBlank {
                url.substringAfterLast('/').substringBefore('?').ifBlank { "fetch-${UUID.randomUUID().toString().take(6)}.mp3" }
            }
            val out = "$project/audio/$name"
            val bytes = httpGetBytes(url, maxBytes = 8_000_000)
            if (bytes == null || bytes.isEmpty()) {
                return@withContext ToolResult(
                    buildString {
                        appendLine("type: html_video_fetch_audio")
                        appendLine("ok: false")
                        appendLine("url: $url")
                        appendLine("error: download failed")
                        appendLine("fallback: use html_video_synth_audio procedural audio")
                    },
                    isError = true,
                )
            }
            fs.writeBytes(out, bytes)
            val note = args.str("sourceNote").orEmpty()
            fs.writeText(
                "$project/audio/${name}.source.txt",
                "url=$url\nsourceNote=$note\nfetchedAt=${System.currentTimeMillis()}\n",
            )
            val auto = args.bool("autoAttach") == true
            val role = args.str("role")
            val startMs = args.long("startMs")
            if (auto) {
                if (role.isNullOrBlank() || startMs == null) {
                    return@withContext ToolResult(
                        buildString {
                            appendLine("type: html_video_fetch_audio")
                            appendLine("ok: true")
                            appendLine("path: $out")
                            appendLine("bytes: ${bytes.size}")
                            appendLine("attached: false")
                            appendLine("error: autoAttach requires role and startMs; ask user if unknown")
                        },
                        isError = true,
                    )
                }
                val attach = HtmlVideoAttachAudioTool(context).execute(
                    buildJsonObject {
                        put("projectDir", project)
                        put("path", out)
                        put("role", role)
                        put("startMs", startMs)
                    },
                )
                return@withContext ToolResult(
                    buildString {
                        appendLine("type: html_video_fetch_audio")
                        appendLine("ok: true")
                        appendLine("path: $out")
                        appendLine("bytes: ${bytes.size}")
                        appendLine(attach.output)
                    },
                    isError = attach.isError,
                )
            }
            ToolResult(
                buildString {
                    appendLine("type: html_video_fetch_audio")
                    appendLine("ok: true")
                    appendLine("path: $out")
                    appendLine("bytes: ${bytes.size}")
                    appendLine("url: $url")
                    appendLine("uiHint: Attach with explicit role/timing; cite source to user briefly.")
                },
            )
        }.getOrElse { ToolResult("fetch_audio failed: ${it.message}", isError = true) }
    }

    private fun httpGetBytes(url: String, maxBytes: Int): ByteArray? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AndMX-HtmlVideo/1.0")
            }
            conn.inputStream.use { input ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) return null
                    bos.write(buf, 0, n)
                }
                bos.toByteArray()
            }
        }.getOrNull()
    }
}

private class HtmlVideoPreviewTool(private val context: Context) : Tool {
    private val fs = HtmlVideoFs(context)
    override val name = "html_video_preview"
    override val description = "Return preview entry path and try opening the HTML film if possible."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectDir") { put("type", "string") }
            putJsonObject("open") { put("type", "boolean") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val project = fs.requireProject(args.str("projectDir"))
            val entry = "$project/index.html"
            if (!fs.exists(entry)) return@withContext ToolResult("missing index.html; write/build first", isError = true)
            val open = args.bool("open") != false
            var opened = false
            var openError = ""
            if (open) {
                val host = fs.hostFile(entry)
                if (host != null && host.exists()) {
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            context.applicationContext,
                            context.packageName + ".fileprovider",
                            host,
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "text/html")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.applicationContext.startActivity(intent)
                        opened = true
                    }.onFailure {
                        openError = it.message.orEmpty()
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(host)).apply {
                                setDataAndType(Uri.fromFile(host), "text/html")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.applicationContext.startActivity(intent)
                            opened = true
                            openError = ""
                        }
                    }
                } else {
                    openError = "host file unavailable (remote workspace?); open path manually"
                }
            }
            ToolResult(
                buildString {
                    appendLine("type: html_video_preview")
                    appendLine("projectDir: $project")
                    appendLine("entry: $entry")
                    appendLine("opened: $opened")
                    if (openError.isNotBlank()) appendLine("openError: $openError")
                    appendLine("uiHint: Tell user how to open entry; if open failed, give path.")
                },
            )
        }.getOrElse { ToolResult("preview failed: ${it.message}", isError = true) }
    }
}

private class HtmlVideoDeliverTool(private val context: Context) : Tool {
    private val fs = HtmlVideoFs(context)
    override val name = "html_video_deliver"
    override val description =
        "Finalize delivery checklist for the HTML film in current workspace. Call when the film is ready."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectDir") { put("type", "string") }
            putJsonObject("summary") { put("type", "string") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val project = fs.requireProject(args.str("projectDir"))
            val entry = "$project/index.html"
            val hasEntry = fs.exists(entry)
            val scenes = if (fs.isDir("$project/scenes")) fs.list("$project/scenes").filter { it.endsWith(".html") } else emptyList()
            val audio = if (fs.isDir("$project/audio")) {
                fs.list("$project/audio").filter {
                    val ext = it.substringAfterLast('.', "").lowercase()
                    ext in HtmlVideoPaths.AUDIO_EXT
                }
            } else emptyList()
            val summary = args.str("summary").orEmpty()
            val delivery = buildString {
                appendLine("# Delivery")
                appendLine("project: $project")
                appendLine("entry: $entry")
                appendLine("scenes: ${scenes.size}")
                appendLine("audioClips: ${audio.size}")
                if (summary.isNotBlank()) {
                    appendLine()
                    appendLine(summary)
                }
            }
            fs.writeText("$project/DELIVERY.md", delivery)
            ToolResult(
                buildString {
                    appendLine("type: html_video_deliver")
                    appendLine("projectDir: $project")
                    appendLine("entry: $entry")
                    appendLine("entryExists: $hasEntry")
                    appendLine("sceneCount: ${scenes.size}")
                    appendLine("audioCount: ${audio.size}")
                    appendLine("deliveryDoc: $project/DELIVERY.md")
                    appendLine("ready: ${hasEntry}")
                    appendLine("uiHint: Present Chinese delivery: path, how to preview, duration/style, what audio used. Do not dump raw fields.")
                },
            )
        }.getOrElse { ToolResult("deliver failed: ${it.message}", isError = true) }
    }
}

private object WavSynth {
    fun render(kind: String, durationMs: Int, frequencyHz: Double): ByteArray {
        val sampleRate = 22050
        val n = ((durationMs / 1000.0) * sampleRate).toInt().coerceAtLeast(1)
        val samples = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val env = when (kind) {
                "whoosh" -> {
                    val a = (i.toDouble() / n)
                    (if (a < 0.3) a / 0.3 else 1.0 - (a - 0.3) / 0.7).coerceIn(0.0, 1.0)
                }
                "click" -> if (i < sampleRate / 50) 1.0 - i.toDouble() / (sampleRate / 50) else 0.0
                "hit" -> kotlin.math.exp(-t * 8.0)
                "drone" -> 0.35
                "blip" -> {
                    val a = i.toDouble() / n
                    kotlin.math.sin(a * PI).coerceIn(0.0, 1.0)
                }
                "riser" -> (i.toDouble() / n).coerceIn(0.0, 1.0)
                else -> {
                    val a = i.toDouble() / n
                    kotlin.math.sin(a * PI).coerceIn(0.0, 1.0)
                }
            }
            val f = when (kind) {
                "whoosh" -> frequencyHz * (0.5 + i.toDouble() / n)
                "riser" -> frequencyHz * (1.0 + 2.0 * i.toDouble() / n)
                "hit" -> frequencyHz * (1.0 - 0.4 * i.toDouble() / n)
                else -> frequencyHz
            }
            val noise = if (kind == "whoosh" || kind == "hit") ((Math.random() * 2 - 1) * 0.25) else 0.0
            val wave = sin(2.0 * PI * f * t) * 0.75 + noise
            val v = (wave * env * Short.MAX_VALUE * 0.55).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            samples[i] = v.toShort()
        }
        return pcm16MonoToWav(samples, sampleRate)
    }

    private fun pcm16MonoToWav(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val d = DataOutputStream(out)
        fun writeString(s: String) = s.forEach { d.writeByte(it.code) }
        fun writeIntLE(v: Int) {
            d.writeByte(v and 0xff)
            d.writeByte((v shr 8) and 0xff)
            d.writeByte((v shr 16) and 0xff)
            d.writeByte((v shr 24) and 0xff)
        }
        fun writeShortLE(v: Int) {
            d.writeByte(v and 0xff)
            d.writeByte((v shr 8) and 0xff)
        }
        writeString("RIFF")
        writeIntLE(36 + dataSize)
        writeString("WAVE")
        writeString("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(1)
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * 2)
        writeShortLE(2)
        writeShortLE(16)
        writeString("data")
        writeIntLE(dataSize)
        for (s in samples) writeShortLE(s.toInt())
        return out.toByteArray()
    }
}

private fun jsonStr(s: String): String =
    buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

private fun defaultPlayerHtml(title: String, width: Int, height: Int, durationMs: Long): String {
    return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"/>
<title>${title.replace("<", "")}</title>
<style>
  :root{
    --bg:#0b0d10; --fg:#f4f6f8; --muted:#9aa3ad; --accent:#7dd3fc;
    --w:${width}px; --h:${height}px;
  }
  *{box-sizing:border-box}
  html,body{margin:0;height:100%;background:#000;color:var(--fg);font-family:ui-sans-serif,system-ui,sans-serif}
  #stage{
    width:min(100vw, calc(100vh * ${width}.0 / ${height}));
    aspect-ratio:${width}/${height};
    margin:0 auto; position:relative; overflow:hidden; background:var(--bg);
  }
  .layer{position:absolute;inset:0;display:grid;place-items:center;opacity:0}
  .layer.active{opacity:1}
  h1{font-size:clamp(28px,6vw,56px);letter-spacing:.04em;margin:0;text-align:center;padding:0 8%}
  .sub{color:var(--muted);margin-top:1rem;text-align:center;padding:0 10%}
  #bar{position:absolute;left:0;right:0;bottom:0;height:4px;background:#ffffff22}
  #bar>i{display:block;height:100%;width:0;background:var(--accent)}
  #subs{position:absolute;left:6%;right:6%;bottom:8%;text-align:center;font-size:clamp(14px,3.2vw,22px);
    text-shadow:0 2px 8px #000; min-height:1.4em}
  @keyframes rise{from{transform:translateY(24px);opacity:0}to{transform:translateY(0);opacity:1}}
  @keyframes drift{from{transform:scale(1.08)}to{transform:scale(1)}}
  .rise{animation:rise .9s cubic-bezier(.2,.8,.2,1) both}
  .drift{animation:drift ${maxOf(durationMs, 1000)}ms linear both}
</style>
</head>
<body>
<div id="stage">
  <div class="layer active drift" id="sc0">
    <div>
      <h1 class="rise">${title.replace("<", "")}</h1>
      <p class="sub rise">AndMX HTML Film — replace scenes for final delivery</p>
    </div>
  </div>
  <div id="subs"></div>
  <div id="bar"><i id="prog"></i></div>
</div>
<script>
(() => {
  const duration = ${durationMs};
  const start = performance.now();
  const prog = document.getElementById('prog');
  const subs = document.getElementById('subs');
  const cues = [];
  function tick(now){
    const t = Math.min(duration, now - start);
    prog.style.width = (t / duration * 100).toFixed(2) + '%';
    const cue = cues.find(c => t >= c.start && t < c.end);
    subs.textContent = cue ? cue.text : '';
    if (t < duration) requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);
  // WebAudio subtle bed
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const o = ctx.createOscillator();
    const g = ctx.createGain();
    o.type = 'sine'; o.frequency.value = 96;
    g.gain.value = 0.02; o.connect(g); g.connect(ctx.destination); o.start();
    setTimeout(() => { try { o.stop(); ctx.close(); } catch(e){} }, duration);
  } catch (e) {}
})();
</script>
</body>
</html>
""".trimIndent()
}
