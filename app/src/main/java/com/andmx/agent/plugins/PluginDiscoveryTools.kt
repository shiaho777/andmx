package com.andmx.agent.plugins

import com.andmx.agent.Tool
import com.andmx.agent.ToolArgs
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class SearchPluginsTool(
    private val loadCatalog: suspend () -> List<PluginMarketplace.CatalogPlugin>,
    private val installedNames: suspend () -> Set<String>,
    private val autoRecommendEnabled: () -> Boolean = { false },
) : Tool {
    override val name = "SearchPlugins"
    override val description =
        "按关键词检索插件市场目录（BM25 相关度排序），返回 name/description/score/installed。" +
            "只检索不安装；安装请用 SuggestPluginInstall 生成建议并由用户确认。"
    override val risk = ToolRisk.READ

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "检索关键词，支持中英文")
            }
            putJsonObject("count") {
                put("type", "integer")
                put("description", "返回条数，默认 10，最大 ${PluginSearch.MAX_COUNT}")
            }
        }
        putJsonArray("required") { add("query") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = ToolArgs.value(args, "query").trim()
        if (query.isBlank()) return ToolResult("缺少 query 参数", isError = true)
        val count = ToolArgs.value(args, "count").trim().toIntOrNull() ?: 10
        val catalog = runCatching { loadCatalog() }.getOrElse {
            return ToolResult("加载插件目录失败: ${it.message}", isError = true)
        }
        val installed = runCatching { installedNames() }.getOrDefault(emptySet())
            .map { it.lowercase() }.toSet()
        val ranked = PluginSearch.searchCatalog(query, catalog, count)
        val out = buildJsonObject {
            put("query", query)
            put("total", catalog.size)
            put("autoRecommend", autoRecommendEnabled())
            putJsonArray("results") {
                ranked.forEach { r ->
                    add(
                        buildJsonObject {
                            put("name", r.entry.name)
                            put("description", r.entry.description)
                            put("marketplace", r.entry.marketplace)
                            put("score", r.score)
                            put("installed", r.entry.name.lowercase() in installed)
                        },
                    )
                }
            }
        }
        return ToolResult(out.toString())
    }
}

class SuggestPluginInstallTool(
    private val loadCatalog: suspend () -> List<PluginMarketplace.CatalogPlugin>,
    private val installedNames: suspend () -> Set<String>,
) : Tool {
    override val name = "SuggestPluginInstall"
    override val description =
        "按插件名生成安装建议卡片数据（名称/描述/来源/安装命令）。本工具只建议、不安装；" +
            "须向用户展示建议并获得明确确认后，才走现有市场安装链 PluginSystem.installFromMarketplace。"
    override val risk = ToolRisk.READ

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("pluginName") {
                put("type", "string")
                put("description", "市场目录中的插件名")
            }
        }
        putJsonArray("required") { add("pluginName") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val pluginName = ToolArgs.value(args, "pluginName").trim()
        if (pluginName.isBlank()) return ToolResult("缺少 pluginName 参数", isError = true)
        val catalog = runCatching { loadCatalog() }.getOrElse {
            return ToolResult("加载插件目录失败: ${it.message}", isError = true)
        }
        val entry = catalog.firstOrNull { it.name.equals(pluginName, ignoreCase = true) }
            ?: return ToolResult("市场目录中未找到插件: $pluginName", isError = true)
        val installed = runCatching { installedNames() }.getOrDefault(emptySet())
            .any { it.equals(entry.name, ignoreCase = true) }
        return ToolResult(suggestionJson(entry, installed).toString())
    }

    companion object {
        fun suggestionJson(entry: PluginMarketplace.CatalogPlugin, installed: Boolean): JsonObject =
            buildJsonObject {
                put("type", "plugin_install_suggestion")
                put("name", entry.name)
                put("description", entry.description)
                put("author", entry.author)
                put("version", entry.version)
                put("marketplace", entry.marketplace)
                putJsonObject("source") {
                    val src = entry.source
                    put("type", src?.type.orEmpty())
                    put("url", src?.url.orEmpty())
                    put("path", src?.path.orEmpty())
                    put("repo", src?.repo.orEmpty())
                    put("id", src?.id.orEmpty())
                }
                put("installed", installed)
                put("installCommand", installCommand(entry))
                put("installPath", "PluginSystem.installFromMarketplace")
                put("requiresUserConfirmation", true)
                put(
                    "note",
                    if (installed) {
                        "该插件已安装，无需重复安装。"
                    } else {
                        "仅为安装建议：向用户展示本卡片并获得明确确认后，才调用现有市场安装链执行安装。"
                    },
                )
            }

        fun installCommand(entry: PluginMarketplace.CatalogPlugin): String {
            val market = entry.marketplace.ifBlank { PluginMarketplace.ANDMX_ID }
            return "/plugin install ${entry.name}@$market"
        }
    }
}