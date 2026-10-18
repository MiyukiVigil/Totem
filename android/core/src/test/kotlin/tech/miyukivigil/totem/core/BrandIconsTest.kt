package tech.miyukivigil.totem.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrandIconsTest {
    private val icons = BrandIcons(File(System.getProperty("totem.brandIcons")).readText())

    private fun slug(issuer: String) = icons.match(issuer)?.slug

    @Test
    fun bundleIsComplete() {
        assertTrue(icons.size > 3000, "${icons.size} icons")
    }

    @Test
    fun matchesCommonIssuers() {
        val expected = mapOf(
            "GitHub" to "github",
            "github.com" to "github",
            "Google" to "google",
            "accounts.google.com" to "google",
            "Discord" to "discord",
            "Epic Games, Inc." to "epicgames",
            "Steam" to "steam",
            "PayPal" to "paypal",
            "Cloudflare" to "cloudflare",
            "X" to "x",
            "Twitter" to "x",
            "Proton" to "proton",
            "Bitwarden" to "bitwarden",
            "Reddit" to "reddit",
            "Twitch" to "twitch",
            "Coinbase" to "coinbase",
            "Binance" to "binance",
            "GitLab" to "gitlab",
            "npm" to "npm",
            "Dropbox" to "dropbox",
            "Facebook" to "facebook",
            "Instagram" to "instagram",
            "Apple" to "apple",
            "Docker Hub" to "docker",
            "DigitalOcean" to "digitalocean",
            "Riot Games" to "riotgames",
        )
        val wrong = expected.filter { (issuer, s) -> slug(issuer) != s }.map { (issuer, s) -> "$issuer → ${slug(issuer)} (wanted $s)" }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun unknownIssuersGetNothing() {
        assertNull(slug("Miyuki's Homelab"))
        assertNull(slug(""))
        assertNull(slug("a"))
    }

    @Test
    fun chosenIconWinsAndEmptyMeansNone() {
        fun acc(issuer: String, label: String = "", icon: String? = null) =
            Account(issuer = issuer, label = label, secret = ByteArray(10), icon = icon)
        assertEquals("github", icons.forAccount(acc("GitHub"))?.slug)
        assertEquals("gitlab", icons.forAccount(acc("GitHub", icon = "gitlab"))?.slug)
        assertNull(icons.forAccount(acc("GitHub", icon = "")))
        assertEquals("gmail", icons.forAccount(acc("", label = "me@gmail.com"))?.slug)
    }

    @Test
    fun searchPutsExactAndPrefixFirst() {
        val results = icons.search("steam").map { it.slug }
        assertEquals("steam", results.first())
        assertTrue(icons.search("git").take(6).any { it.slug == "github" })
        assertEquals("github", icons.search("github").first().slug)
    }

    @Test
    fun letterBadges() {
        val ms = Account(issuer = "Microsoft", label = "me", secret = ByteArray(10))
        assertEquals("M", LetterBadge.letter(ms))
        assertEquals("0078D4", LetterBadge.color(ms))
        val other = Account(issuer = "Hytale", label = "", secret = ByteArray(10))
        assertEquals(LetterBadge.color(other), LetterBadge.color(other.copy(label = "x")))
    }

    @Test
    fun iconSurvivesTheVaultFormat() {
        val list = listOf(
            Account(issuer = "A", label = "", secret = ByteArray(10), icon = "github"),
            Account(issuer = "B", label = "", secret = ByteArray(10), icon = ""),
            Account(issuer = "C", label = "", secret = ByteArray(10)),
        )
        assertEquals(list, AccountListCodec.decode(AccountListCodec.encode(list)))
    }
}
