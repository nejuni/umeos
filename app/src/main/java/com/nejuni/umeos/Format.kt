package com.nejuni.umeos

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Text transformations used by the toolbar above the keyboard. */
object Fmt {
    private val numRe = Regex("^\\d+[.)] ")
    private val contRe = Regex("^([ \\t]*)(- \\[[ xX]\\] |[-*+] |\\d+\\. |> )(.*)$")

    private fun lineStart(t: String, pos: Int): Int =
        if (pos <= 0) 0 else t.lastIndexOf('\n', pos - 1) + 1

    private fun lineEnd(t: String, pos: Int): Int {
        val e = t.indexOf('\n', pos)
        return if (e < 0) t.length else e
    }

    private fun mapLines(v: TextFieldValue, f: (String) -> String): TextFieldValue {
        val t = v.text
        val s = v.selection.min.coerceIn(0, t.length)
        val e = v.selection.max.coerceIn(0, t.length)
        val ls = lineStart(t, s)
        val le = lineEnd(t, e)
        val old = t.substring(ls, le)
        val new = old.split("\n").joinToString("\n") { f(it) }
        val text = t.substring(0, ls) + new + t.substring(le)
        val sel = if (v.selection.collapsed) {
            TextRange((s + new.length - old.length).coerceIn(ls, ls + new.length))
        } else {
            TextRange(ls, ls + new.length)
        }
        return v.copy(text = text, selection = sel, composition = null)
    }

    /** Each tap adds one more '#': # -> ## -> ... -> ###### -> none. */
    fun heading(v: TextFieldValue) = mapLines(v) { line ->
        val n = line.takeWhile { it == '#' }.length
        val rest = line.drop(n).trimStart()
        when {
            n == 0 -> "# $line"
            n in 1..5 -> "#".repeat(n + 1) + " " + rest
            else -> rest
        }
    }

    fun bullet(v: TextFieldValue) = mapLines(v) { line ->
        val ind = line.takeWhile { it == ' ' || it == '\t' }
        val b = line.drop(ind.length)
        when {
            b.startsWith("- [ ] ") || b.startsWith("- [x] ") || b.startsWith("- [X] ") -> ind + "- " + b.drop(6)
            b.startsWith("- ") || b.startsWith("* ") || b.startsWith("+ ") -> ind + b.drop(2)
            numRe.containsMatchIn(b) -> ind + "- " + b.replaceFirst(numRe, "")
            else -> "$ind- $b"
        }
    }

    /** Tap cycle: plain -> [ ] -> [x] -> plain. */
    fun todo(v: TextFieldValue) = mapLines(v) { line ->
        val ind = line.takeWhile { it == ' ' || it == '\t' }
        val b = line.drop(ind.length)
        when {
            b.startsWith("- [ ] ") -> ind + "- [x] " + b.drop(6)
            b.startsWith("- [x] ") || b.startsWith("- [X] ") -> ind + b.drop(6)
            b.startsWith("- ") -> ind + "- [ ] " + b.drop(2)
            numRe.containsMatchIn(b) -> ind + "- [ ] " + b.replaceFirst(numRe, "")
            else -> "$ind- [ ] $b"
        }
    }

    fun numbered(v: TextFieldValue) = mapLines(v) { line ->
        val ind = line.takeWhile { it == ' ' || it == '\t' }
        val b = line.drop(ind.length)
        when {
            numRe.containsMatchIn(b) -> ind + b.replaceFirst(numRe, "")
            b.startsWith("- [ ] ") || b.startsWith("- [x] ") || b.startsWith("- [X] ") -> ind + "1. " + b.drop(6)
            b.startsWith("- ") -> ind + "1. " + b.drop(2)
            else -> "${ind}1. $b"
        }
    }

    fun quote(v: TextFieldValue) = mapLines(v) { l ->
        when {
            l.startsWith("> ") -> l.drop(2)
            l.startsWith(">") -> l.drop(1)
            else -> "> $l"
        }
    }

    fun indent(v: TextFieldValue) = mapLines(v) { "  $it" }

    fun outdent(v: TextFieldValue) = mapLines(v) { l ->
        when {
            l.startsWith("  ") -> l.drop(2)
            l.startsWith("\t") -> l.drop(1)
            l.startsWith(" ") -> l.drop(1)
            else -> l
        }
    }

