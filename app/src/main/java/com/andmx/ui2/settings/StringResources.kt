package com.andmx.ui2.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.andmx.R
import java.util.Locale

object AppStrings {
    private var zhMap: Map<String, String> = emptyMap()
    private var enMap: Map<String, String> = emptyMap()

    fun init(context: Context) {
        if (enMap.isNotEmpty()) return
        val appContext = context.applicationContext
        val zhContext = appContext.createConfigurationContext(
            Configuration(appContext.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        )
        val enContext = appContext.createConfigurationContext(
            Configuration(appContext.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        )
        val zhResources = zhContext.resources
        val enResources = enContext.resources
        val zh = HashMap<String, String>()
        val en = HashMap<String, String>()
        for (field in R.string::class.java.fields) {
            if (!field.name.startsWith("str_")) continue
            val id = runCatching { field.getInt(null) }.getOrNull() ?: continue
            val zhValue = runCatching { zhResources.getString(id) }.getOrNull() ?: continue
            val enValue = runCatching { enResources.getString(id) }.getOrNull() ?: continue
            zh.putIfAbsent(zhValue, zhValue)
            en.putIfAbsent(zhValue, enValue)
        }
        zhMap = zh
        enMap = en
    }

    fun lookup(text: String): String? =
        if (UiLang.tag == "en") enMap[text] else zhMap[text]
}