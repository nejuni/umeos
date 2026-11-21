package com.mds.io

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** Small, dependency-free shell (sh/bash/zsh) syntax highlighter in Gruvbox colours. */
object ShellHighlight {
    private val shellLangs = setOf(
        "sh", "bash", "shell", "zsh", "ksh", "console", "shellscript",
        "shell-session", "shellsession", "termux", "terminal", "cmd-line"
    )
    private val plainLangs = setOf("", "text", "txt", "plain", "plaintext")
    private val knownCommands = setOf(
        "curl", "wget", "gpg", "sudo", "pip", "pip3", "python", "python3", "apt", "apt-get",
        "pkg", "git", "cd", "ls", "echo", "export", "chmod", "chown", "mkdir", "rm", "cp", "mv",
        "cat", "brew", "npm", "npx", "yt-dlp", "ffmpeg", "tar", "unzip", "make", "cmake",
        "docker", "gradle", "source", "pacman", "dnf", "yum", "winget", "scoop", "choco"
    )
    private val keywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
        "in", "function", "select", "return", "exit", "break", "continue", "local", "export",
        "readonly", "declare", "unset", "set", "shift", "trap", "eval", "exec", "source", "alias"
    )
    // after these words the next word is still a command
    private val cmdKeywords = setOf("then", "else", "elif", "do", "if", "while", "until", "!", "{")
    private val prefixCommands = setOf("sudo", "time", "exec", "nohup", "env", "xargs", "command", "doas")

    private val cCmd = SpanStyle(color = G.yellow)
    private val cKeyword = SpanStyle(color = G.red)
    private val cString = SpanStyle(color = G.green)
    private val cComment = SpanStyle(color = G.gray, fontStyle = FontStyle.Italic)
    private val cVar = SpanStyle(color = G.aqua)
    private val cFlag = SpanStyle(color = G.purple)
    private val cOp = SpanStyle(color = G.orange)
    private val cAssign = SpanStyle(color = G.blue)
    private val cPrompt = SpanStyle(color = G.gray)

    fun isShell(lang: String, code: String): Boolean {
        val l = lang.lowercase()
        if (l in shellLangs) return true
        // blocks labelled text / unlabelled are always treated as shell
        return l in plainLangs
    }

    private const val delims = "\"'$|&;<>()`\\"

    fun highlight(s: String): AnnotatedString = buildAnnotatedString {
        val n = s.length
        var i = 0
        var cmdPos = true

        fun prevIsBoundary(idx: Int): Boolean =
            idx == 0 || s[idx - 1].isWhitespace() || s[idx - 1] in ";|&("

        while (i < n) {
            val c = s[i]

            // newline
            if (c == '\n') {
                append(c)
                cmdPos = true
                i++
                continue
            }
            if (c.isWhitespace()) {
                append(c)
                i++
                continue
            }

            // prompt "$ " at line start
            if (c == '$' && cmdPos && i + 1 < n && s[i + 1] == ' ' && (i == 0 || s[i - 1] == '\n')) {
                withStyle(cPrompt) { append("$") }
                i++
                continue
            }

            // comment
            if (c == '#' && prevIsBoundary(i)) {
                var j = i
                while (j < n && s[j] != '\n') j++
                withStyle(cComment) { append(s.substring(i, j)) }
                i = j
                continue
            }

            // line continuation / escape
            if (c == '\\') {
                if (i + 1 < n && s[i + 1] == '\n') {
                    withStyle(cOp) { append("\\") }
                    i++ // newline handled next loop, but keep cmdPos as-is
                    append('\n')
                    i++
                } else if (i + 1 < n) {
                    append(s.substring(i, i + 2))
                    i += 2
                } else {
                    append(c)
                    i++
                }
                continue
            }

            // strings
            if (c == '"' || c == '\'') {
                var j = i + 1
                while (j < n && s[j] != c) {
                    if (c == '"' && s[j] == '\\') j++
                    j++
                }
                val end = minOf(j + 1, n)
                val str = s.substring(i, end)
                val start = length
                withStyle(cString) { append(str) }
                if (c == '"') {
                    var k = 0
                    while (k < str.length) {
                        if (str[k] == '$' && k + 1 < str.length) {
                            var m = k + 1
                            if (str[m] == '{') {
                                while (m < str.length && str[m] != '}') m++
                                m = minOf(m + 1, str.length)
                            } else {
                                while (m < str.length && (str[m].isLetterOrDigit() || str[m] == '_')) m++
                            }
                            if (m > k + 1) addStyle(cVar, start + k, start + m)
                            k = maxOf(m, k + 1)
                        } else {
                            k++
                        }
                    }
                }
                cmdPos = false
                i = end
                continue
            }

            // variables
            if (c == '$') {
                var j = i + 1
                if (j < n && s[j] == '{') {
                    while (j < n && s[j] != '}') j++
                    j = minOf(j + 1, n)
                } else if (j < n && s[j] == '(') {
                    j++ // just colour "$("
                    withStyle(cVar) { append("$(") }
                    i = j
                    cmdPos = true
                    continue
                } else if (j < n && (s[j].isLetter() || s[j] == '_')) {
                    while (j < n && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                } else if (j < n && (s[j].isDigit() || s[j] in "@*#?!$-")) {
                    j++
                }
                withStyle(cVar) { append(s.substring(i, j)) }
                cmdPos = false
                i = j
                continue
            }

            // operators
            if (c in "|&;<>()") {
                var j = i + 1
                while (j < n && s[j] == c && c in "|&<>") j++
                withStyle(cOp) { append(s.substring(i, j)) }
                if (c in "|&;(") cmdPos = true
                i = j
                continue
            }

            if (c == '`') {
                withStyle(cVar) { append("`") }
                i++
                continue
            }

            // word
            var j = i + 1
            while (j < n && !s[j].isWhitespace() && s[j] !in delims) j++
            val w = s.substring(i, j)

            // assignment  NAME=value
            val eq = w.indexOf('=')
            if (cmdPos && eq > 0 && w.substring(0, eq).all { it.isLetterOrDigit() || it == '_' } &&
                !w[0].isDigit()
            ) {
                withStyle(cAssign) { append(w.substring(0, eq)) }
                append(w.substring(eq))
                i = j
                continue
            }

            when {
                w in keywords && (cmdPos || w == "in" || w == "do" || w == "then" || w == "done" || w == "fi" || w == "esac") -> {
                    withStyle(cKeyword) { append(w) }
                    cmdPos = w in cmdKeywords
                }
                cmdPos -> {
                    withStyle(SpanStyle(color = G.yellow, fontWeight = FontWeight.Bold)) { append(w) }
                    cmdPos = w in prefixCommands
                }
                w.length > 1 && w[0] == '-' && !w[1].isDigit() -> {
                    withStyle(cFlag) { append(w) }
                }
                w.all { it.isDigit() } -> {
                    withStyle(cFlag) { append(w) }
                }
                else -> append(w)
            }
            i = j
        }
    }

    private inline fun AnnotatedString.Builder.withStyle(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
        pushStyle(style)
        block()
        pop()
    }
}
