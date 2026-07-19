package com.andmx.agent.plugins.devforge

import android.content.Context
import android.os.Build
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
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
import java.util.UUID

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

class DevForgeToolset(private val context: Context) {
    fun tools(): List<Tool> = listOf(
        ForgeEnvScanTool(context),
        ForgeListProfilesTool(context),
        ForgeRecipeRecommendTool(context),
        ForgeRecipePlanTool(context),
        ForgeCheckStepTool(context),
        ForgeScaffoldProjectTool(context),
        ForgeWriteToolingTool(context),
        ForgeProjectStatusTool(context),
    )
}

private data class Profile(
    val id: String,
    val title: String,
    val summary: String,
    val tags: List<String>,
    val requires: List<String>,
    val nextPlugins: List<String>,
)

private object ForgeProfiles {
    val all: List<Profile> = listOf(
        Profile(
            id = "web-static",
            title = "静态网页",
            summary = "HTML/CSS/JS 静态站点，可在当前工作区直接编辑与预览",
            tags = listOf("web", "html", "frontend", "static"),
            requires = listOf("workspace_writable"),
            nextPlugins = listOf("andmx-html-video"),
        ),
        Profile(
            id = "web-node",
            title = "Node 前端/脚本",
            summary = "package.json 驱动的 Node 工程；无 Node 时给安装指引",
            tags = listOf("web", "node", "javascript", "typescript"),
            requires = listOf("workspace_writable", "node_optional"),
            nextPlugins = emptyList(),
        ),
        Profile(
            id = "android-app",
            title = "Android 应用",
            summary = "Android/Compose 工程脚手架；构建运行委托 andmx-android-dev",
            tags = listOf("android", "kotlin", "compose", "mobile"),
            requires = listOf("workspace_writable"),
            nextPlugins = listOf("andmx-android-dev"),
        ),
        Profile(
            id = "python-script",
            title = "Python 脚本/工具",
            summary = "main.py + requirements 轻量工具工程",
            tags = listOf("python", "script", "tooling"),
            requires = listOf("workspace_writable", "python_optional"),
            nextPlugins = emptyList(),
        ),
        Profile(
            id = "docs-markdown",
            title = "文档站/笔记",
            summary = "Markdown 文档目录与基础规范",
            tags = listOf("docs", "markdown", "writing"),
            requires = listOf("workspace_writable"),
            nextPlugins = listOf("document-skills"),
        ),
        Profile(
            id = "html-video-ready",
            title = "HTML 影片工作区",
            summary = "为 andmx-html-video 准备目录约定与说明",
            tags = listOf("video", "html", "creative"),
            requires = listOf("workspace_writable"),
            nextPlugins = listOf("andmx-html-video"),
        ),
        Profile(
            id = "agent-skill-pack",
            title = "Agent 技能包",
            summary = "skills/ 目录与 SKILL.md 模板",
            tags = listOf("agent", "skill", "plugin"),
            requires = listOf("workspace_writable"),
            nextPlugins = listOf("skill-creator"),
        ),
    )

    fun get(id: String): Profile? = all.find { it.id == id }
}

private class ForgeEnv(private val context: Context) {
    val access = WorkspaceAccess(context)

    suspend fun which(cmd: String): String? {
        val res = access.executeShell("command -v ${shq(cmd)} 2>/dev/null || which ${shq(cmd)} 2>/dev/null || true")
        val line = res.stdout.lines().map { it.trim() }.firstOrNull { it.startsWith("/") || it.contains(cmd) }
        return line?.takeIf { it.isNotBlank() && !it.contains("not found") }
    }

