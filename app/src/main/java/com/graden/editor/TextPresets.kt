package com.graden.editor

/**
 * Ready-made texts for the media box's Text section: a heading, subheading and body to start from, then groups of
 * styles (each a font, colours, edge, box and decoration together). Each is a [Caption] added by hand ([Caption.TEXT]),
 * placed in the middle of the video unless it says otherwise; everything about it can be changed after.
 */
object TextPresets {
    class Preset(val name: String, val cap: Caption)
    class Group(val name: String, val presets: List<Preset>)

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val ACCENT = 0xFFFFB547.toInt()

    private fun text(t: String, scale: Float, look: TextLook, x: Float = 0.5f, y: Float = 0.5f) =
        Caption(t, Caption.TEXT, scale = scale, x = x, y = y, look = look)

    /** No edge, a soft shadow: clean text for titles. */
    private val clean = TextLook(stroke = 0f, shadow = 0.12f, shadowColor = 0x73000000)

    val QUICK = listOf(
        Preset("Add a heading", text("Heading", 2.2f, clean.copy(font = "inter", weight = 800))),
        Preset("Add a subheading", text("Subheading", 1.5f, clean.copy(font = "inter", weight = 600))),
        Preset("Add a little bit of body text", text("Body text", 1.0f, clean.copy(font = "inter", weight = 400))),
    )

