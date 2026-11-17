package com.andmx.agent.office

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object Ooxml {
    const val XML_DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    const val NS_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
    const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    const val NS_S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val REL_OFFICE_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"

    fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch.code >= 32 || ch == '\n' || ch == '\t') append(ch)
            }
        }
    }

    fun zip(entries: List<Pair<String, String>>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    fun rels(vararg relations: Triple<String, String, String>): String = buildString {
        append(XML_DECL)
        append("""<Relationships xmlns="$NS_REL">""")
        for ((id, type, target) in relations) {
            append("""<Relationship Id="$id" Type="$type" Target="$target"/>""")
        }
        append("</Relationships>")
    }

    fun contentTypes(defaults: List<Pair<String, String>>, overrides: List<Pair<String, String>>): String = buildString {
        append(XML_DECL)
        append("""<Types xmlns="$NS_CT">""")
        for ((extension, type) in defaults) {
            append("""<Default Extension="$extension" ContentType="$type"/>""")
        }
        for ((partName, type) in overrides) {
            append("""<Override PartName="$partName" ContentType="$type"/>""")
        }
        append("</Types>")
    }

    fun rootRels(target: String): String =
        rels(Triple("rId1", REL_OFFICE_DOC, target))
}