    suspend fun version(cmd: String, args: String = "--version"): String? {
        val res = access.executeShell("$cmd $args 2>&1 | head -n 1")
        return res.stdout.lines().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

private class ForgeEnvScanTool(private val context: Context) : Tool {
    private val env = ForgeEnv(context)
    override val name = "forge_env_scan"
    override val description =
        "Scan current workspace host for developer capabilities (shell/jdk/node/python/git/android). Data only."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("deep") { put("type", "boolean") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val deep = args.bool("deep") == true
            val access = env.access
            val workspace = access.guestCwd()
            val writable = runCatching {
                val p = "$workspace/.andmx-forge-write-test"
                access.writeText(p, "ok")
                access.deleteFile(p)
                true
            }.getOrDefault(false)
            val shell = runCatching { env.which("sh") ?: env.which("bash") }.getOrNull()
            val git = runCatching { env.which("git") }.getOrNull()
            val node = runCatching { env.which("node") }.getOrNull()
            val npm = runCatching { env.which("npm") }.getOrNull()
            val python = runCatching { env.which("python3") ?: env.which("python") }.getOrNull()
            val java = runCatching { env.which("java") }.getOrNull()
            val javac = runCatching { env.which("javac") }.getOrNull()
            val gradle = runCatching { env.which("gradle") }.getOrNull()
            val adb = runCatching { env.which("adb") }.getOrNull()
            suspend fun ver(bin: String?, flag: String = "--version"): String {
                if (bin.isNullOrBlank()) return ""
                return if (deep) runCatching { env.version(bin, flag).orEmpty() }.getOrDefault("") else ""
            }
            val gitVer = ver(git)
            val nodeVer = ver(node)
            val pythonVer = ver(python)
            val javaVer = ver(java, "-version")
            val profilesReady = mutableListOf<String>()
            val profilesMissing = mutableListOf<String>()
            if (writable) {
                profilesReady += listOf("web-static", "docs-markdown", "html-video-ready", "agent-skill-pack", "android-app")
                if (node != null) profilesReady += "web-node" else profilesMissing += "web-node"
                if (python != null) profilesReady += "python-script" else profilesMissing += "python-script"
            } else {
                profilesMissing += ForgeProfiles.all.map { it.id }
            }
            ToolResult(
                buildString {
                    appendLine("type: forge_env_scan")
                    appendLine("host: ${if (access.isRemote) "remote" else "android-device"}")
                    appendLine("androidRelease: ${Build.VERSION.RELEASE}")
                    appendLine("api: ${Build.VERSION.SDK_INT}")
                    appendLine("workspace: $workspace")
                    appendLine("displayWorkspace: ${access.displayCwd()}")
                    appendLine("workspaceWritable: $writable")
                    appendLine("tools:")
                    appendLine("  shell: ${shell ?: "none"}")
                    appendLine("  git: ${git ?: "none"} $gitVer")
                    appendLine("  node: ${node ?: "none"} $nodeVer")
                    appendLine("  npm: ${npm ?: "none"}")
                    appendLine("  python: ${python ?: "none"} $pythonVer")
                    appendLine("  java: ${java ?: "none"} $javaVer")
                    appendLine("  javac: ${javac ?: "none"}")
                    appendLine("  gradle: ${gradle ?: "none"}")
                    appendLine("  adb: ${adb ?: "none"}")
                    appendLine("tiers:")
                    appendLine("  shell: ${if (shell != null) "available" else "limited"}")
                    appendLine("  jdk: ${if (java != null) "available" else "missing"}")
                    appendLine("  node: ${if (node != null) "available" else "missing"}")
                    appendLine("  python: ${if (python != null) "available" else "missing"}")
                    appendLine("  git: ${if (git != null) "available" else "missing"}")
                    appendLine("  androidSdk: ${if (adb != null) "partial" else "unknown"}")
                    appendLine("profilesReady:")
                    profilesReady.distinct().forEach { appendLine("- $it") }
                    appendLine("profilesPartialOrGuide:")
                    profilesMissing.distinct().forEach { appendLine("- $it") }
                    appendLine("uiHint: Render Chinese capability matrix; ask project type only if unknown; then recommend profile.")
                },
            )
        }.getOrElse { ToolResult("forge_env_scan failed: ${it.message}", isError = true) }
    }
}

private class ForgeListProfilesTool(private val context: Context) : Tool {
    override val name = "forge_list_profiles"
    override val description = "List builtin engineering profiles/recipes available in andmx-dev-forge."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        ToolResult(
            buildString {
                appendLine("type: forge_list_profiles")
                appendLine("profiles:")
                ForgeProfiles.all.forEach { p ->
                    appendLine("- id: ${p.id}")
                    appendLine("  title: ${p.title}")
                    appendLine("  summary: ${p.summary}")
                    appendLine("  tags: ${p.tags.joinToString(",")}")
                    appendLine("  nextPlugins: ${p.nextPlugins.joinToString(",")}")
                }
                appendLine("uiHint: Present as Chinese options; do not dump if user already stated project type.")
            },
        )
    }
}

