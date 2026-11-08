package de.chromefiles.app

import de.chromefiles.app.data.media.HighlightSpan
import de.chromefiles.app.data.media.SyntaxHighlighter
import de.chromefiles.app.data.media.SyntaxHighlighter.Language
import de.chromefiles.app.data.media.TokenType
import de.chromefiles.app.data.remote.RemotePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {

    private fun List<HighlightSpan>.textsOf(source: String, type: TokenType): List<String> =
        filter { it.type == type }.map { source.substring(it.start, it.end) }

    @Test
    fun kotlinKeywordsStringsCommentsAndTypes() {
        val code = """
            // Kommentar mit "String" darin
            fun main(args: Array<String>) {
                val name = "Welt"
                println("Hallo ${'$'}name") /* mehrzeiliger
                Kommentar */
            }
        """.trimIndent()
        val spans = SyntaxHighlighter.highlight(code, Language.KOTLIN)

        assertTrue(spans.textsOf(code, TokenType.KEYWORD).containsAll(listOf("fun", "val")))
        assertEquals(listOf("\"Welt\"", "\"Hallo \$name\""), spans.textsOf(code, TokenType.STRING))
        val comments = spans.textsOf(code, TokenType.COMMENT)
        assertEquals(2, comments.size)
        assertTrue(comments[0].startsWith("// Kommentar"))
        assertTrue(comments[1].startsWith("/* mehrzeiliger") && comments[1].endsWith("Kommentar */"))
        assertTrue(spans.textsOf(code, TokenType.TYPE).containsAll(listOf("Array", "String")))
    }

    @Test
    fun spansAreSortedAndNonOverlapping() {
        val code = "val x = 0x1F + 2.5e3 // ende\nclass Foo"
        val spans = SyntaxHighlighter.highlight(code, Language.KOTLIN)
        spans.zipWithNext().forEach { (a, b) -> assertTrue("Überlappung: $a / $b", a.end <= b.start) }
        assertEquals(listOf("0x1F", "2.5e3"), spans.textsOf(code, TokenType.NUMBER))
    }

    @Test
    fun jsonDistinguishesPropertiesFromValues() {
        val json = """{"name": "ChromeFiles", "aktiv": true, "version": 4}"""
        val spans = SyntaxHighlighter.highlight(json, Language.JSON)
        assertEquals(listOf("\"name\"", "\"aktiv\"", "\"version\""), spans.textsOf(json, TokenType.PROPERTY))
        assertEquals(listOf("\"ChromeFiles\""), spans.textsOf(json, TokenType.STRING))
        assertEquals(listOf("true"), spans.textsOf(json, TokenType.KEYWORD))
        assertEquals(listOf("4"), spans.textsOf(json, TokenType.NUMBER))
    }

    @Test
    fun markdownHeadingsCodeAndLinks() {
        val md = "# Titel\nText mit `code` und [Link](https://example.org)\n## Unterpunkt"
        val spans = SyntaxHighlighter.highlight(md, Language.MARKDOWN)
        assertEquals(listOf("# Titel", "## Unterpunkt"), spans.textsOf(md, TokenType.HEADING))
        assertEquals(listOf("`code`"), spans.textsOf(md, TokenType.CODE))
        assertEquals(listOf("[Link](https://example.org)"), spans.textsOf(md, TokenType.LINK))
    }

    @Test
    fun languageDetectionByExtension() {
        assertEquals(Language.KOTLIN, SyntaxHighlighter.languageFor("KT"))
        assertEquals(Language.MARKDOWN, SyntaxHighlighter.languageFor("md"))
        assertEquals(Language.PLAIN, SyntaxHighlighter.languageFor("bin"))
        assertTrue(SyntaxHighlighter.highlight("irgendwas", Language.PLAIN).isEmpty())
    }

    @Test
    fun remotePathsNormalizeAndJoin() {
        assertEquals("a/b/c", RemotePaths.normalize("/a//b\\c/./"))
        assertEquals("", RemotePaths.normalize("/"))
        assertEquals("ordner/datei.txt", RemotePaths.child("ordner", "datei.txt"))
        assertEquals("datei.txt", RemotePaths.child("", "datei.txt"))
        assertEquals("c", RemotePaths.name("a/b/c"))
    }
}
