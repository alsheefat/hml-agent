package com.hmlai.agent

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Tiny dependency-free Markdown -> Spannable renderer for agent replies.
 * Supports: **bold**, *italic* / _italic_, ***both***, ~~strike~~, `inline code`,
 * ``` code blocks ```, # headings, - / * / + bullets, > quotes, and escaped characters.
 * Anything it doesn't recognise is left as plain text, so it can never lose content.
 */
object MarkdownRenderer {

    private val CODE_BG = Color.parseColor("#1F2A38")
    private val CODE_FG = Color.parseColor("#BFE0FF")
    private val QUOTE_BAR = Color.parseColor("#3A6EA5")

    private val headingRe = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$")
    private val bulletRe = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val quoteRe = Regex("^\\s{0,3}>\\s?(.*)$")
    private val ruleRe = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")

    fun render(src: String): CharSequence {
        if (src.none { it == '*' || it == '_' || it == '`' || it == '#' || it == '~' || it == '>' || it == '-' || it == '+' || it == '\\' }) {
            return src
        }
        val out = SpannableStringBuilder()
        val lines = src.replace("\r\n", "\n").split("\n")
        var inCode = false
        var codeStart = 0

        for ((index, line) in lines.withIndex()) {
            val isLast = index == lines.lastIndex

            if (line.trimStart().startsWith("```")) {
                if (!inCode) {
                    inCode = true
                    codeStart = out.length
                } else {
                    inCode = false
                    styleCode(out, codeStart, out.length)
                }
                continue
            }
            if (inCode) {
                out.append(line)
                if (!isLast) out.append('\n')
                continue
            }

            val start = out.length
            val heading = headingRe.matchEntire(line)
            val bullet = bulletRe.matchEntire(line)
            val quote = quoteRe.matchEntire(line)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    appendInline(out, heading.groupValues[2])
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val size = when (level) { 1 -> 1.3f; 2 -> 1.18f; 3 -> 1.08f; else -> 1.0f }
                    if (size != 1.0f) out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ruleRe.matches(line) -> out.append("────────")
                bullet != null -> {
                    val indent = (bullet.groupValues[1].length / 2).coerceAtMost(4)
                    out.append("    ".repeat(indent)).append("•  ")
                    appendInline(out, bullet.groupValues[2])
                }
                quote != null -> {
                    appendInline(out, quote.groupValues[1])
                    out.setSpan(QuoteSpan(QUOTE_BAR), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> appendInline(out, line)
            }
            if (!isLast) out.append('\n')
        }
        // Unterminated code fence (still streaming / model forgot to close it): style what we have.
        if (inCode) styleCode(out, codeStart, out.length)
        return out
    }

    private fun styleCode(sb: SpannableStringBuilder, start: Int, end: Int) {
        if (end <= start) return
        sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(BackgroundColorSpan(CODE_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(CODE_FG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendInline(sb: SpannableStringBuilder, s: String) {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]

            // Backslash escapes: \* \_ \` etc.
            if (c == '\\' && i + 1 < n && s[i + 1] in "\\`*_{}[]()#+-.!~>|") {
                sb.append(s[i + 1]); i += 2; continue
            }

            // `inline code`
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    val st = sb.length
                    sb.append(s, i + 1, end)
                    styleCode(sb, st, sb.length)
                    i = end + 1
                    continue
                }
            }

            // **bold** / __bold__ / ~~strike~~
            if (i + 1 < n && (s.startsWith("**", i) || s.startsWith("__", i) || s.startsWith("~~", i))) {
                val marker = s.substring(i, i + 2)
                val end = findClose(s, marker, i + 2)
                if (end > 0) {
                    val st = sb.length
                    appendInline(sb, s.substring(i + 2, end))
                    val span: Any = if (marker == "~~") StrikethroughSpan() else StyleSpan(Typeface.BOLD)
                    sb.setSpan(span, st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }

            // *italic* / _italic_
            if ((c == '*' || c == '_') && i + 1 < n && !s[i + 1].isWhitespace() && s[i + 1] != c) {
                val boundaryOk = c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()
                if (boundaryOk) {
                    val end = findItalicClose(s, c, i + 1)
                    if (end > 0) {
                        val st = sb.length
                        appendInline(sb, s.substring(i + 1, end))
                        sb.setSpan(StyleSpan(Typeface.ITALIC), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                        continue
                    }
                }
            }

            sb.append(c)
            i++
        }
    }

    /** Index of the closing 2-char [marker], or -1. Content must be non-empty and not end in space. */
    private fun findClose(s: String, marker: String, from: Int): Int {
        var idx = s.indexOf(marker, from)
        while (idx != -1) {
            if (idx > from && !s[idx - 1].isWhitespace()) {
                // "***x***": close on the LAST two chars of the run so the inner *x* still parses.
                var close = idx
                while (close + 2 < s.length && s[close + 2] == marker[0]) close++
                return close
            }
            idx = s.indexOf(marker, idx + 1)
        }
        return -1
    }

    /** Index of the closing single [ch], or -1. */
    private fun findItalicClose(s: String, ch: Char, from: Int): Int {
        var idx = s.indexOf(ch, from)
        while (idx != -1) {
            val prevOk = idx > from - 1 && !s[idx - 1].isWhitespace() && s[idx - 1] != ch
            val nextCh = if (idx + 1 < s.length) s[idx + 1] else ' '
            val nextOk = nextCh != ch && (ch == '*' || !nextCh.isLetterOrDigit())
            if (idx > from && prevOk && nextOk) return idx
            idx = s.indexOf(ch, idx + 1)
        }
        return -1
    }
}
