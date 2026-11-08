package ch.threema.app.linkpreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * F1Whisper: the link-preview HTML parser. Each case is a real-world shape that the old single-regex parser got
 * wrong or ignored.
 */
class LinkPreviewHtmlParserTest {

    /** A regression to backtracking regexes takes minutes on the hostile inputs below: fail instead of hanging. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private fun assertFast(html: String) {
        val took = measureTime { LinkPreviewHtmlParser.parse(html) }
        assertTrue(took.inWholeMilliseconds < 3_000, "parsing ${html.length} chars took $took")
    }

    @Test
    fun `hostile pages near the 2 MB cap parse in linear time`() {
        val n = 2 * 1024 * 1024 - 16
        // A huge attribute name without '=' (quadratic for an unanchored attribute regex).
        assertFast("<meta \"" + "a".repeat(n) + "\">")
        assertFast("<meta " + "a".repeat(n) + ">")
        // Many unterminated tags and an unclosed quote (rescanning per tag would be quadratic).
        assertFast("<meta a=\"".repeat(n / 9))
        assertFast("<meta ".repeat(n / 6))
        assertFast("<meta content=x ".repeat(n / 16))
        // Many title openings without a close.
        assertFast("<title>".repeat(n / 7))
    }

    @Test
    fun `a broken tag does not hide the tags after it`() {
        val html = """<meta property="og:title" content="unterminated <meta property="og:title" content="Second">"""

        assertEquals("Second", LinkPreviewHtmlParser.parse("""<meta junk <meta property="og:title" content="Second">""").title)
        // An unclosed quote swallows what follows up to the next matching quote; it must still terminate.
        LinkPreviewHtmlParser.parse(html)
    }

    @Test
    fun `a longer tag name is not mistaken for meta or title`() {
        val html = """<metadata property="og:title" content="no"><titlex>no</titlex><title>yes</title>"""

        assertEquals("yes", LinkPreviewHtmlParser.parse(html).title)
    }

    @Test
    fun `an apostrophe inside a double-quoted value is kept whole`() {
        val html = """<meta property="og:title" content="Don't panic: it's fine"><meta property="og:description" content='Say "hi"'>"""

        val metadata = LinkPreviewHtmlParser.parse(html)

        assertEquals("Don't panic: it's fine", metadata.title)
        assertEquals("Say \"hi\"", metadata.description)
    }

    @Test
    fun `og keys in name= and content before the key are read`() {
        val html = """<meta content="Named title" name="og:title"><meta content="https://cdn.example/a.jpg" property="og:image">"""

        val metadata = LinkPreviewHtmlParser.parse(html)

        assertEquals("Named title", metadata.title)
        assertEquals("https://cdn.example/a.jpg", metadata.imageUrl)
    }

    @Test
    fun `twitter tags fill in when Open Graph is missing`() {
        val html = """
            <meta name="twitter:title" content="Tweet title">
            <meta name="twitter:description" content="Tweet description">
            <meta name="twitter:image:src" content="https://cdn.example/t.jpg">
        """.trimIndent()

        val metadata = LinkPreviewHtmlParser.parse(html)

        assertEquals("Tweet title", metadata.title)
        assertEquals("Tweet description", metadata.description)
        assertEquals("https://cdn.example/t.jpg", metadata.imageUrl)
    }

    @Test
    fun `Open Graph wins over twitter, and the first occurrence wins`() {
        val html = """
            <meta name="twitter:title" content="Tweet title">
            <meta property="og:title" content="First">
            <meta property="og:title" content="Second">
        """.trimIndent()

        assertEquals("First", LinkPreviewHtmlParser.parse(html).title)
    }

    @Test
    fun `plain meta description and the document title are the last fallbacks`() {
        val html = """<html><head><title>  Page title </title><meta name="description" content="Plain description"></head>"""

        val metadata = LinkPreviewHtmlParser.parse(html)

        assertEquals("  Page title ", metadata.title, "raw value; the fetcher cleans it")
        assertEquals("Plain description", metadata.description)
        assertNull(metadata.imageUrl)
        assertTrue(metadata.isUsable)
    }

    @Test
    fun `a blank og title falls through to the next source`() {
        val html = """<meta property="og:title" content="   "><title>Real title</title>"""

        assertEquals("Real title", LinkPreviewHtmlParser.parse(html).title)
    }

    @Test
    fun `a closing bracket inside a quoted value does not end the tag`() {
        val html = """<meta property="og:description" content="a > b" data-x="1"><meta property="og:title" content="T">"""

        val metadata = LinkPreviewHtmlParser.parse(html)

        assertEquals("a > b", metadata.description)
        assertEquals("T", metadata.title)
    }

    @Test
    fun `a relative image is returned as written, for the fetcher to resolve against the final url`() {
        val html = """<meta property="og:image" content="/img/cover.png">"""

        assertEquals("/img/cover.png", LinkPreviewHtmlParser.parse(html).imageUrl)
    }

    @Test
    fun `a page with nothing to show is not usable`() {
        val metadata = LinkPreviewHtmlParser.parse("<html><body>hello</body></html>")

        assertNull(metadata.title)
        assertFalse(metadata.isUsable)
    }

    @Test
    fun `a size-capped prefix drops the tag that the cap split`() {
        val prefix = """<meta property="og:title" content="Kept"><meta property="og:description" content="cut off he"""

        val trimmed = LinkPreviewHtmlParser.dropUnfinishedTag(prefix)

        assertEquals("""<meta property="og:title" content="Kept">""", trimmed)
        val metadata = LinkPreviewHtmlParser.parse(trimmed)
        assertEquals("Kept", metadata.title)
        assertNull(metadata.description)
    }

    @Test
    fun `a complete document is not trimmed`() {
        val html = "<p>done</p>"

        assertEquals(html, LinkPreviewHtmlParser.dropUnfinishedTag(html))
    }

    @Test
    fun `bidi and zero-width marks around a title are stripped`() {
        assertEquals("Gemini", LinkPreviewHtmlParser.cleanText("\u200E Gemini\u200F"))
        assertEquals("a b", LinkPreviewHtmlParser.cleanText("\uFEFF\u00A0a b\u200B"))
    }

    @Test
    fun `bot challenge pages are recognised`() {
        assertTrue(LinkPreviewHtmlParser.isChallengePage("<html></html>", "Just a moment..."))
        assertTrue(LinkPreviewHtmlParser.isChallengePage("<html></html>", "Attention Required! | Cloudflare"))
        assertTrue(
            LinkPreviewHtmlParser.isChallengePage("""<script src="/cdn-cgi/challenge-platform/h/b/orchestrate"></script>""", null),
        )
        assertFalse(LinkPreviewHtmlParser.isChallengePage("<html></html>", "Just a normal article"))
    }
}
