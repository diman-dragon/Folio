package app.folio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TextSelectTest {
    /** Одна строка, символы шириной 0.01, i-й символ занимает [i*0.01, (i+1)*0.01]. */
    private fun line(s: String) = s.mapIndexed { i, c -> TChar(c.code, i * 0.01f, 0.10f, (i + 1) * 0.01f, 0.12f, 0, 0) }
    private fun xOf(i: Int) = i * 0.01f + 0.005f

    private fun pick(text: String, at: Int, mode: Int): String? =
        TextSelect.select(line(text), xOf(at), 0.11f, xOf(at), 0.11f, mode)?.text

    @Test fun tapSelectsWord() {
        assertEquals("world", pick("Hello world. This is a test!", 6, TextSelect.WORD))
    }

    @Test fun tapInFreeModeAlsoSelectsWord() {
        assertEquals("Hello", pick("Hello world.", 2, TextSelect.FREE))
    }

    @Test fun tapSelectsSentence() {
        assertEquals("This is a test!", pick("Hello world. This is a test! Done", 14, TextSelect.SENTENCE))
    }

    @Test fun abbreviationsDoNotEndSentence() {
        assertEquals("See e.g. the book.", pick("See e.g. the book. Next one.", 14, TextSelect.SENTENCE))
    }

    @Test fun dragInSentenceModeCoversBothSentences() {
        val t = "Hello world. This is a test! Done"
        val sel = TextSelect.select(line(t), xOf(6), 0.11f, xOf(14), 0.11f, TextSelect.SENTENCE)
        assertNotNull(sel)
        assertEquals("Hello world. This is a test!", sel!!.text)
    }

    @Test fun dragInWordModeSnapsToWholeWords() {
        val sel = TextSelect.select(line("alpha beta gamma"), xOf(2), 0.11f, xOf(8), 0.11f, TextSelect.WORD)
        assertEquals("alpha beta", sel!!.text)
    }

    @Test fun tapFarFromTextSelectsNothing() {
        assertNull(TextSelect.select(line("Hello"), 0.5f, 0.9f, 0.5f, 0.9f, TextSelect.WORD))
    }

    @Test fun multiLineSelectionGivesOneRectPerLine() {
        val a = "Hello world".mapIndexed { i, c -> TChar(c.code, i * 0.01f, 0.10f, (i + 1) * 0.01f, 0.12f, 0, 0) }
        val b = "again. Next".mapIndexed { i, c -> TChar(c.code, i * 0.01f, 0.13f, (i + 1) * 0.01f, 0.15f, 0, 1) }
        val sel = TextSelect.select(a + b, xOf(1), 0.11f, xOf(2), 0.14f, TextSelect.SENTENCE)
        assertEquals(2, sel!!.rects.size)
        assertEquals("Hello world again.", sel.text)
    }
}
