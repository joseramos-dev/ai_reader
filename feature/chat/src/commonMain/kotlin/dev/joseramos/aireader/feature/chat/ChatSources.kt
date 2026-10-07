package dev.joseramos.aireader.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.core.data.book.ChatMessage
import dev.joseramos.aireader.core.data.book.ChatSource
import dev.joseramos.aireader.core.data.book.SourceKind
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.chat.generated.resources.Res
import dev.joseramos.aireader.feature.chat.generated.resources.chat_page
import dev.joseramos.aireader.feature.chat.generated.resources.chat_pages
import dev.joseramos.aireader.feature.chat.generated.resources.chat_source_book
import dev.joseramos.aireader.feature.chat.generated.resources.chat_source_cited
import dev.joseramos.aireader.feature.chat.generated.resources.chat_source_key_events
import dev.joseramos.aireader.feature.chat.generated.resources.chat_source_key_ideas
import dev.joseramos.aireader.feature.chat.generated.resources.chat_source_less
import dev.joseramos.aireader.feature.chat.generated.resources.chat_source_more
import dev.joseramos.aireader.feature.chat.generated.resources.chat_source_open_chapter
import dev.joseramos.aireader.feature.chat.generated.resources.chat_source_open_passage
import dev.joseramos.aireader.feature.chat.generated.resources.chat_sources
import dev.joseramos.aireader.feature.chat.generated.resources.chat_sources_book
import dev.joseramos.aireader.feature.chat.generated.resources.chat_sources_hide
import dev.joseramos.aireader.feature.chat.generated.resources.chat_sources_key_events
import dev.joseramos.aireader.feature.chat.generated.resources.chat_sources_key_ideas
import dev.joseramos.aireader.feature.chat.generated.resources.chat_sources_mixed_events
import dev.joseramos.aireader.feature.chat.generated.resources.chat_sources_mixed_ideas
import dev.joseramos.aireader.feature.chat.generated.resources.chat_sources_show
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * «Fuentes»: desplegable bajo la respuesta con los fragmentos que se enviaron a la IA para darla. El
 * título dice si son texto del libro o hechos clave de los capítulos (ideas clave si no es una novela:
 * [literature]), y se marcan los que la respuesta cita. Tocar uno llama a [onOpen].
 */
@Composable
internal fun Sources(message: ChatMessage, literature: Boolean, onOpen: (ChatSource) -> Unit) {
    val colors = AppTheme.colors
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val fromBook = message.sources.count { it.kind == SourceKind.BOOK }
    val fromKeyPoints = message.sources.size - fromBook
    val origin = when {
        fromKeyPoints == 0 -> pluralStringResource(Res.plurals.chat_sources_book, fromBook, fromBook)
        fromBook == 0 -> pluralStringResource(
            if (literature) Res.plurals.chat_sources_key_events else Res.plurals.chat_sources_key_ideas,
            fromKeyPoints,
            fromKeyPoints
        )
        else -> stringResource(
            if (literature) Res.string.chat_sources_mixed_events else Res.string.chat_sources_mixed_ideas,
            fromBook,
            fromKeyPoints
        )
    }
    Column(Modifier.widthIn(max = 320.dp).padding(top = Spacing.xxs)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(
                        if (expanded) Res.string.chat_sources_hide else Res.string.chat_sources_show
                    )
                ) { expanded = !expanded }
                .padding(end = Spacing.xs, top = Spacing.xxs, bottom = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.secondaryLabel,
                modifier = Modifier.size(20.dp)
            )
            Text(
                stringResource(Res.string.chat_sources),
                style = AppTheme.typography.footnote,
                fontWeight = FontWeight.SemiBold,
                color = colors.secondaryLabel
            )
            Text(
                " · $origin",
                style = AppTheme.typography.footnote,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(top = Spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                message.sources.forEach { source ->
                    key(source.number) {
                        SourceCard(source, literature, cited = source.isCitedBy(message.citations)) { onOpen(source) }
                    }
                }
            }
        }
    }
}

/**
 * Un fragmento enviado a la IA: de dónde sale (libro o hechos clave), sus páginas y capítulo, si la respuesta
 * lo cita, y su texto, recortado a unas líneas con «Ver todo».
 */
@Composable
private fun SourceCard(source: ChatSource, literature: Boolean, cited: Boolean, onOpen: () -> Unit) {
    val colors = AppTheme.colors
    val keyPoints = source.kind == SourceKind.KEY_POINTS
    var full by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    val pages = if (source.startPage == source.endPage) {
        stringResource(Res.string.chat_page, source.startPage)
    } else {
        stringResource(Res.string.chat_pages, source.startPage, source.endPage)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.cell))
            .background(colors.fill)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(
                    if (keyPoints) Res.string.chat_source_open_chapter else Res.string.chat_source_open_passage
                ),
                onClick = onOpen
            )
            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(
                    when {
                        !keyPoints -> Res.string.chat_source_book
                        literature -> Res.string.chat_source_key_events
                        else -> Res.string.chat_source_key_ideas
                    }
                ),
                style = AppTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
                color = if (keyPoints) colors.label else colors.accentText,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (keyPoints) colors.fill else colors.accentFill)
                    .padding(horizontal = Spacing.xs, vertical = 1.dp)
            )
            Text(
                listOfNotNull(pages, source.chapter).joinToString(" · "),
                style = AppTheme.typography.caption,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (cited) {
                Text(
                    stringResource(Res.string.chat_source_cited),
                    style = AppTheme.typography.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accentText
                )
            }
        }
        Text(
            // Párrafos con un solo salto de línea: en la tarjeta, la línea en blanco ocupa sin aportar.
            source.text.replace(paragraphBreak, "\n"),
            style = AppTheme.typography.footnote,
            color = colors.label,
            maxLines = if (full) Int.MAX_VALUE else SOURCE_PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!full) overflows = it.hasVisualOverflow }
        )
        if (overflows || full) {
            Text(
                stringResource(if (full) Res.string.chat_source_less else Res.string.chat_source_more),
                style = AppTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
                color = colors.accentText,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button) { full = !full }
                    .padding(vertical = 2.dp)
            )
        }
    }
}

/** Líneas del texto de una fuente que se ven antes de «Ver todo». */
private const val SOURCE_PREVIEW_LINES = 4

private val paragraphBreak = Regex("\n\\s*\n")
