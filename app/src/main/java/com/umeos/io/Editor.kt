package com.umeos.io

import com.umeos.io.R

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatIndentDecrease
import androidx.compose.material.icons.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EditorView(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    scroll: ScrollState,
    focus: FocusRequester,
    layout: TextLayoutResult?,
    onLayout: (TextLayoutResult) -> Unit
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minH = maxHeight
        val viewportPx = with(density) { maxHeight.toPx() }
        val padPx = with(density) { 16.dp.toPx() }

        // keep the caret visible while typing
        LaunchedEffect(value.selection, layout) {
            val l = layout ?: return@LaunchedEffect
            runCatching {
                val off = value.selection.start.coerceIn(0, l.layoutInput.text.length)
                val r = l.getCursorRect(off)
                val top = r.top + padPx
                val bottom = r.bottom + padPx
                val cur = scroll.value
                if (top < cur) {
                    scroll.scrollTo((top - 8f).toInt().coerceAtLeast(0))
                } else if (bottom > cur + viewportPx) {
                    scroll.scrollTo((bottom - viewportPx + 8f).toInt())
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minH)
                    .padding(16.dp)
                    .focusRequester(focus),
                textStyle = TextStyle(
                    color = G.fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    lineHeight = 23.sp
                ),
                cursorBrush = SolidColor(G.yellow),
                onTextLayout = { onLayout(it) },
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(
                                "Type or paste markdown\u2026",
                                color = G.gray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 15.sp,
                                lineHeight = 23.sp
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}

private class Tool(
    val label: String?,
    val icon: ImageVector?,
    val desc: String,
    val res: Int? = null,
    val edit: (TextFieldValue) -> TextFieldValue
)

private val tools = listOf(
    Tool(null, null, "Heading (tap again for ##, ###...)", R.drawable.ic_heading_h) { Fmt.heading(it) },
    Tool(null, null, "Code block", R.drawable.ic_code_slash) { Fmt.codeBlock(it) },
    Tool("-", null, "Bullet list") { Fmt.bullet(it) },
    Tool(null, null, "To-do", R.drawable.ic_check_square) { Fmt.todo(it) },
    Tool(null, Icons.Filled.FormatBold, "Bold") { Fmt.wrap(it, "**") },
    Tool(null, Icons.Filled.FormatItalic, "Italic") { Fmt.wrap(it, "*") },
    Tool(null, Icons.Filled.StrikethroughS, "Strikethrough") { Fmt.wrap(it, "~~") },
    Tool(null, Icons.Filled.Code, "Inline code") { Fmt.wrap(it, "`") },
    Tool(null, Icons.Filled.Link, "Link") { Fmt.link(it) },
    Tool(null, Icons.Filled.Image, "Image") { Fmt.link(it, image = true) },
    Tool(null, Icons.Filled.FormatQuote, "Quote") { Fmt.quote(it) },
    Tool(null, Icons.Filled.FormatListNumbered, "Numbered list") { Fmt.numbered(it) },
    Tool(null, Icons.Filled.TableChart, "Table") { Fmt.table(it) },
    Tool(null, Icons.Filled.HorizontalRule, "Divider") { Fmt.rule(it) },
    Tool(null, Icons.Filled.FormatIndentIncrease, "Indent") { Fmt.indent(it) },
    Tool(null, Icons.Filled.FormatIndentDecrease, "Outdent") { Fmt.outdent(it) }
)

@Composable
fun FormatBar(onEdit: ((TextFieldValue) -> TextFieldValue) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(G.bg1)
            .horizontalScroll(rememberScrollState())
    ) {
        tools.forEach { t ->
            Box(
                Modifier
                    .width(48.dp)
                    .height(44.dp)
                    .clickable { onEdit(t.edit) },
                contentAlignment = Alignment.Center
            ) {
                if (t.label != null) {
                    Text(
                        t.label,
                        color = G.fg,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                } else if (t.icon != null) {
                    Icon(t.icon, t.desc, tint = G.fg, modifier = Modifier.size(22.dp))
                } else if (t.res != null) {
                    Icon(painter = painterResource(t.res), contentDescription = t.desc, tint = G.fg, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
