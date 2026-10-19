package com.graden.editor

import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * The fonts texts can use, built into the app (assets/fonts, all under the SIL Open Font License or Apache 2.0 —
 * licences in assets/fonts/licenses). Most are variable fonts, so any weight comes from the one file. Letters a font
 * hasn't got (Devanagari in a Latin font, say) are drawn from the phone's own fonts.
 */
object TextFonts {
    /** [file]: regular (or variable) file; [bold]: a separate bold file for static fonts; [variable]: has a weight axis. */
    class Font(val id: String, val name: String, val group: String, val file: String, val bold: String? = null, val variable: Boolean = false)

    val ALL = listOf(
        Font("inter", "Inter", "Sans", "Inter.ttf", variable = true),
        Font("poppins", "Poppins", "Sans", "Poppins-Regular.ttf", "Poppins-Bold.ttf"),
        Font("montserrat", "Montserrat", "Sans", "Montserrat.ttf", variable = true),
        Font("rubik", "Rubik", "Sans", "Rubik.ttf", variable = true),
        Font("spacegrotesk", "Space Grotesk", "Sans", "SpaceGrotesk.ttf", variable = true),
        Font("bricolage", "Bricolage", "Sans", "BricolageGrotesque.ttf", variable = true),
        Font("syne", "Syne", "Sans", "Syne.ttf", variable = true),
        Font("oswald", "Oswald", "Display", "Oswald.ttf", variable = true),
        Font("bebas", "Bebas Neue", "Display", "BebasNeue-Regular.ttf"),
        Font("anton", "Anton", "Display", "Anton-Regular.ttf"),
        Font("archivoblack", "Archivo Black", "Display", "ArchivoBlack-Regular.ttf"),
        Font("bangers", "Bangers", "Display", "Bangers-Regular.ttf"),
        Font("righteous", "Righteous", "Display", "Righteous-Regular.ttf"),
        Font("playfair", "Playfair Display", "Serif", "PlayfairDisplay.ttf", variable = true),
        Font("lora", "Lora", "Serif", "Lora.ttf", variable = true),
        Font("dmserif", "DM Serif", "Serif", "DMSerifDisplay-Regular.ttf"),
        Font("dancing", "Dancing Script", "Script", "DancingScript.ttf", variable = true),
        Font("pacifico", "Pacifico", "Script", "Pacifico-Regular.ttf"),
        Font("lobster", "Lobster", "Script", "Lobster-Regular.ttf"),
        Font("caveat", "Caveat", "Hand", "Caveat.ttf", variable = true),
        Font("marker", "Permanent Marker", "Hand", "PermanentMarker-Regular.ttf"),
        Font("notodeva", "Noto Sans हिन्दी", "हिन्दी", "NotoSansDevanagari.ttf", variable = true),
        Font("baloo", "Baloo 2", "हिन्दी", "Baloo2.ttf", variable = true),
        Font("teko", "Teko", "हिन्दी", "Teko.ttf", variable = true),
        Font("kalam", "Kalam", "हिन्दी", "Kalam-Regular.ttf", "Kalam-Bold.ttf"),
        Font("notobeng", "Noto Sans বাংলা", "বাংলা", "NotoSansBengali.ttf", variable = true),
        Font("hindsiliguri", "Hind Siliguri", "বাংলা", "HindSiliguri-Regular.ttf", "HindSiliguri-Bold.ttf"),
        Font("galada", "Galada", "বাংলা", "Galada-Regular.ttf"),
    )
    val GROUPS = ALL.map { it.group }.distinct()
    private val byId = ALL.associateBy { it.id }
    fun of(id: String) = byId[id] ?: ALL.first()

    /** Set once at start (the app's assets). Without it texts use the phone's sans-serif. */
    @Volatile var assets: AssetManager? = null

    private val cache = HashMap<String, Typeface>()

    /** [id] at [weight] (100..900). */
    fun typeface(id: String, weight: Int): Typeface {
        val f = of(id)
        val w = weight.coerceIn(100, 900)
        val key = if (f.variable) "$id@$w" else "$id@${if (w >= 600 && f.bold != null) 'b' else 'r'}"
        synchronized(cache) { cache[key]?.let { return it } }
        val am = assets
        val t = runCatching {
            when {
                am == null -> Typeface.create("sans-serif", Typeface.NORMAL)
                f.variable -> Typeface.Builder(am, "fonts/${f.file}").setFontVariationSettings("'wght' $w").build()
                w >= 600 && f.bold != null -> Typeface.createFromAsset(am, "fonts/${f.bold}")
                else -> Typeface.createFromAsset(am, "fonts/${f.file}")
            }
        }.getOrNull() ?: Typeface.DEFAULT
        synchronized(cache) { cache[key] = t }
        return t
    }

    /** Fonts without their own bold are made bolder by drawing over: whether [id] at [weight] needs that. */
    fun fakeBold(id: String, weight: Int): Boolean { val f = of(id); return !f.variable && f.bold == null && weight >= 700 }
}
