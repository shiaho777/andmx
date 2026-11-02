package com.andmx.ui2.settings

/**
 * 设置首页搜索：标题、副标题、分组名与枚举名都参与匹配。
 * 纯函数，便于 JVM 回归测试。
 */
internal fun filterSettingsGroups(
    query: String,
    groups: List<Pair<String, List<SettingsPage>>>,
    descriptions: Map<SettingsPage, String>,
    translate: (String) -> String = ::tr,
): List<Pair<String, List<SettingsPage>>> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return groups
    return groups.map { (title, items) ->
        val groupHit = translate(title).lowercase().contains(needle)
        title to items.filter { item ->
            groupHit ||
                item.name.lowercase().contains(needle) ||
                translate(item.title).lowercase().contains(needle) ||
                translate(descriptions[item].orEmpty()).lowercase().contains(needle)
        }
    }.filter { it.second.isNotEmpty() }
}
