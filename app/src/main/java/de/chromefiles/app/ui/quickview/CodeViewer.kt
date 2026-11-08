package de.chromefiles.app.ui.quickview

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.chromefiles.app.data.media.HighlightSpan
import de.chromefiles.app.data.media.SyntaxHighlighter
import de.chromefiles.app.data.media.TokenType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

private const val MAX_TEXT_BYTES = 1024 * 1024
private const val BINARY_PROBE_BYTES = 8 * 1024
private const val TAB_AS_SPACES = "    "

/** Dateiendungen, die im QuickView als Text/Code angezeigt werden. */
val TEXT_PREVIEW_EXTENSIONS = setOf(
    "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "rs", "go", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
    "json", "xml", "yml", "yaml", "html", "htm", "css", "scss", "sql", "md", "markdown", "txt", "log", "csv", "tsv", "ini",
    "conf", "cfg", "toml", "gradle", "properties", "env", "gitignore", "dockerfile", "makefile", "svg"
)

private data class LoadedText(
    val text: String,
    val lineStarts: IntArray,
    val spans: List<HighlightSpan>,
    val truncated: Boolean,
    val binary: Boolean
)

private data class CodePalette(
    val keyword: Color, val type: Color, val string: Color, val number: Color, val comment: Color,
    val annotation: Color, val property: Color, val tag: Color, val heading: Color, val link: Color, val variable: Color
)

private val LightPalette = CodePalette(
    keyword = Color(0xFFCF222E), type = Color(0xFF8250DF), string = Color(0xFF0A3069), number = Color(0xFF0550AE),
    comment = Color(0xFF6E7781), annotation = Color(0xFF953800), property = Color(0xFF0550AE), tag = Color(0xFF116329),
    heading = Color(0xFF0550AE), link = Color(0xFF0969DA), variable = Color(0xFF953800)
)

private val DarkPalette = CodePalette(
    keyword = Color(0xFFFF7B72), type = Color(0xFFD2A8FF), string = Color(0xFFA5D6FF), number = Color(0xFF79C0FF),
    comment = Color(0xFF8B949E), annotation = Color(0xFFFFA657), property = Color(0xFF79C0FF), tag = Color(0xFF7EE787),
    heading = Color(0xFF79C0FF), link = Color(0xFF58A6FF), variable = Color(0xFFFFA657)
)

private fun CodePalette.style(token: TokenType): SpanStyle = when (token) {
    TokenType.KEYWORD -> SpanStyle(color = keyword, fontWeight = FontWeight.SemiBold)
    TokenType.TYPE -> SpanStyle(color = this.type)
    TokenType.STRING -> SpanStyle(color = string)
    TokenType.NUMBER -> SpanStyle(color = number)
    TokenType.COMMENT -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
    TokenType.ANNOTATION -> SpanStyle(color = annotation)
    TokenType.PROPERTY -> SpanStyle(color = property)
    TokenType.TAG -> SpanStyle(color = tag)
    TokenType.HEADING -> SpanStyle(color = heading, fontWeight = FontWeight.Bold)
    TokenType.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold)
    TokenType.CODE -> SpanStyle(color = string)
    TokenType.LINK -> SpanStyle(color = link)
    TokenType.VARIABLE -> SpanStyle(color = variable)
}

