package tech.miyukivigil.totem.core

import org.json.JSONObject

/**
 * Brand logos for accounts, from the bundled `brand-icons.json` (Simple Icons, built by
 * `scripts/brand-icons.mjs`). Everything is offline: looking up an icon never tells anyone which
 * services you use.
 */
class BrandIcons(json: String) {
    /** [path] is SVG path data on a 24×24 grid; [hex] the brand color without `#`. */
    data class Icon(val slug: String, val title: String, val hex: String, val path: String)

    private val icons: List<Icon>
    private val bySlug: Map<String, Icon>
    /** Normalized title / slug / alias → icon. */
    private val byName: Map<String, Icon>

    init {
        val list = JSONObject(json).getJSONArray("icons")
        val loaded = ArrayList<Icon>(list.length())
        val names = HashMap<String, Icon>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val icon = Icon(o.getString("s"), o.getString("t"), o.getString("h"), o.getString("p"))
            loaded += icon
            // Titles win over aliases when two icons claim the same name.
            names.putIfAbsent(normalize(icon.title), icon)
            names.putIfAbsent(normalize(icon.slug), icon)
        }
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val aliases = o.optJSONArray("a") ?: continue
            val icon = loaded[i]
            for (j in 0 until aliases.length()) names.putIfAbsent(normalize(aliases.getString(j)), icon)
        }
        icons = loaded
        bySlug = loaded.associateBy { it.slug }
        byName = names
    }

    val size: Int get() = icons.size

    fun bySlug(slug: String): Icon? = bySlug[slug]

    /**
     * The icon for an account: its chosen [Account.icon] if set (`""` = deliberately none),
     * otherwise a match on the issuer, or failing that on the label's email domain.
     */
    fun forAccount(account: Account): Icon? = when (val chosen = account.icon) {
        null -> match(account.issuer) ?: account.label.substringAfter('@', "").takeIf { it.isNotEmpty() }?.let(::match)
        "" -> null
        else -> bySlug[chosen]
    }

    /** Best icon for an issuer such as "GitHub", "accounts.google.com" or "Epic Games, Inc.". */
    fun match(issuer: String): Icon? {
        val raw = issuer.trim().lowercase()
        if (raw.isEmpty()) return null
        for ((index, candidate) in candidates(raw).withIndex()) {
            val n = normalize(candidate)
            // One-letter brands ("X") only match the whole issuer, never a fragment of it.
            if (n.isEmpty() || (n.length < 2 && index > 0)) continue
            (RENAMED[n]?.let(bySlug::get) ?: byName[n])?.let { return it }
        }
        return null
    }

    /** For the icon picker: titles containing [query], best matches first. */
    fun search(query: String, limit: Int = 60): List<Icon> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return icons.asSequence()
            .map { it to normalize(it.title) }
            .filter { (icon, title) -> q in title || q in icon.slug }
            .sortedWith(compareBy({ (_, title) -> if (title == q) 0 else if (title.startsWith(q)) 1 else 2 }, { (_, title) -> title.length }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun candidates(raw: String): List<String> = buildList {
        add(raw)
        // "Epic Games, Inc." / "GitHub (work)" → "epic games" / "github"
        val cleaned = raw.substringBefore('(').substringBefore(',').replace(SUFFIXES, "").trim()
        add(cleaned)
        // Domains: "accounts.google.com" → "google"; "discord.com" → "discord"
        if ('.' in cleaned && ' ' !in cleaned) {
            val parts = cleaned.split('.').filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                add(parts[parts.size - 2])
                add(parts.dropLast(1).joinToString(""))
            }
        }
        // "Google Cloud" → "google"; but only after the whole name failed.
        cleaned.split(' ', '-', '_', ':').firstOrNull { it.length >= 2 }?.let(::add)
    }

    companion object {
        private val SUFFIXES = Regex("""\b(inc|llc|ltd|gmbh|corp|corporation|co)\.?$""")

        /** Brands whose current logo lives under another slug. */
        private val RENAMED = mapOf("twitter" to "x")

        fun normalize(s: String): String = s.lowercase().replace("&", "and").replace("+", "plus").filter { it.isLetterOrDigit() }
    }
}

/** Badge colors for accounts without a logo: the brand's color if known, otherwise a stable pick. */
object LetterBadge {
    /** Well-known brands that aren't in the icon set (their owners asked Simple Icons to drop them). */
    private val BRAND_COLORS = mapOf(
        "microsoft" to "0078D4", "outlook" to "0078D4", "xbox" to "107C10", "azure" to "0078D4",
        "linkedin" to "0A66C2", "amazon" to "FF9900", "aws" to "232F3E", "amazonwebservices" to "232F3E",
        "openai" to "10A37F", "chatgpt" to "10A37F", "adobe" to "FF0000", "slack" to "4A154B",
        "yahoo" to "6001D2", "heroku" to "430098", "nintendo" to "E60012", "minecraft" to "62B47A",
        "kraken" to "5741D9", "tuta" to "850122", "tutanota" to "850122", "fastmail" to "0067B9",
    )
    private val PALETTE = listOf("B5652B", "2B7BB5", "2E9E6B", "8E44AD", "C0392B", "D35400", "16A085", "7F8C8D", "2C3E50", "B03A75")

    fun letter(account: Account): String =
        (account.issuer.ifBlank { account.label }).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

    fun color(account: Account): String {
        val name = BrandIcons.normalize(account.issuer.ifBlank { account.label })
        BRAND_COLORS.entries.firstOrNull { (brand, _) -> name == brand || name.startsWith(brand) }?.let { return it.value }
        return PALETTE[Math.floorMod(name.hashCode(), PALETTE.size)]
    }
}
