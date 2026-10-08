package app.folio.engine

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Символ текстового слоя: нормализованный прямоугольник страницы + номера блока и строки. */
class TChar(val cp: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val block: Int, val line: Int)

/** rects: x0,y0,x1,y1 по одной строке текста; text — выделенный текст. */
class TextSelection(val rects: List<FloatArray>, val text: String)

/** Выделение по символам / словам / предложениям. Чистая логика без зависимостей от MuPDF. */
object TextSelect {
    const val FREE = 0
    const val WORD = 1
    const val SENTENCE = 2

    private const val TAP_RADIUS = 0.01f      // сдвиг меньше — считаем тапом
    private const val MAX_TAP_DISTANCE = 0.04f // тап дальше от текста — ничего не выделяем

    private fun dist(c: TChar, x: Float, y: Float): Float {
        val dx = if (x < c.x0) c.x0 - x else if (x > c.x1) x - c.x1 else 0f
        val dy = if (y < c.y0) c.y0 - y else if (y > c.y1) y - c.y1 else 0f
        return hypot(dx, dy)
    }

    private fun nearest(chars: List<TChar>, x: Float, y: Float): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in chars.indices) {
            val d = dist(chars[i], x, y)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun isSpace(c: TChar) = c.cp <= 32 || Character.isWhitespace(c.cp)

    private fun sameLine(chars: List<TChar>, a: Int, b: Int) = chars[a].block == chars[b].block && chars[a].line == chars[b].line

    private fun isWordAt(chars: List<TChar>, i: Int): Boolean {
        val cp = chars[i].cp
        if (Character.isLetterOrDigit(cp) || cp == '_'.code) return true
        // апостроф и дефис внутри слова: don't, кто-то
        if (cp == '\''.code || cp == '\u2019'.code || cp == '-'.code) {
            return i > 0 && i < chars.size - 1 && sameLine(chars, i - 1, i) && sameLine(chars, i, i + 1) &&
                Character.isLetterOrDigit(chars[i - 1].cp) && Character.isLetterOrDigit(chars[i + 1].cp)
        }
        return false
    }

    private fun snapToWordChar(chars: List<TChar>, i: Int): Int {
        if (isWordAt(chars, i)) return i
        for (d in 1..3) {
            if (i - d >= 0 && isWordAt(chars, i - d)) return i - d
            if (i + d < chars.size && isWordAt(chars, i + d)) return i + d
        }
        return i
    }

    private fun wordStart(chars: List<TChar>, i: Int): Int {
        var j = i
        while (j > 0 && sameLine(chars, j - 1, j) && isWordAt(chars, j - 1)) j--
        return j
    }

    private fun wordEnd(chars: List<TChar>, i: Int): Int {
        var j = i
        while (j < chars.size - 1 && sameLine(chars, j, j + 1) && isWordAt(chars, j + 1)) j++
        return j
    }

    private fun isTerminator(cp: Int) = cp == '.'.code || cp == '!'.code || cp == '?'.code || cp == 0x2026 ||
        cp == 0x3002 || cp == 0xFF01 || cp == 0xFF1F

    private fun isCloser(cp: Int) = cp == '"'.code || cp == '\''.code || cp == ')'.code || cp == ']'.code ||
        cp == 0xBB || cp == 0x201D || cp == 0x2019

    /** ends[i] = true, если на символе i заканчивается предложение. */
    private fun sentenceEnds(chars: List<TChar>): BooleanArray {
        val n = chars.size
        val ends = BooleanArray(n)
        for (i in 0 until n) {
            if (!isTerminator(chars[i].cp)) continue
            var k = i + 1
            while (k < n && isCloser(chars[k].cp) && chars[k].block == chars[i].block) k++
            val last = k - 1                              // последний символ предложения (с закрывающими кавычками)
            if (k >= n || chars[k].block != chars[i].block) { ends[last] = true; continue }
            val gap = isSpace(chars[k]) || chars[k].line != chars[last].line
            if (!gap) continue                             // «3.14», «e.g.» — не конец
            var m = k
            while (m < n && chars[m].block == chars[i].block && isSpace(chars[m])) m++
            if (m >= n || chars[m].block != chars[i].block) { ends[last] = true; continue }
            if (Character.isLowerCase(chars[m].cp)) continue   // «т.е. что», «e.g. the» — сокращение
            ends[last] = true
        }
        return ends
    }

    private fun sentenceStart(chars: List<TChar>, ends: BooleanArray, i: Int): Int {
        var j = i
        while (j > 0 && chars[j - 1].block == chars[j].block && !ends[j - 1]) j--
        while (j < i && isSpace(chars[j])) j++
        return j
    }

    private fun sentenceEnd(chars: List<TChar>, ends: BooleanArray, i: Int): Int {
        var k = i
        while (k < chars.size - 1 && chars[k + 1].block == chars[k].block && !ends[k]) k++
        while (k > i && isSpace(chars[k])) k--
        return k
    }

    /** Возвращает выделение или null, если текста под точками нет. */
    fun select(chars: List<TChar>, ax: Float, ay: Float, bx: Float, by: Float, mode: Int): TextSelection? {
        if (chars.isEmpty()) return null
        val ia = nearest(chars, ax, ay)
        val ib = nearest(chars, bx, by)
        if (ia < 0 || ib < 0) return null
        val tap = hypot(bx - ax, by - ay) < TAP_RADIUS
        if (tap && dist(chars[ia], ax, ay) > MAX_TAP_DISTANCE) return null

        var s = min(ia, ib)
        var e = max(ia, ib)
        val effective = if (tap && mode == FREE) WORD else mode
        when (effective) {
            WORD -> {
                s = wordStart(chars, snapToWordChar(chars, s))
                e = wordEnd(chars, snapToWordChar(chars, e))
            }
            SENTENCE -> {
                val ends = sentenceEnds(chars)
                s = sentenceStart(chars, ends, s)
                e = sentenceEnd(chars, ends, e)
            }
        }
        if (e < s) return null
        return build(chars, s, e)
    }

    private fun build(chars: List<TChar>, s: Int, e: Int): TextSelection? {
        val rects = ArrayList<FloatArray>()
        val text = StringBuilder()
        var i = s
        while (i <= e) {
            val blk = chars[i].block
            val ln = chars[i].line
            var j = i
            while (j + 1 <= e && chars[j + 1].block == blk && chars[j + 1].line == ln) j++
            var a = i
            var b = j
            while (a <= b && isSpace(chars[a])) a++
            while (b >= a && isSpace(chars[b])) b--
            if (a <= b) {
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                for (k in a..b) {
                    val c = chars[k]
                    x0 = min(x0, c.x0); y0 = min(y0, c.y0); x1 = max(x1, c.x1); y1 = max(y1, c.y1)
                    text.appendCodePoint(c.cp)
                }
                rects.add(floatArrayOf(x0, y0, x1, y1))
                if (j < e) text.append(if (chars[j + 1].block != blk) '\n' else ' ')
            }
            i = j + 1
        }
        if (rects.isEmpty()) return null
        return TextSelection(rects, text.toString().trim())
    }
}
