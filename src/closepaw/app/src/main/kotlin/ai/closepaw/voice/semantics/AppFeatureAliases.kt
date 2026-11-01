package ai.closepaw.voice.semantics

/**
 * Spoken names for in-app features, mapped to one feature identity plus the UI labels a screen may
 * legitimately use for it.
 *
 * Round 40 (review 6551941593, item 2): `appFeatureWords` listed 「扫一扫」 and 「扫码」 side by side
 * but the matched word went into `params["feature"]` untouched, and the UI lookup compared that word
 * against screen labels literally. Saying 「打开微信扫码」 against a button labelled 「扫一扫」
 * therefore ended in `not-found` although the feature was right there. The spoken word is now
 * normalised to one identity, and the lookup accepts the identity *and* its known label variants.
 *
 * This table is vocabulary, not evidence: whether WeChat 8.x really labels the entry 「扫一扫」,
 * 「扫一扫，打开相机」 or something else is **unverified without a device**.
 */
object AppFeatureAliases {

    /** Each group is one feature identity followed by the words/labels that may stand for it. */
    private val groups: List<List<String>> = listOf(
        listOf("扫一扫", "扫码", "扫描", "扫二维码"),
        listOf("付款码", "收付款", "收钱码"),
    )

    /** Canonical feature identity for a spoken word; unknown words are their own identity. */
    fun canonical(word: String): String {
        val w = word.trim()
        if (w.isEmpty()) return w
        val group = groups.firstOrNull { g -> g.any { it == w || w.contains(it) } } ?: return w
        return group.first()
    }

    /**
     * UI labels that may stand for [feature] on screen. The identity itself is always included, so an
     * alias never *narrows* what can be matched — it only adds the spoken variants.
     */
    fun labelsFor(feature: String): List<String> {
        val id = canonical(feature)
        if (id.isEmpty()) return emptyList()
        val group = groups.firstOrNull { it.first() == id }
        return if (group == null) listOf(id) else group.distinct()
    }
}