    val GROUPS = listOf(
        Group("Basic", listOf(
            Preset("Clean", text("Clean", 1.8f, clean.copy(font = "inter", weight = 700))),
            Preset("Outline", text("OUTLINE", 1.8f, TextLook(font = "montserrat", weight = 900, stroke = 0.2f, strokeColor = BLACK, shadow = 0f))),
            Preset("Boxed", text("Boxed text", 1.4f, TextLook(font = "poppins", weight = 700, stroke = 0f, shadow = 0f, box = 0xCC000000.toInt(), boxRound = 0.18f))),
            Preset("Highlight", text("HIGHLIGHT", 1.5f, TextLook(font = "archivoblack", weight = 400, color = BLACK, stroke = 0f, shadow = 0f, box = 0xFFFFE14D.toInt(), boxRound = 0.08f))),
            Preset("Soft", text("Soft and simple", 1.4f, clean.copy(font = "rubik", weight = 500, shadow = 0.25f))),
            Preset("Bubble", text("Bubble", 1.5f, TextLook(font = "baloo", weight = 800, color = BLACK, stroke = 0f, shadow = 0f, box = WHITE, boxRound = 0.6f))),
        )),
        Group("Titles", listOf(
            Preset("Title + subtitle", text("TITLE\nSUBTITLE", 2.2f, clean.copy(font = "montserrat", weight = 800, sub = 0.45f, subColor = 0xFFB9BCC6.toInt(), letter = 0.02f))),
            Preset("Ruled", text("VIDEO TITLE", 1.4f, clean.copy(font = "oswald", weight = 500, letter = 0.14f, decor = TextLook.DECOR_RULES))),
            Preset("Underline", text("HEADLINE", 2.4f, TextLook(font = "bebas", weight = 400, stroke = 0f, shadow = 0.1f, decor = TextLook.DECOR_UNDERLINE, decorColor = ACCENT))),
            Preset("Framed", text("Chapter One", 1.5f, clean.copy(font = "playfair", weight = 500, italic = true, decor = TextLook.DECOR_FRAME))),
            Preset("Cinema", text("THE JOURNEY", 1.6f, clean.copy(font = "playfair", weight = 400, letter = 0.32f, shadow = 0.3f))),
            Preset("Grotesk", text("New drop", 2.4f, clean.copy(font = "bricolage", weight = 800, letter = -0.03f))),
            Preset("Wide", text("EXPLORE", 1.4f, clean.copy(font = "syne", weight = 700, letter = 0.5f))),
        )),
        Group("Lower thirds", listOf(
            Preset("Name bar", text("Your Name\nWhat you do", 1.25f, clean.copy(font = "poppins", weight = 700, align = 0, sub = 0.62f, subColor = 0xFFD2D4DB.toInt(),
                decor = TextLook.DECOR_SIDEBAR, decorColor = ACCENT), x = 0.3f, y = 0.8f)),
            Preset("Tag", text("LOCATION", 1.0f, TextLook(font = "spacegrotesk", weight = 700, color = BLACK, stroke = 0f, shadow = 0f, box = ACCENT, boxRound = 0.1f, letter = 0.1f), x = 0.22f, y = 0.84f)),
            Preset("Minimal", text("Speaker name\nTitle · Company", 1.1f, clean.copy(font = "inter", weight = 600, align = 0, sub = 0.7f), x = 0.3f, y = 0.82f)),
            Preset("Strip", text("BREAKING", 1.1f, TextLook(font = "oswald", weight = 600, stroke = 0f, shadow = 0f, box = 0xE6D7263D.toInt(), boxRound = 0f, letter = 0.08f), x = 0.2f, y = 0.85f)),
        )),
        Group("Social", listOf(
            Preset("Bold pop", text("WAIT FOR IT", 2.0f, TextLook(font = "bangers", weight = 400, color = 0xFFFFE14D.toInt(), stroke = 0.22f, strokeColor = BLACK, shadow = 0f, letter = 0.04f))),
            Preset("Marker", text("so good", 2.0f, TextLook(font = "marker", weight = 400, stroke = 0.16f, strokeColor = BLACK, shadow = 0f))),
            Preset("Neon", text("NEON", 2.4f, TextLook(font = "righteous", weight = 400, color = 0xFF7DF9FF.toInt(), stroke = 0f, shadow = 0.5f, shadowColor = 0xFF00D5FF.toInt()))),
            Preset("Retro", text("Retro", 2.2f, TextLook(font = "lobster", weight = 400, color = 0xFFFF7AB6.toInt(), stroke = 0.12f, strokeColor = 0xFF2B0A3D.toInt(), shadow = 0f))),
            Preset("Hype", text("NO WAY", 2.4f, TextLook(font = "anton", weight = 400, stroke = 0.14f, strokeColor = BLACK, shadow = 0.2f))),
            Preset("Sticker", text("OMG", 2.0f, TextLook(font = "baloo", weight = 800, color = 0xFFFF4D6D.toInt(), stroke = 0.3f, strokeColor = WHITE, shadow = 0.15f))),
        )),
        Group("Script", listOf(
            Preset("Signature", text("Memories", 2.2f, clean.copy(font = "dancing", weight = 700))),
            Preset("Pacifico", text("Summer days", 1.8f, clean.copy(font = "pacifico", weight = 400))),
            Preset("Note", text("note to self", 2.0f, clean.copy(font = "caveat", weight = 600, color = 0xFFFFF3B0.toInt()))),
            Preset("Elegant", text("Forever", 2.0f, clean.copy(font = "dmserif", weight = 400, italic = true))),
        )),
        Group("हिन्दी", listOf(
            Preset("शीर्षक", text("शीर्षक", 2.2f, TextLook(font = "baloo", weight = 800, stroke = 0.16f, strokeColor = BLACK, shadow = 0f))),
            Preset("Teko", text("नमस्ते", 2.4f, clean.copy(font = "teko", weight = 600))),
            Preset("Kalam", text("दिल से", 2.0f, clean.copy(font = "kalam", weight = 700, color = 0xFFFFE6A8.toInt()))),
            Preset("Box", text("ज़रूर देखें", 1.5f, TextLook(font = "notodeva", weight = 700, color = BLACK, stroke = 0f, shadow = 0f, box = 0xFFFFE14D.toInt(), boxRound = 0.12f))),
        )),
        Group("বাংলা", listOf(
            Preset("শিরোনাম", text("শিরোনাম", 2.2f, clean.copy(font = "galada", weight = 400))),
            Preset("Hind", text("ভালোবাসা", 1.8f, TextLook(font = "hindsiliguri", weight = 700, stroke = 0.14f, strokeColor = BLACK, shadow = 0f))),
            Preset("Box", text("দেখুন", 1.5f, TextLook(font = "notobeng", weight = 700, stroke = 0f, shadow = 0f, box = 0xCC000000.toInt()))),
        )),
    )
}
