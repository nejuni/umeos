package com.nejuni.umeos

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

sealed interface Block {
    data class Heading(val level: Int, val text: String, val line: Int) : Block
    data class Paragraph(val text: String) : Block
    data class Code(val lang: String, val code: String) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class Items(val items: List<Item>) : Block
    data object Rule : Block
    data class Table(val header: List<String>, val aligns: List<Int>, val rows: List<List<String>>) : Block
}

data class Item(val level: Int, val marker: String, val checked: Boolean?, val text: String)

object Markdown {
    private val headingRe = Regex("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*\$")
    private val ruleRe = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*\$")
    private val listRe = Regex("^([ \\t]*)([-*+]|\\d{1,9}[.)])[ \\t]+(.*)\$")
    private val taskRe = Regex("^\\[([ xX])\\](?:[ \\t]+(.*))?\$")
    private val sepRe = Regex("^[ \\t]*\\|?[ \\t]*:?-+:?[ \\t]*(\\|[ \\t]*:?-+:?[ \\t]*)*\\|?[ \\t]*\$")
    private val fenceRe = Regex("^ {0,3}(`{3,}|~{3,})(.*)\$")
    private val cellSplit = Regex("(?<!\\\\)\\|")

    private val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = G.bg1,
        color = G.orange,
        fontSize = 0.9.em
    )
    private val linkStyle = SpanStyle(color = G.blue, textDecoration = TextDecoration.Underline)

    // ---------------------------------------------------------------- blocks

    fun parse(src: String): List<Block> {
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return parseLines(lines, 0)
    }

    private fun isFence(l: String): Boolean {
        val m = fenceRe.find(l) ?: return false
        return !(m.groupValues[1][0] == '`' && m.groupValues[2].contains('`'))
    }

    private fun isClosing(l: String, marker: String): Boolean {
        val t = l.trim()
        return t.length >= marker.length && t.all { it == marker[0] }
    }

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        return t.split(cellSplit).map { it.trim().replace("\\|", "|") }
    }

    private fun isTableStart(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        val l = lines[i]
        val s = lines[i + 1]
        if (!l.contains('|') || !s.contains('-') || !sepRe.matches(s)) return false
        return cells(l).size == cells(s).size
    }

    private fun startsBlock(lines: List<String>, i: Int): Boolean {
        val l = lines[i]
        if (isFence(l) || headingRe.matches(l) || ruleRe.matches(l) ||
            l.trimStart().startsWith(">") || listRe.matches(l)
        ) return true
        return isTableStart(lines, i)
    }

    private fun indentWidth(ws: String): Int {
        var n = 0
        for (c in ws) n += if (c == '\t') 4 else 1
        return n
    }

    private fun parseLines(lines: List<String>, base: Int): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            // fenced code
            if (isFence(line)) {
                val m = fenceRe.find(line)!!
                val marker = m.groupValues[1]
                val lang = m.groupValues[2].trim().takeWhile { !it.isWhitespace() }
                val buf = ArrayList<String>()
                i++
                while (i < lines.size && !isClosing(lines[i], marker)) {
                    buf.add(lines[i])
                    i++
                }
                i++
                out.add(Block.Code(lang, buf.joinToString("\n")))
                continue
            }

            // heading
            val hm = headingRe.find(line)
            if (hm != null) {
                out.add(Block.Heading(hm.groupValues[1].length, hm.groupValues[2].trim(), base + i))
                i++
                continue
            }

            // horizontal rule
            if (ruleRe.matches(line)) {
                out.add(Block.Rule)
                i++
                continue
            }

            // table
            if (isTableStart(lines, i)) {
                val header = cells(line)
                val aligns = cells(lines[i + 1]).map { c ->
                    val l = c.startsWith(":")
                    val r = c.endsWith(":")
                    if (l && r) 1 else if (r) 2 else 0
                }
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    val r = cells(lines[i]).toMutableList()
                    while (r.size < header.size) r.add("")
                    rows.add(r.take(header.size))
                    i++
                }
                out.add(Block.Table(header, aligns, rows))
                continue
            }

            // quote
            if (line.trimStart().startsWith(">")) {
                val inner = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].trimStart().startsWith(">")) {
                    inner.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                out.add(Block.Quote(parseLines(inner, 0)))
                continue
            }

            // list
            if (listRe.matches(line)) {
                val items = ArrayList<Item>()
                val stack = ArrayList<Int>()
                while (i < lines.size) {
                    val l = lines[i]
                    val m = listRe.find(l)
                    if (m != null && !ruleRe.matches(l)) {
                        val indent = indentWidth(m.groupValues[1])
                        while (stack.isNotEmpty() && indent < stack.last()) stack.removeAt(stack.lastIndex)
                        if (stack.isEmpty() || indent > stack.last()) stack.add(indent)
                        var text = m.groupValues[3]
                        var checked: Boolean? = null
                        val tm = taskRe.find(text)
                        if (tm != null) {
                            checked = tm.groupValues[1] != " "
                            text = tm.groupValues[2]
                        }
                        items.add(Item(stack.size - 1, m.groupValues[2], checked, text))
                        i++
                    } else if (l.isBlank()) {
                        if (i + 1 < lines.size && listRe.matches(lines[i + 1])) i++ else break
                    } else if ((l.startsWith(" ") || l.startsWith("\t")) && items.isNotEmpty()) {
                        val last = items.removeAt(items.lastIndex)
                        items.add(last.copy(text = last.text + " " + l.trim()))
                        i++
                    } else {
                        break
                    }
                }
                out.add(Block.Items(items))
                continue
            }

            // paragraph
            val sb = StringBuilder()
            var first = true
            while (i < lines.size && lines[i].isNotBlank() && (first || !startsBlock(lines, i))) {
                val l = lines[i]
                val hard = l.endsWith("  ") || l.endsWith("\\")
                sb.append(l.trim().removeSuffix("\\").trimEnd())
                sb.append(if (hard) "\n" else " ")
                first = false
                i++
            }
            out.add(Block.Paragraph(sb.toString().trim()))
        }
        return out
    }

    // ---------------------------------------------------------------- inline

    /** GitHub-style heading slug, e.g. "Usage and Options" -> "usage-and-options". */
    fun slug(heading: String): String {
        val t = heading
            .replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "\$1")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("[*`~]"), "")
            .trim()
            .lowercase()
        val sb = StringBuilder()
        for (ch in t) {
            when {
                ch.isLetterOrDigit() || ch == '-' || ch == '_' -> sb.append(ch)
                ch == ' ' -> sb.append('-')
            }
        }
        return sb.toString()
    }

    /** anchor id -> (block index, heading). Duplicates get -1, -2 ... like GitHub. */
    fun anchors(blocks: List<Block>): Map<String, Pair<Int, Block.Heading>> {
        val map = LinkedHashMap<String, Pair<Int, Block.Heading>>()
        val seen = HashMap<String, Int>()
        blocks.forEachIndexed { idx, b ->
            if (b is Block.Heading) {
                val base = slug(b.text)
                val n = seen.getOrDefault(base, 0)
                seen[base] = n + 1
                val key = if (n == 0) base else "$base-$n"
                map.putIfAbsent(key, idx to b)
            }
        }
        // loose fallback: collapse repeated hyphens
        for ((k, v) in map.entries.toList()) {
            map.putIfAbsent(k.replace(Regex("-+"), "-").trim('-'), v)
        }
        return map
    }

    fun plain(s: String): String =
        s.replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "\$1").replace(Regex("[*_`~]"), "")

    fun inline(s: String): AnnotatedString = buildAnnotatedString { appendInline(this, s, 0) }

    private class Lnk(val label: String, val url: String, val end: Int)

    private fun parseLink(s: String, open: Int): Lnk? {
        var depth = 0
        var j = open
        while (j < s.length) {
            val ch = s[j]
            if (ch == '\\') {
                j += 2
                continue
            }
            if (ch == '[') depth++
            if (ch == ']') {
                depth--
                if (depth == 0) break
            }
            j++
        }
        if (j >= s.length || s[j] != ']') return null
        if (j + 1 >= s.length || s[j + 1] != '(') return null
        var k = j + 2
        var pd = 1
        while (k < s.length) {
            val ch = s[k]
            if (ch == '(') {
                pd++
            } else if (ch == ')') {
                pd--
                if (pd == 0) break
            }
            k++
        }
        if (k >= s.length) return null
        val url = s.substring(j + 2, k).trim().substringBefore(' ').trim('<', '>')
        return Lnk(s.substring(open + 1, j), url, k + 1)
    }

    private fun findClose(s: String, from: Int, c: Char): Int {
        var j = from + 1
        while (j < s.length) {
            if (s[j] == c && !s[j - 1].isWhitespace() &&
                (j + 1 >= s.length || s[j + 1] != c) &&
                (c == '*' || j + 1 >= s.length || !s[j + 1].isLetterOrDigit())
            ) return j
            j++
        }
        return -1
    }

    private fun appendInline(b: AnnotatedString.Builder, s: String, depth: Int) {
        val n = s.length
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                b.append(plain.toString())
                plain.clear()
            }
        }
        fun link(url: String, label: String) {
            flush()
            b.pushStringAnnotation("URL", url)
            b.pushStyle(linkStyle)
            b.append(label)
            b.pop()
            b.pop()
        }

        var i = 0
        while (i < n) {
            val c = s[i]

            // backslash escape
            if (c == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!|~<>".indexOf(s[i + 1]) >= 0) {
                plain.append(s[i + 1])
                i += 2
                continue
            }

            // inline code
            if (c == '`') {
                var run = 1
                while (i + run < n && s[i + run] == '`') run++
                val ticks = "`".repeat(run)
                val end = s.indexOf(ticks, i + run)
                if (end > 0) {
                    flush()
                    b.pushStyle(codeStyle)
                    b.append(s.substring(i + run, end).trim())
                    b.pop()
                    i = end + run
                } else {
                    plain.append(ticks)
                    i += run
                }
                continue
            }

            if (depth < 6) {
                // bold / strike
                if ((c == '*' || c == '_' || c == '~') && i + 1 < n && s[i + 1] == c) {
                    val d = "$c$c"
                    val end = s.indexOf(d, i + 2)
                    if (end > i + 2) {
                        flush()
                        val st = if (c == '~') SpanStyle(textDecoration = TextDecoration.LineThrough, color = G.gray)
                        else SpanStyle(fontWeight = FontWeight.Bold)
                        b.pushStyle(st)
                        appendInline(b, s.substring(i + 2, end), depth + 1)
                        b.pop()
                        i = end + 2
                        continue
                    }
                }
                // italic
                if ((c == '*' || (c == '_' && (i == 0 || !s[i - 1].isLetterOrDigit()))) &&
                    i + 1 < n && s[i + 1] != c && !s[i + 1].isWhitespace()
                ) {
                    val end = findClose(s, i + 1, c)
                    if (end != -1) {
                        flush()
                        b.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        appendInline(b, s.substring(i + 1, end), depth + 1)
                        b.pop()
                        i = end + 1
                        continue
                    }
                }
            }

            // image -> shown as a tappable [image: alt] label
            if (c == '!' && i + 1 < n && s[i + 1] == '[') {
                val l = parseLink(s, i + 1)
                if (l != null) {
                    link(l.url, "[image: " + l.label.ifBlank { "image" } + "]")
                    i = l.end
                    continue
                }
            }

            // link
            if (c == '[') {
                val l = parseLink(s, i)
                if (l != null) {
                    flush()
                    b.pushStringAnnotation("URL", l.url)
                    b.pushStyle(linkStyle)
                    appendInline(b, l.label, depth + 1)
                    b.pop()
                    b.pop()
                    i = l.end
                    continue
                }
            }

            // <https://...>
            if (c == '<' && (s.startsWith("<http://", i) || s.startsWith("<https://", i))) {
                val end = s.indexOf('>', i)
                if (end > 0) {
                    val url = s.substring(i + 1, end)
                    link(url, url)
                    i = end + 1
                    continue
                }
            }

            // bare url
            if (c == 'h' && (s.startsWith("http://", i) || s.startsWith("https://", i)) &&
                (i == 0 || !s[i - 1].isLetterOrDigit())
            ) {
                var j = i
                while (j < n && !s[j].isWhitespace() && s[j] != '<' && s[j] != '>') j++
                val url = s.substring(i, j).trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
                link(url, url)
                i += url.length
                continue
            }

            plain.append(c)
            i++
        }
        flush()
    }
}