private class ForgeRecipeRecommendTool(private val context: Context) : Tool {
    override val name = "forge_recipe_recommend"
    override val description =
        "Recommend engineering profiles from project type keywords and constraints. Returns ranked ids + reasons fields."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectType") { put("type", "string") }
            putJsonObject("goals") { put("type", "string") }
            putJsonObject("constraints") { put("type", "string") }
        }
        putJsonArray("required") { add("projectType") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val text = listOfNotNull(args.str("projectType"), args.str("goals"), args.str("constraints"))
            .joinToString(" ").lowercase()
        fun score(p: Profile): Int {
            var s = 0
            p.tags.forEach { if (text.contains(it)) s += 3 }
            if (text.contains(p.id.replace('-', ' '))) s += 4
            when {
                listOf("安卓", "android", "apk", "compose", "kotlin").any { text.contains(it) } && p.id == "android-app" -> s += 6
                listOf("网页", "website", "html", "落地页", "静态").any { text.contains(it) } && p.id == "web-static" -> s += 6
                listOf("node", "react", "vue", "npm", "typescript").any { text.contains(it) } && p.id == "web-node" -> s += 6
                listOf("python", "爬虫", "脚本").any { text.contains(it) } && p.id == "python-script" -> s += 6
                listOf("文档", "docs", "readme", "笔记").any { text.contains(it) } && p.id == "docs-markdown" -> s += 5
                listOf("视频", "影片", "html video", "动画").any { text.contains(it) } && p.id == "html-video-ready" -> s += 6
                listOf("技能", "skill", "插件").any { text.contains(it) } && p.id == "agent-skill-pack" -> s += 5
            }
            return s
        }
        val ranked = ForgeProfiles.all.map { it to score(it) }.sortedByDescending { it.second }
        val top = ranked.filter { it.second > 0 }.ifEmpty { ranked.take(3).map { it.first to 1 } }
        ToolResult(
            buildString {
                appendLine("type: forge_recipe_recommend")
                appendLine("query: ${args.str("projectType")}")
                appendLine("recommendations:")
                top.take(3).forEachIndexed { i, (p, sc) ->
                    appendLine("- rank: ${i + 1}")
                    appendLine("  id: ${p.id}")
                    appendLine("  title: ${p.title}")
                    appendLine("  score: $sc")
                    appendLine("  summary: ${p.summary}")
                    appendLine("  nextPlugins: ${p.nextPlugins.joinToString(",")}")
                    appendLine("  tier: ${if (i == 0) "recommended" else if (i == 1) "alternative" else "optional"}")
                }
                appendLine("uiHint: Recommend 1 primary + optional alternatives in Chinese; confirm then plan/scaffold.")
            },
        )
    }
}

