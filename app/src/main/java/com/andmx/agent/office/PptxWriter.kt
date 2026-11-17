package com.andmx.agent.office

object PptxWriter {
    private const val CT_PRESENTATION =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    private const val CT_SLIDE =
        "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    private const val CT_LAYOUT =
        "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
    private const val CT_MASTER =
        "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"
    private const val CT_THEME =
        "application/vnd.openxmlformats-officedocument.theme+xml"
    private const val CT_RELS = "application/vnd.openxmlformats-package.relationships+xml"
    private const val REL_BASE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
    private const val SLIDE_WIDTH = 12192000L
    private const val SLIDE_HEIGHT = 6858000L

    fun write(model: DocumentModel): ByteArray = writeSlides(model.toSlides())

    fun writeSlides(slides: List<Slide>): ByteArray {
        val pages = slides.ifEmpty { listOf(Slide("", emptyList())) }
        val entries = ArrayList<Pair<String, String>>()
        val overrides = ArrayList<Pair<String, String>>()
        overrides += "/ppt/presentation.xml" to CT_PRESENTATION
        overrides += "/ppt/slideMasters/slideMaster1.xml" to CT_MASTER
        overrides += "/ppt/slideLayouts/slideLayout1.xml" to CT_LAYOUT
        overrides += "/ppt/theme/theme1.xml" to CT_THEME
        pages.indices.forEach { i -> overrides += "/ppt/slides/slide${i + 1}.xml" to CT_SLIDE }
        entries += "[Content_Types].xml" to Ooxml.contentTypes(
            defaults = listOf("rels" to CT_RELS, "xml" to "application/xml"),
            overrides = overrides,
        )
        entries += "_rels/.rels" to Ooxml.rootRels("ppt/presentation.xml")
        entries += "ppt/presentation.xml" to presentationXml(pages.size)
        val presentationRels = ArrayList<Triple<String, String, String>>()
        presentationRels += Triple("rId1", REL_BASE + "slideMaster", "slideMasters/slideMaster1.xml")
        pages.indices.forEach { i ->
            presentationRels += Triple("rId${i + 2}", REL_BASE + "slide", "slides/slide${i + 1}.xml")
        }
        entries += "ppt/_rels/presentation.xml.rels" to Ooxml.rels(*presentationRels.toTypedArray())
        pages.forEachIndexed { i, slide ->
            entries += "ppt/slides/slide${i + 1}.xml" to slideXml(slide)
            entries += "ppt/slides/_rels/slide${i + 1}.xml.rels" to Ooxml.rels(
                Triple("rId1", REL_BASE + "slideLayout", "../slideLayouts/slideLayout1.xml"),
            )
        }
        entries += "ppt/slideLayouts/slideLayout1.xml" to layoutXml()
        entries += "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to Ooxml.rels(
            Triple("rId1", REL_BASE + "slideMaster", "../slideMasters/slideMaster1.xml"),
        )
        entries += "ppt/slideMasters/slideMaster1.xml" to masterXml()
        entries += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to Ooxml.rels(
            Triple("rId1", REL_BASE + "slideLayout", "../slideLayouts/slideLayout1.xml"),
            Triple("rId2", REL_BASE + "theme", "../theme/theme1.xml"),
        )
        entries += "ppt/theme/theme1.xml" to themeXml()
        return Ooxml.zip(entries)
    }

    private fun presentationXml(slideCount: Int): String = buildString {
        append(Ooxml.XML_DECL)
        append("""<p:presentation xmlns:a="${Ooxml.NS_A}" xmlns:r="${Ooxml.NS_R}" xmlns:p="${Ooxml.NS_P}">""")
        append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""")
        append("<p:sldIdLst>")
        for (i in 0 until slideCount) {
            append("""<p:sldId id="${256 + i}" r:id="rId${i + 2}"/>""")
        }
        append("</p:sldIdLst>")
        append("""<p:sldSz cx="$SLIDE_WIDTH" cy="$SLIDE_HEIGHT"/><p:notesSz cx="$SLIDE_HEIGHT" cy="$SLIDE_WIDTH"/>""")
        append("</p:presentation>")
    }

    internal fun slideXml(slide: Slide): String = buildString {
        append(Ooxml.XML_DECL)
        append("""<p:sld xmlns:a="${Ooxml.NS_A}" xmlns:r="${Ooxml.NS_R}" xmlns:p="${Ooxml.NS_P}">""")
        append("<p:cSld><p:spTree>")
        append("""<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""")
        append("""<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""")
        append(textBox(id = 2, name = "Title", placeholder = "title", x = 838200, y = 365125, cx = 10515600, cy = 1325563, lines = listOf(slide.title), fontSize = 3200, bold = true))
        append(textBox(id = 3, name = "Body", placeholder = "body", x = 838200, y = 1825625, cx = 10515600, cy = 4351338, lines = slide.body, fontSize = 1800, bold = false))
        append("</p:spTree></p:cSld>")
        append("""<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>""")
        append("</p:sld>")
    }

    private fun textBox(
        id: Int,
        name: String,
        placeholder: String,
        x: Long,
        y: Long,
        cx: Long,
        cy: Long,
        lines: List<String>,
        fontSize: Int,
        bold: Boolean,
    ): String = buildString {
        append("<p:sp>")
        append("""<p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>""")
        append("""<p:nvPr><p:ph type="$placeholder"${if (placeholder == "body") """ idx="1"""" else ""}/></p:nvPr></p:nvSpPr>""")
        append("""<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""")
        append("""<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>""")
        append("<p:txBody><a:bodyPr/><a:lstStyle/>")
        val effective = lines.ifEmpty { listOf("") }
        for (line in effective) {
            append("<a:p>")
            if (line.isNotEmpty()) {
                append("""<a:r><a:rPr lang="zh-CN" sz="$fontSize"${if (bold) """ b="1"""" else ""} dirty="0"/><a:t>""")
                append(Ooxml.escape(line))
                append("</a:t></a:r>")
            } else {
                append("<a:endParaRPr lang=\"zh-CN\"/>")
            }
            append("</a:p>")
        }
        append("</p:txBody></p:sp>")
    }

    private fun layoutXml(): String =
        Ooxml.XML_DECL +
            """<p:sldLayout xmlns:a="${Ooxml.NS_A}" xmlns:r="${Ooxml.NS_R}" xmlns:p="${Ooxml.NS_P}" type="blank" preserve="1">""" +
            """<p:cSld name="Blank"><p:spTree>""" +
            """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
            """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private fun masterXml(): String =
        Ooxml.XML_DECL +
            """<p:sldMaster xmlns:a="${Ooxml.NS_A}" xmlns:r="${Ooxml.NS_R}" xmlns:p="${Ooxml.NS_P}">""" +
            """<p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>""" +
            """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
            """</p:spTree></p:cSld>""" +
            """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
            """<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>""" +
            """<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="3200"/></a:lvl1pPr></p:titleStyle>""" +
            """<p:bodyStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:bodyStyle>""" +
            """<p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles>""" +
            """</p:sldMaster>"""

    private fun themeXml(): String {
        val colors = listOf(
            "dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "44546A", "lt2" to "E7E6E6",
            "accent1" to "4472C4", "accent2" to "ED7D31", "accent3" to "A5A5A5",
            "accent4" to "FFC000", "accent5" to "5B9BD5", "accent6" to "70AD47",
            "hlink" to "0563C1", "folHlink" to "954F72",
        )
        val scheme = colors.joinToString("") { (name, rgb) -> """<a:$name><a:srgbClr val="$rgb"/></a:$name>""" }
        val solidFill = """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""
        val line = """<a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>"""
        val effect = """<a:effectStyle><a:effectLst/></a:effectStyle>"""
        return Ooxml.XML_DECL +
            """<a:theme xmlns:a="${Ooxml.NS_A}" name="AndMX">""" +
            """<a:themeElements><a:clrScheme name="AndMX">$scheme</a:clrScheme>""" +
            """<a:fontScheme name="AndMX"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>""" +
            """<a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>""" +
            """<a:fmtScheme name="AndMX">""" +
            """<a:fillStyleLst>$solidFill$solidFill$solidFill</a:fillStyleLst>""" +
            """<a:lnStyleLst>$line$line$line</a:lnStyleLst>""" +
            """<a:effectStyleLst>$effect$effect$effect</a:effectStyleLst>""" +
            """<a:bgFillStyleLst>$solidFill$solidFill$solidFill</a:bgFillStyleLst>""" +
            """</a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""
    }
}
