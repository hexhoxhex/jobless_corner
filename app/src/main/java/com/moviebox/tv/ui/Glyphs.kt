package com.moviebox.tv.ui

/**
 * Strips emoji and pictograph glyphs from text we put on screen.
 *
 * The live schedule arrives decorated: country flags, sport balls, a TV set
 * in front of every show ("📺 NCIS S24, E1"), a megaphone, a speech bubble —
 * about 900 of them on a measured day, in titles, in some category headings
 * ("Tennis 🎾 ATP - Singles") and in 40 channel names. On the TV they render
 * as mismatched colour emoji or empty boxes and read as clutter, so the user
 * asked for none at all.
 *
 * Display-only on purpose. The matching code still needs the raw text: the
 * leading football is one of the three signals [com.moviebox.tv.data.live
 * .LeagueCatalog.isFootball] uses, and follow / reminder keys are built from
 * the titles as published — rewriting them where the schedule enters the app
 * would re-key every armed reminder.
 *
 * Arrows, the bullet separator and dashes are punctuation, not pictographs,
 * and stay.
 */
object Glyphs {

    private val SPACES = Regex("""\s{2,}""")

    fun plain(s: String): String {
        if (s.isEmpty()) return s
        var sb: StringBuilder? = null
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (isPictograph(cp)) {
                if (sb == null) sb = StringBuilder(s.length).append(s, 0, i)
                sb.append(' ')
            } else {
                sb?.appendCodePoint(cp)
            }
            i += n
        }
        return sb?.let { SPACES.replace(it, " ").trim() } ?: s
    }

    private fun isPictograph(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||   // emoji, incl. regional-indicator flags
            cp in 0x2600..0x27BF ||  // misc symbols + dingbats (football, tick, cross)
            cp in 0x2300..0x23FF ||  // technical: media keys, hourglass, watch
            cp in 0x2B00..0x2BFF ||  // arrows-as-icons, star, large shapes
            cp in 0xE0000..0xE007F || // tag sequence of the England/Scotland flags
            cp == 0xFE0F || cp == 0xFE0E || // variation selectors
            cp == 0x200D ||          // zero-width joiner
            cp == 0x20E3             // combining keycap
}