private class ForgeRecipePlanTool(private val context: Context) : Tool {
    override val name = "forge_recipe_plan"
    override val description = "Build a step plan for a profile without executing writes."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("profileId") { put("type", "string") }
            putJsonObject("projectName") { put("type", "string") }
        }
        putJsonArray("required") { add("profileId") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val id = args.str("profileId")?.trim().orEmpty()
        val p = ForgeProfiles.get(id) ?: return@withContext ToolResult("unknown profileId", isError = true)
        val name = args.str("projectName")?.trim().orEmpty().ifBlank { "app" }
        val steps = when (p.id) {
            "web-static" -> listOf(
                "check workspace writable",
                "scaffold index.html css/style.css js/main.js",
                "write README with preview tips",
                "status verify files",
            )
            "web-node" -> listOf(
                "check node/npm or mark guide-only",
                "scaffold package.json and src/index.js",
                "write scripts and README",
                "status verify",
            )
            "android-app" -> listOf(
                "check workspace",
                "scaffold minimal android markers + README pointing to android_* tools",
                "advise enable andmx-android-dev for build/run",
                "status verify",
            )
            "python-script" -> listOf(
                "check python or guide-only",
                "scaffold main.py requirements.txt",
                "write README",
                "status verify",
            )
            "docs-markdown" -> listOf(
                "scaffold docs/ and README",
                "add outline pages",
                "status verify",
            )
            "html-video-ready" -> listOf(
                "scaffold .andmx-html-video notes",
                "advise enable andmx-html-video and describe film request",
                "status verify",
            )
            "agent-skill-pack" -> listOf(
                "scaffold skills/example/SKILL.md",
                "write packaging notes",
                "status verify",
            )
            else -> listOf("check", "scaffold", "verify")
        }
        ToolResult(
            buildString {
                appendLine("type: forge_recipe_plan")
                appendLine("profileId: ${p.id}")
                appendLine("title: ${p.title}")
                appendLine("projectName: $name")
                appendLine("steps:")
                steps.forEachIndexed { i, s ->
                    appendLine("- index: ${i + 1}")
                    appendLine("  action: $s")
                }
                appendLine("uiHint: Show short plan; on confirm call forge_scaffold_project / forge_write_tooling.")
            },
        )
    }
}

private class ForgeCheckStepTool(private val context: Context) : Tool {
    private val env = ForgeEnv(context)
    override val name = "forge_check_step"
    override val description = "Run one environment check step (node/python/git/java/workspace)."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("check") {
                put("type", "string")
                put("description", "workspace | node | python | git | java | android")
            }
        }
        putJsonArray("required") { add("check") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val check = args.str("check")?.trim()?.lowercase().orEmpty()
        val access = env.access
        val result = when (check) {
            "workspace" -> {
                val w = access.guestCwd()
                val ok = runCatching {
                    val p = "$w/.andmx-forge-check"
                    access.writeText(p, "1")
                    access.deleteFile(p)
                    true
                }.getOrDefault(false)
                "status: ${if (ok) "ok" else "fail"}\nworkspace: $w\nwritable: $ok"
            }
            "node" -> {
                val bin = env.which("node")
                "status: ${if (bin != null) "ok" else "missing"}\npath: ${bin ?: ""}\nversion: ${bin?.let { env.version(it) } ?: ""}"
            }
            "python" -> {
                val bin = env.which("python3") ?: env.which("python")
                "status: ${if (bin != null) "ok" else "missing"}\npath: ${bin ?: ""}\nversion: ${bin?.let { env.version(it) } ?: ""}"
            }
            "git" -> {
                val bin = env.which("git")
                "status: ${if (bin != null) "ok" else "missing"}\npath: ${bin ?: ""}"
            }
            "java" -> {
                val bin = env.which("java")
                "status: ${if (bin != null) "ok" else "missing"}\npath: ${bin ?: ""}\nversion: ${bin?.let { env.version(it, "-version") } ?: ""}"
            }
            "android" -> {
                val adb = env.which("adb")
                "status: ${if (adb != null) "partial" else "unknown"}\nadb: ${adb ?: "none"}\nnote: use android_preflight from andmx-android-dev for full SDK checks"
            }
            else -> return@withContext ToolResult("unknown check", isError = true)
        }
        ToolResult("type: forge_check_step\ncheck: $check\n$result\nuiHint: Continue plan based on status.")
    }
}

