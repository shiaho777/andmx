package com.andmx.agent.ext

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SlotDeclaration(
    val pluginId: String,
    val slotId: String,
    val title: String,
    val htmlAsset: String,
)

object ExtensionSlotRegistry {
    const val COMPOSER_STATUS = "composer.status"
    const val SIDE_PANE_LAUNCHER = "sidePane.launcher"
    const val NAV_PRIMARY = "nav.primary"
    const val FILE_VIEWER = "file.viewer"
    const val COMPOSER_MENTION = "composer.mention"

    val KNOWN_SLOTS: Set<String> = setOf(
        COMPOSER_STATUS,
        SIDE_PANE_LAUNCHER,
        NAV_PRIMARY,
        FILE_VIEWER,
        COMPOSER_MENTION,
    )

    private val _declarations = MutableStateFlow<List<SlotDeclaration>>(emptyList())
    val declarations: StateFlow<List<SlotDeclaration>> = _declarations.asStateFlow()

    fun register(declaration: SlotDeclaration): Boolean {
        if (declaration.pluginId.isBlank() || declaration.slotId !in KNOWN_SLOTS) return false
        if (!isSafeAssetPath(declaration.htmlAsset)) return false
        synchronized(this) {
            _declarations.value = _declarations.value.filterNot {
                it.pluginId == declaration.pluginId && it.slotId == declaration.slotId
            } + declaration
        }
        return true
    }

    fun forSlot(slotId: String): List<SlotDeclaration> =
        _declarations.value.filter { it.slotId == slotId }

    fun forPlugin(pluginId: String): List<SlotDeclaration> =
        _declarations.value.filter { it.pluginId == pluginId }

    fun unregisterPlugin(pluginId: String) {
        synchronized(this) {
            _declarations.value = _declarations.value.filterNot { it.pluginId == pluginId }
        }
    }

    fun clear() {
        synchronized(this) { _declarations.value = emptyList() }
    }

    fun isSafeAssetPath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith("/") &&
            !path.contains("..") &&
            !path.contains(":") &&
            !path.contains("\\")
}