    fun wrap(v: TextFieldValue, l: String, r: String = l): TextFieldValue {
        val t = v.text
        val s = v.selection.min.coerceIn(0, t.length)
        val e = v.selection.max.coerceIn(0, t.length)
        if (s != e && s >= l.length && e + r.length <= t.length &&
            t.startsWith(l, s - l.length) && t.startsWith(r, e)
        ) {
            val text = t.substring(0, s - l.length) + t.substring(s, e) + t.substring(e + r.length)
            return v.copy(text = text, selection = TextRange(s - l.length, e - l.length), composition = null)
        }
        val text = t.substring(0, s) + l + t.substring(s, e) + r + t.substring(e)
        val sel = if (s == e) TextRange(s + l.length) else TextRange(s + l.length, e + l.length)
        return v.copy(text = text, selection = sel, composition = null)
    }

    fun link(v: TextFieldValue, image: Boolean = false): TextFieldValue {
        val t = v.text
        val s = v.selection.min.coerceIn(0, t.length)
        val e = v.selection.max.coerceIn(0, t.length)
        val bang = if (image) "!" else ""
        val label = t.substring(s, e)
        val ins = "$bang[$label]()"
        val cursor = if (s == e) s + bang.length + 1 else s + bang.length + label.length + 3
        return v.copy(text = t.substring(0, s) + ins + t.substring(e), selection = TextRange(cursor), composition = null)
    }

    fun codeBlock(v: TextFieldValue): TextFieldValue {
        val t = v.text
        val s = v.selection.min.coerceIn(0, t.length)
        val e = v.selection.max.coerceIn(0, t.length)
        val pre = if (s > 0 && t[s - 1] != '\n') "\n" else ""
        val sel = t.substring(s, e)
        val ins = pre + "```\n" + sel + "\n```\n"
        val cursor = if (s == e) s + pre.length + 4 else s + ins.length
        return v.copy(text = t.substring(0, s) + ins + t.substring(e), selection = TextRange(cursor), composition = null)
    }

    fun rule(v: TextFieldValue): TextFieldValue {
        val t = v.text
        val s = v.selection.min.coerceIn(0, t.length)
        val e = v.selection.max.coerceIn(0, t.length)
        val pre = if (s > 0 && t[s - 1] != '\n') "\n" else ""
        val ins = pre + "---\n"
        return v.copy(text = t.substring(0, s) + ins + t.substring(e), selection = TextRange(s + ins.length), composition = null)
    }

    fun table(v: TextFieldValue): TextFieldValue {
        val t = v.text
        val s = v.selection.min.coerceIn(0, t.length)
        val e = v.selection.max.coerceIn(0, t.length)
        val pre = if (s > 0 && t[s - 1] != '\n') "\n" else ""
        val a = "| Column 1 | Column 2 |\n"
        val b = "| --- | --- |\n"
        val c = "|  |  |\n"
        val ins = pre + a + b + c
        val cursor = s + pre.length + a.length + b.length + 2
        return v.copy(text = t.substring(0, s) + ins + t.substring(e), selection = TextRange(cursor), composition = null)
    }

    /**
     * When Enter is pressed at the end of a list / to-do / quote line, continue the list.
     * Enter on an empty item removes the marker. Returns null when nothing special applies.
     */
    fun autoContinue(old: TextFieldValue, nw: TextFieldValue): TextFieldValue? {
        if (!old.selection.collapsed || !nw.selection.collapsed) return null
        if (nw.text.length != old.text.length + 1) return null
        val pos = nw.selection.start
        if (pos < 1 || pos > nw.text.length || nw.text[pos - 1] != '\n') return null
        if (nw.text.removeRange(pos - 1, pos) != old.text) return null
        val t = nw.text
        val ps = lineStart(t, pos - 1)
        val prev = t.substring(ps, pos - 1)
        val m = contRe.find(prev) ?: return null
        val ind = m.groupValues[1]
        val marker = m.groupValues[2]
        val content = m.groupValues[3]
        if (content.isBlank()) {
            return nw.copy(text = t.removeRange(ps, pos), selection = TextRange(ps), composition = null)
        }
        val next = when {
            marker.startsWith("- [") -> "- [ ] "
            marker[0].isDigit() -> ((marker.takeWhile { it.isDigit() }.toIntOrNull() ?: 0) + 1).toString() + ". "
            else -> marker
        }
        val ins = ind + next
        return nw.copy(
            text = t.substring(0, pos) + ins + t.substring(pos),
            selection = TextRange(pos + ins.length),
            composition = null
        )
    }
}