private class ForgeScaffoldProjectTool(private val context: Context) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "forge_scaffold_project"
    override val description =
        "Scaffold project files for a profile into the current workspace (or subdir). No global default workspace."
    override val risk = ToolRisk.WRITE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("profileId") { put("type", "string") }
            putJsonObject("dir") { put("type", "string"); put("description", "Optional subdir under current workspace") }
            putJsonObject("projectName") { put("type", "string") }
            putJsonObject("overwrite") { put("type", "boolean") }
        }
        putJsonArray("required") { add("profileId") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val id = args.str("profileId")?.trim().orEmpty()
            val profile = ForgeProfiles.get(id) ?: return@withContext ToolResult("unknown profileId", isError = true)
            val name = args.str("projectName")?.trim().orEmpty().ifBlank { "project" }
            val overwrite = args.bool("overwrite") == true
            val base = args.str("dir")?.trim()?.let { access.resolvePath(it) } ?: access.guestCwd()
            val written = mutableListOf<String>()
            suspend fun put(rel: String, content: String) {
                val path = if (base.endsWith("/")) "$base$rel" else "$base/$rel"
                if (!overwrite && access.exists(path)) return
                access.writeText(path, content)
                written += path
            }
            when (profile.id) {
                "web-static" -> {
                    put(
                        "index.html",
                        """
                        <!DOCTYPE html>
                        <html lang="zh-CN">
                        <head>
                          <meta charset="utf-8"/>
                          <meta name="viewport" content="width=device-width, initial-scale=1"/>
                          <title>$name</title>
                          <link rel="stylesheet" href="css/style.css"/>
                        </head>
                        <body>
                          <main>
                            <h1>$name</h1>
                            <p>Scaffolded by AndMX Dev Forge.</p>
                          </main>
                          <script src="js/main.js"></script>
                        </body>
                        </html>
                        """.trimIndent(),
                    )
                    put("css/style.css", "body{font-family:system-ui,sans-serif;margin:2rem;background:#0f1115;color:#e8eaed}main{max-width:40rem}")
                    put("js/main.js", "console.log('andmx dev forge web-static ready');\n")
                    put("README.md", "# $name\n\n静态网页脚手架。用浏览器或 AndMX 文件预览打开 `index.html`。\n")
                }
                "web-node" -> {
                    put(
                        "package.json",
                        """
                        {
                          "name": "$name",
                          "version": "0.1.0",
                          "private": true,
                          "type": "module",
                          "scripts": {
                            "start": "node src/index.js"
                          }
                        }
                        """.trimIndent(),
                    )
                    put("src/index.js", "console.log('hello from $name');\n")
                    put("README.md", "# $name\n\n```bash\nnpm start\n```\n若无 Node，先安装再运行。\n")
                }
                "android-app" -> {
                    put(
                        "README.md",
                        """
                        # $name

                        Android 工程入口由 AndMX Dev Forge 生成说明。

                        下一步：
                        1. 启用插件 `andmx-android-dev`
                        2. 使用 `android_preflight` / `android_create_app` / `android_build_and_run`

                        本脚手架不替代完整 Gradle 工程生成；复杂工程请用 android_create_app。
                        """.trimIndent(),
                    )
                    put(
                        ".andmx-forge/android.json",
                        """
                        {
                          "profile": "android-app",
                          "projectName": "$name",
                          "next": ["android_preflight", "android_create_app"]
                        }
                        """.trimIndent(),
                    )
                }
                "python-script" -> {
                    put("main.py", "def main():\n    print(\"hello from $name\")\n\nif __name__ == \"__main__\":\n    main()\n")
                    put("requirements.txt", "# add deps here\n")
                    put("README.md", "# $name\n\n```bash\npython3 main.py\n```\n")
                }
                "docs-markdown" -> {
                    put("README.md", "# $name\n\n文档入口。\n\n- [概述](docs/overview.md)\n")
                    put("docs/overview.md", "# 概述\n\n在此写下项目说明。\n")
                }
                "html-video-ready" -> {
                    put(
                        "README.md",
                        """
                        # $name — HTML Video Ready

                        在此工作区描述你要的影片，并启用 `andmx-html-video`：
                        - `html_video_init`
                        - 编写分镜/动效/音频
                        - `html_video_preview` / `html_video_deliver`
                        """.trimIndent(),
                    )
                    put(".andmx-forge/html-video.json", """{"profile":"html-video-ready","ready":true}""")
                }
                "agent-skill-pack" -> {
                    put(
                        "skills/example-skill/SKILL.md",
                        """
                        ---
                        name: example-skill
                        description: Example skill scaffolded by AndMX Dev Forge
                        ---

                        # Example Skill

                        Describe when to use this skill and the workflow.
                        """.trimIndent(),
                    )
                    put("README.md", "# $name skills\n\n将技能放在 `skills/*/SKILL.md`。\n")
                }
            }
            put(
                ".andmx-forge/scaffold.json",
                """
                {
                  "id": "${UUID.randomUUID()}",
                  "profileId": "${profile.id}",
                  "projectName": "$name",
                  "createdAt": ${System.currentTimeMillis()},
                  "files": ${written.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}
                }
                """.trimIndent(),
            )
            written += if (base.endsWith("/")) "${base}.andmx-forge/scaffold.json" else "$base/.andmx-forge/scaffold.json"
            ToolResult(
                buildString {
                    appendLine("type: forge_scaffold_project")
                    appendLine("profileId: ${profile.id}")
                    appendLine("baseDir: $base")
                    appendLine("projectName: $name")
                    appendLine("writtenCount: ${written.size}")
                    appendLine("written:")
                    written.distinct().forEach { appendLine("- $it") }
                    appendLine("nextPlugins: ${profile.nextPlugins.joinToString(",")}")
                    appendLine("uiHint: Summarize what was created; offer next env steps or handoff plugins.")
                },
            )
        }.getOrElse { ToolResult("scaffold failed: ${it.message}", isError = true) }
    }
}

