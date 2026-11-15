package sg.lightchat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

internal enum class ReplyBlockKind { Text, Heading, ListItem, Code }

internal data class ReplyBlock(
    val kind: ReplyBlockKind,
    val text: String,
    val marker: String = "",
    val indent: Int = 0,
)

private val heading = Regex("""^ {0,3}#{1,6}[ \t]+(.+?)(?:[ \t]+#+)?[ \t]*$""")
private val listItem = Regex("""^(\s*)([-+*]|\d{1,9}[.)])\s+(.+)$""")
private val fence = Regex("""^\s{0,3}(`{3,}|~{3,}).*$""")
private val inline = Regex("""(`([^`\n]+)`|\*\*([^*\n]+)\*\*|__([^_\n]+)__)""")

internal fun formatAnswer(text: String): List<ReplyBlock> = buildList {
    val lines = StringBuilder()
    var codeFence: String? = null
    fun finish(kind: ReplyBlockKind) {
        if (lines.isNotEmpty()) add(ReplyBlock(kind, lines.toString().removeSuffix("\n")))
        lines.clear()
    }

    for (line in text.lineSequence()) {
        val marker = fence.matchEntire(line)?.groupValues?.get(1)
        val open = codeFence
        if (open != null) {
            if (marker != null && marker.first() == open.first() && marker.length >= open.length &&
                line.trim().length == marker.length) {
                finish(ReplyBlockKind.Code)
                codeFence = null
            } else {
                lines.appendLine(line)
            }
            continue
        }
        if (marker != null) {
            finish(ReplyBlockKind.Text)
            codeFence = marker
            continue
        }
        val title = heading.matchEntire(line)
        val item = listItem.matchEntire(line)
        when {
            title != null -> {
                finish(ReplyBlockKind.Text)
                add(ReplyBlock(ReplyBlockKind.Heading, title.groupValues[1]))
            }
            item != null -> {
                finish(ReplyBlockKind.Text)
                val bullet = item.groupValues[2]
                add(ReplyBlock(
                    ReplyBlockKind.ListItem,
                    item.groupValues[3],
                    if (bullet.length == 1) "•" else bullet,
                    (item.groupValues[1].length / 2).coerceAtMost(3),
                ))
            }
            line.isBlank() -> finish(ReplyBlockKind.Text)
            else -> lines.appendLine(line)
        }
    }
    finish(if (codeFence == null) ReplyBlockKind.Text else ReplyBlockKind.Code)
}

@Composable
internal fun FormattedAnswer(text: String) {
    val blocks = remember(text) { formatAnswer(text) }
    val colors = LightThemeTokens.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (block in blocks) {
            when (block.kind) {
                ReplyBlockKind.Heading -> {
                    InlineAnswer(block.text, Modifier.padding(top = 3.dp), heading = true)
                }
                ReplyBlockKind.ListItem -> {
                    Row(Modifier.padding(start = (block.indent * 9).dp)) {
                        LightText(block.marker, LightTextVariant.Detail, modifier = Modifier.padding(end = 7.dp))
                        InlineAnswer(block.text, Modifier.weight(1f))
                    }
                }
                ReplyBlockKind.Code -> {
                    Box(Modifier.fillMaxWidth().background(colors.content.copy(alpha = .10f))) {
                        BasicText(
                            block.text,
                            Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                            style = LightThemeTokens.typography.paragraph.copy(
                                color = colors.content, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp,
                            ),
                            softWrap = false,
                        )
                    }
                }
                ReplyBlockKind.Text -> InlineAnswer(block.text)
            }
        }
    }
}

@Composable
private fun InlineAnswer(text: String, modifier: Modifier = Modifier, heading: Boolean = false) {
    val colors = LightThemeTokens.colors
    val styled = remember(text) {
        buildAnnotatedString {
            var offset = 0
            for (match in inline.findAll(text)) {
                append(text.substring(offset, match.range.first))
                if (match.groups[2] != null) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[3].ifEmpty { match.groupValues[4] }) }
                }
                offset = match.range.last + 1
            }
            append(text.substring(offset))
        }
    }
    val style = if (heading) LightThemeTokens.typography.subheading.copy(fontWeight = FontWeight.Bold)
        else LightThemeTokens.typography.paragraph
    BasicText(styled, modifier, style = style.copy(color = colors.content, fontSize = if (heading) 19.sp else 16.sp, lineHeight = 22.sp))
}
