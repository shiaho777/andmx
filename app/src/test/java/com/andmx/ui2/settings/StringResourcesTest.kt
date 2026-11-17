package com.andmx.ui2.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StringResourcesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppStrings.init(context)
    }

    @Test
    fun zhResourceSamples() {
        UiLang.apply("zh-CN")
        assertEquals("重试", AppStrings.lookup("重试"))
        assertEquals("确认", AppStrings.lookup("确认"))
        assertEquals("加载中…", AppStrings.lookup("加载中…"))
        assertEquals("例如 <file-path>", AppStrings.lookup("例如 <file-path>"))
        assertEquals("动态取色", AppStrings.lookup("动态取色"))
    }

    @Test
    fun enResourceSamples() {
        UiLang.apply("en-US")
        assertEquals("Retry", AppStrings.lookup("重试"))
        assertEquals("Confirm", AppStrings.lookup("确认"))
        assertEquals("Loading…", AppStrings.lookup("加载中…"))
        assertEquals("For example <file-path>", AppStrings.lookup("例如 <file-path>"))
        assertEquals("Dynamic Color", AppStrings.lookup("动态取色"))
    }

    @Test
    fun trExactDictionaryHit() {
        UiLang.apply("en")
        assertEquals("Retry", tr("重试"))
        assertEquals("Speech recognition failed", tr("语音识别失败"))
    }

    @Test
    fun trPassThroughWhenZh() {
        UiLang.apply("zh")
        assertEquals("重试", tr("重试"))
    }

    @Test
    fun trUnknownTextReturnsInput() {
        UiLang.apply("en")
        assertEquals("完全不存在的界面文案XYZ", tr("完全不存在的界面文案XYZ"))
    }

    @Test
    fun trTemplatePath() {
        UiLang.apply("en")
        assertEquals("3 done", tr("已完成 3 项"))
        assertEquals("Uncommitted changes: 5 files", tr("未提交的更改：5 个文件"))
        assertEquals("Session #42", tr("会话 #42"))
    }

    private fun sourceString(dir: String, name: String): String {
        val relative = "src/test/res/$dir/strings.xml"
        val file = listOf(java.io.File(relative), java.io.File("app/$relative")).first { it.isFile }
        val parser = android.util.Xml.newPullParser()
        file.inputStream().use { input ->
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG &&
                    parser.name == "string" &&
                    parser.getAttributeValue(null, "name") == name
                ) {
                    parser.next()
                    return parser.text
                }
                event = parser.next()
            }
        }
        throw AssertionError("entry $name not found in $dir")
    }

    private fun androidUnquote(s: String): String {
        val body = if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                i++
                when (body[i]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(body[i])
                }
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

    @Test
    fun ampersandEntriesDecodeCorrectly() {
        assertEquals("检索 & 排序", androidUnquote(sourceString("values", "str_9001")))
        assertEquals("Search & sort", androidUnquote(sourceString("values-en", "str_9001")))
    }

    @Test
    fun percentEntriesDecodeCorrectly() {
        assertEquals("占比 50% 以上", androidUnquote(sourceString("values", "str_9002")))
        assertEquals("Over 50% share", androidUnquote(sourceString("values-en", "str_9002")))
    }

    @Test
    fun quoteAndNewlineEntriesDecodeCorrectly() {
        UiLang.apply("en")
        assertEquals("Delete subagent \"", AppStrings.lookup("确定要删除子智能体「"))
        assertEquals("Delete \"", AppStrings.lookup("确定删除「"))
        assertEquals("Commands:", AppStrings.lookup("可用命令：\\n"))
        assertEquals("Usage `/mode <id>`", AppStrings.lookup("\n用法 `/mode <id>`"))
    }
}