private fun loadText(file: File, extension: String): LoadedText {
    val buffer = ByteArray(MAX_TEXT_BYTES + 1)
    var read = 0
    FileInputStream(file).use { input ->
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
    }
    val truncated = read > MAX_TEXT_BYTES
    val length = minOf(read, MAX_TEXT_BYTES)
    val probe = minOf(length, BINARY_PROBE_BYTES)
    if ((0 until probe).any { buffer[it] == 0.toByte() }) {
        return LoadedText("", intArrayOf(0), emptyList(), truncated = false, binary = true)
    }
    val text = String(buffer, 0, length, Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n').replace("\t", TAB_AS_SPACES)
    val starts = ArrayList<Int>().apply {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()
    val spans = SyntaxHighlighter.highlight(text, SyntaxHighlighter.languageFor(extension)).sortedBy { it.start }
    return LoadedText(text, starts, spans, truncated, binary = false)
}

/** Erster Span-Index, dessen Ende hinter [offset] liegt (Spans sind nach Start sortiert und überlappen nicht). */
private fun firstSpanEndingAfter(spans: List<HighlightSpan>, offset: Int): Int {
    var lo = 0
    var hi = spans.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (spans[mid].end <= offset) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun buildLine(doc: LoadedText, lineIndex: Int, palette: CodePalette): AnnotatedString {
    val start = doc.lineStarts[lineIndex]
    val end = if (lineIndex + 1 < doc.lineStarts.size) doc.lineStarts[lineIndex + 1] - 1 else doc.text.length
    val line = doc.text.substring(start, end.coerceAtLeast(start))
    return buildAnnotatedString {
        append(line)
        var i = firstSpanEndingAfter(doc.spans, start)
        while (i < doc.spans.size && doc.spans[i].start < end) {
            val span = doc.spans[i]
            val s = (span.start - start).coerceAtLeast(0)
            val e = (span.end - start).coerceAtMost(line.length)
            if (e > s) addStyle(palette.style(span.type), s, e)
            i++
        }
    }
}

/** Text-/Code-Ansicht mit Syntax-Hervorhebung, Zeilennummern, horizontalem Scrollen, Textauswahl und integriertem Editor. */
@Composable
fun CodeViewer(
    file: File,
    extension: String,
    isEditing: Boolean = false,
    editedText: String = "",
    onEditedTextChange: (String) -> Unit = {},
    onInitialTextLoaded: (String) -> Unit = {},
    onSaveShortcut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val loaded by produceState<Result<LoadedText>?>(null, file, file.lastModified()) {
        val res = withContext(Dispatchers.IO) { runCatching { loadText(file, extension) } }
        res.getOrNull()?.let { doc ->
            if (!doc.binary) onInitialTextLoaded(doc.text)
        }
        value = res
    }
    val result = loaded
    when {
        result == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        result.isFailure -> PreviewMessage("Datei kann nicht gelesen werden: ${result.exceptionOrNull()?.message}", modifier)
        result.getOrThrow().binary -> PreviewMessage("Binärdatei – keine Textvorschau möglich.", modifier)
        isEditing -> CodeEditorArea(
            text = editedText,
            onTextChange = onEditedTextChange,
            onSaveShortcut = onSaveShortcut,
            modifier = modifier
        )
        else -> CodeLines(result.getOrThrow(), modifier)
    }
}

@Composable
private fun CodeEditorArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSaveShortcut: () -> Unit,
    modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = colors.onSurface
    )
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val gutterNumbers = remember(lineCount) { (1..lineCount).joinToString("\n") }

    Column(modifier = modifier.background(colors.surfaceContainerLowest)) {
        Text(
            "Bearbeitungsmodus aktiv • Drücken Sie Strg + S oder klicken Sie oben auf „Speichern“, um Änderungen zu sichern.",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onPrimaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(verticalScroll)
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = gutterNumbers,
                style = codeStyle.copy(
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.End
                ),
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp)
            )
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = codeStyle,
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScroll)
                    .padding(end = 16.dp)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown &&
                            (ev.isCtrlPressed || ev.isMetaPressed) &&
                            ev.key == Key.S
                        ) {
                            onSaveShortcut()
                            true
                        } else {
                            false
                        }
                    }
            )
        }
    }
}

@Composable
private fun CodeLines(doc: LoadedText, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val palette = if (colors.surface.luminance() < 0.5f) DarkPalette else LightPalette
    val codeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurface)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidthPx = remember(codeStyle) { measurer.measure("M", codeStyle).size.width }
    val lineCount = doc.lineStarts.size
    val gutterChars = lineCount.toString().length.coerceAtLeast(2)
    val maxLineLength = remember(doc) {
        var maxLen = 0
        for (i in 0 until lineCount) {
            val end = if (i + 1 < lineCount) doc.lineStarts[i + 1] - 1 else doc.text.length
            maxLen = maxOf(maxLen, end - doc.lineStarts[i])
        }
        maxLen
    }

    Column(modifier = modifier.background(colors.surfaceContainerLowest)) {
        if (doc.truncated) {
            Text(
                "Vorschau auf die ersten 1 MB gekürzt.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth().background(colors.tertiaryContainer).padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val contentWidth = with(density) { (charWidthPx * (maxLineLength + gutterChars + 4)).toDp() + 48.dp }
            val width = if (contentWidth > maxWidth) contentWidth else maxWidth
            Box(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                SelectionContainer {
                    LazyColumn(modifier = Modifier.width(width).fillMaxHeight().padding(vertical = 8.dp)) {
                        items(count = lineCount) { index ->
                            Row {
                                Text(
                                    (index + 1).toString(),
                                    style = codeStyle.copy(color = colors.onSurfaceVariant.copy(alpha = 0.6f), textAlign = TextAlign.End),
                                    modifier = Modifier.width(with(density) { (charWidthPx * gutterChars).toDp() } + 24.dp).padding(end = 12.dp)
                                )
                                Text(remember(doc, index, palette) { buildLine(doc, index, palette) }, style = codeStyle, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
