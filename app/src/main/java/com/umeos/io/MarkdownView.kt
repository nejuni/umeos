package com.umeos.io

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val LocalBodyColor = compositionLocalOf { G.fg }

/** Called with the part after '#' of an in-document link. Returns true if it was handled. */
val LocalAnchorHandler = compositionLocalOf<(String) -> Boolean> { { false } }

@Composable
fun MarkdownPreview(blocks: List<Block>, listState: LazyListState, modifier: Modifier = Modifier) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (blocks.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. Tap the pencil to start writing.",
                    color = G.gray,
                    fontSize = 15.sp
                )
            }
        }
        items(blocks) { b -> BlockView(b) }
    }
}

@Composable
fun RichText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val anchor = LocalAnchorHandler.current
    if (text.getStringAnnotations("URL", 0, text.length).isEmpty()) {
        Text(text = text, style = style, modifier = modifier)
    } else {
        ClickableText(text = text, modifier = modifier, style = style) { off ->
            val a = text.getStringAnnotations("URL", off, off).firstOrNull()
            if (a != null) {
                if (a.item.startsWith("#")) anchor(a.item.drop(1))
                else runCatching { uri.openUri(a.item) }
            }
        }
    }
}

@Composable
fun BlockView(b: Block) {
    when (b) {
        is Block.Heading -> HeadingView(b)
        is Block.Paragraph -> RichText(
            Markdown.inline(b.text),
            TextStyle(color = LocalBodyColor.current, fontSize = 16.sp, lineHeight = 25.sp)
        )
        is Block.Code -> CodeView(b)
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(G.gray)
            )
            Spacer(Modifier.width(12.dp))
            CompositionLocalProvider(LocalBodyColor provides G.fg4) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    b.blocks.forEach { BlockView(it) }
                }
            }
        }
        is Block.Items -> ItemsView(b)
        Block.Rule -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(G.bg2)
        )
        is Block.Table -> TableView(b)
    }
}

@Composable
private fun HeadingView(h: Block.Heading) {
    val size = when (h.level) {
        1 -> 28
        2 -> 24
        3 -> 20
        4 -> 18
        5 -> 16
        else -> 15
    }
    val color = when (h.level) {
        1 -> G.red
        2 -> G.green
        3 -> G.yellow
        4 -> G.blue
        5 -> G.purple
        else -> G.aqua
    }
    Column(Modifier.padding(top = if (h.level <= 2) 8.dp else 4.dp)) {
        RichText(
            Markdown.inline(h.text),
            TextStyle(
                color = color,
                fontSize = size.sp,
                lineHeight = (size * 1.3f).sp,
                fontWeight = FontWeight.Bold
            )
        )
        if (h.level <= 2) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(G.bg2)
            )
        }
    }
}

@Composable
fun CodeView(b: Block.Code) {
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val shell = remember(b) { ShellHighlight.isShell(b.lang, b.code) }
    val highlighted = remember(b) { if (shell) ShellHighlight.highlight(b.code) else AnnotatedString(b.code) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(G.bg0H)
            .border(1.dp, G.bg1, shape)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                b.lang.ifBlank { if (shell) "sh" else "text" },
                color = G.gray,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    clip.setText(AnnotatedString(b.code))
                    copied = true
                },
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = if (copied) G.green else G.fg4,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = highlighted,
            color = G.fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
        )
    }
}

@Composable
private fun ItemsView(b: Block.Items) {
    val labels = remember(b) {
        val counters = IntArray(12)
        b.items.map { item ->
            val lvl = item.level.coerceIn(0, 11)
            for (k in (lvl + 1)..11) counters[k] = 0
            val mk = item.marker
            if (mk.first().isDigit()) {
                val start = mk.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 1
                counters[lvl] = if (counters[lvl] == 0) start else counters[lvl] + 1
                counters[lvl].toString() + "."
            } else {
                counters[lvl] = 0
                ""
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        b.items.forEachIndexed { idx, item ->
            val ch = item.checked
            Row(Modifier.padding(start = (item.level * 22).dp)) {
                Box(
                    Modifier
                        .widthIn(min = 22.dp)
                        .padding(end = 6.dp)
                ) {
                    if (ch != null) {
                        Icon(
                            imageVector = if (ch) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (ch) G.green else G.gray,
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .size(20.dp)
                        )
                    } else if (labels[idx].isNotEmpty()) {
                        Text(labels[idx], color = G.yellow, fontSize = 16.sp, lineHeight = 25.sp)
                    } else {
                        Text(
                            if (item.level % 2 == 0) "\u2022" else "\u25E6",
                            color = G.orange,
                            fontSize = 16.sp,
                            lineHeight = 25.sp
                        )
                    }
                }
                val done = ch == true
                RichText(
                    Markdown.inline(item.text),
                    TextStyle(
                        color = if (done) G.gray else LocalBodyColor.current,
                        fontSize = 16.sp,
                        lineHeight = 25.sp,
                        textDecoration = if (done) TextDecoration.LineThrough else null
                    ),
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TableView(t: Block.Table) {
    val cols = t.header.size
    val widths = (0 until cols).map { c ->
        val m = maxOf(t.header[c].length, t.rows.maxOfOrNull { it[c].length } ?: 0)
        (m * 8 + 28).coerceIn(80, 260).dp
    }
    Column(
        Modifier
            .horizontalScroll(rememberScrollState())
            .border(1.dp, G.bg2)
    ) {
        for (r in -1 until t.rows.size) {
            val header = r == -1
            val row = if (header) t.header else t.rows[r]
            Row(Modifier.height(IntrinsicSize.Min)) {
                row.forEachIndexed { c, cell ->
                    val align = when (t.aligns.getOrElse(c) { 0 }) {
                        1 -> TextAlign.Center
                        2 -> TextAlign.End
                        else -> TextAlign.Start
                    }
                    Box(
                        Modifier
                            .width(widths[c])
                            .fillMaxHeight()
                            .background(if (header) G.bg1 else G.bg0)
                            .border(0.5.dp, G.bg2)
                            .padding(8.dp)
                    ) {
                        RichText(
                            Markdown.inline(cell),
                            TextStyle(
                                color = G.fg,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                                textAlign = align
                            ),
                            Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