private class ForgeWriteToolingTool(private val context: Context) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "forge_write_tooling"
    override val description = "Write common tooling files (.gitignore, editorconfig, Makefile) into current workspace."
    override val risk = ToolRisk.WRITE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("dir") { put("type", "string") }
            putJsonObject("gitignore") { put("type", "boolean") }
            putJsonObject("editorconfig") { put("type", "boolean") }
            putJsonObject("makefile") { put("type", "boolean") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val base = args.str("dir")?.let { access.resolvePath(it) } ?: access.guestCwd()
            val written = mutableListOf<String>()
            suspend fun put(name: String, content: String) {
                val path = "$base/$name"
                access.writeText(path, content)
                written += path
            }
            if (args.bool("gitignore") != false) {
                put(
                    ".gitignore",
                    """
                    .DS_Store
                    node_modules/
                    dist/
                    build/
                    .gradle/
                    local.properties
                    __pycache__/
                    .venv/
                    *.log
                    .andmx-forge/write-test*
                    """.trimIndent() + "\n",
                )
            }
            if (args.bool("editorconfig") != false) {
                put(
                    ".editorconfig",
                    """
                    root = true
                    [*]
                    charset = utf-8
                    end_of_line = lf
                    insert_final_newline = true
                    indent_style = space
                    indent_size = 2
                    """.trimIndent() + "\n",
                )
            }
            if (args.bool("makefile") == true) {
                put("Makefile", "all:\n\t@echo ok\n")
            }
            ToolResult(
                buildString {
                    appendLine("type: forge_write_tooling")
                    appendLine("baseDir: $base")
                    appendLine("written:")
                    written.forEach { appendLine("- $it") }
                    appendLine("uiHint: Brief Chinese note on tooling files added.")
                },
            )
        }.getOrElse { ToolResult("write_tooling failed: ${it.message}", isError = true) }
    }
}

private class ForgeProjectStatusTool(private val context: Context) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "forge_project_status"
    override val description = "Inspect current workspace for scaffold markers and common project files."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("dir") { put("type", "string") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val base = args.str("dir")?.let { access.resolvePath(it) } ?: access.guestCwd()
            val markers = listOf(
                "README.md", "index.html", "package.json", "main.py", "requirements.txt",
                "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts",
                ".andmx-forge/scaffold.json", ".gitignore",
            )
            val present = markers.filter { access.exists("$base/$it") }
            val missing = markers.filter { it !in present }
            val listing = runCatching { access.list(base).take(40) }.getOrDefault(emptyList())
            ToolResult(
                buildString {
                    appendLine("type: forge_project_status")
                    appendLine("baseDir: $base")
                    appendLine("present:")
                    if (present.isEmpty()) appendLine("[]") else present.forEach { appendLine("- $it") }
                    appendLine("missingCommon:")
                    missing.take(12).forEach { appendLine("- $it") }
                    appendLine("topEntries:")
                    listing.forEach { appendLine("- $it") }
                    appendLine("uiHint: Summarize readiness and next actions in Chinese.")
                },
            )
        }.getOrElse { ToolResult("project_status failed: ${it.message}", isError = true) }
    }
}
