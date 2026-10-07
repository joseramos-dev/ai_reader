package dev.joseramos.aireader.feature.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.ai.characters.CharactersSnapshot
import dev.joseramos.aireader.core.data.book.Bookmark
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.Highlight
import dev.joseramos.aireader.core.data.book.PageText
import dev.joseramos.aireader.core.designsystem.component.Cell
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.component.PrimaryButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.BarSize
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.reader.generated.resources.Res
import dev.joseramos.aireader.feature.reader.generated.resources.bookmark_add
import dev.joseramos.aireader.feature.reader.generated.resources.bookmark_delete
import dev.joseramos.aireader.feature.reader.generated.resources.bookmark_go
import dev.joseramos.aireader.feature.reader.generated.resources.bookmark_note
import dev.joseramos.aireader.feature.reader.generated.resources.bookmark_note_hint
import dev.joseramos.aireader.feature.reader.generated.resources.bookmark_note_title
import dev.joseramos.aireader.feature.reader.generated.resources.bookmark_remove
import dev.joseramos.aireader.feature.reader.generated.resources.bookmark_save
import dev.joseramos.aireader.feature.reader.generated.resources.bookmarks_empty
import dev.joseramos.aireader.feature.reader.generated.resources.characters_on_page
import dev.joseramos.aireader.feature.reader.generated.resources.highlight_copy
import dev.joseramos.aireader.feature.reader.generated.resources.highlight_remove
import dev.joseramos.aireader.feature.reader.generated.resources.highlight_title
import dev.joseramos.aireader.feature.reader.generated.resources.highlights_empty
import dev.joseramos.aireader.feature.reader.generated.resources.reader_cancel
import dev.joseramos.aireader.feature.reader.generated.resources.reader_page_short
import dev.joseramos.aireader.feature.reader.generated.resources.recap_offer_message
import dev.joseramos.aireader.feature.reader.generated.resources.recap_offer_no
import dev.joseramos.aireader.feature.reader.generated.resources.recap_offer_title
import dev.joseramos.aireader.feature.reader.generated.resources.recap_offer_yes
import dev.joseramos.aireader.text.PhraseSplitter
import java.text.DateFormat
import java.util.Date
import org.jetbrains.compose.resources.stringResource

/** Icono de marcapáginas de la barra superior: tocar marca o desmarca; mantener pulsado añade una nota. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookmarkButton(marked: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(BarSize.minTouch)
            .clip(RoundedCornerShape(50))
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stringResource(if (marked) Res.string.bookmark_remove else Res.string.bookmark_add),
                onLongClickLabel = stringResource(Res.string.bookmark_note),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onToggle()
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (marked) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = stringResource(if (marked) Res.string.bookmark_remove else Res.string.bookmark_add),
            tint = AppTheme.colors.accentText,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
internal fun BookmarkNoteDialog(page: Int, initial: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.bookmark_note_title, page)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(MAX_NOTE) },
                placeholder = { Text(stringResource(Res.string.bookmark_note_hint)) },
                maxLines = 4
            )
        },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text(stringResource(Res.string.bookmark_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.reader_cancel)) } }
    )
}

/** Lista de marcapáginas: página, capítulo, nota o primera frase y fecha. Deslizar para borrar. */
@Composable
internal fun BookmarksList(
    bookmarks: List<Bookmark>,
    chapters: List<Chapter>,
    pages: List<PageText>,
    onSelect: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit
) {
    if (bookmarks.isEmpty()) {
        Text(
            stringResource(Res.string.bookmarks_empty),
            style = AppTheme.typography.subheadline,
            color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.padding(Spacing.l)
        )
        return
    }
    val byPage = remember(pages) { pages.associateBy { it.page } }
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    LazyColumn(Modifier.heightIn(max = 560.dp)) {
        items(bookmarks, key = { it.id }) { bookmark ->
            val dismiss = rememberSwipeToDismissBoxState()
            SwipeToDismissBox(
                state = dismiss,
                enableDismissFromStartToEnd = false,
                onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete(bookmark) },
                backgroundContent = {
                    Box(
                        Modifier.fillMaxSize().background(AppTheme.colors.destructive).padding(horizontal = Spacing.l),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            stringResource(Res.string.bookmark_delete),
                            tint = AppTheme.colors.surface
                        )
                    }
                }
            ) {
                val chapter = chapters.lastOrNull { bookmark.page >= it.startPage }
                val firstPhrase = byPage[bookmark.page]?.paragraphs?.firstOrNull()?.let {
                    PhraseSplitter.split(it).firstOrNull()
                }
                val details = listOfNotNull(chapter?.title, dates.format(Date(bookmark.createdAt))).joinToString(" · ")
                Cell(
                    title = bookmark.note ?: firstPhrase ?: stringResource(Res.string.reader_page_short, bookmark.page),
                    subtitle = details,
                    value = stringResource(Res.string.reader_page_short, bookmark.page),
                    onClick = { onSelect(bookmark) },
                    modifier = Modifier.background(AppTheme.colors.surface)
                )
            }
        }
    }
}

/** Texto citado de un subrayado: el fragmento exacto, acotado por si el libro cambió de extracción. */
internal fun quotedText(highlight: Highlight, pages: List<PageText>): String? {
    val paragraph = pages.firstOrNull { it.page == highlight.page }?.paragraphs?.getOrNull(highlight.paragraph)
        ?: return null
    val start = highlight.startOffset.coerceIn(0, paragraph.length)
    val end = highlight.endOffset.coerceIn(start, paragraph.length)
    return paragraph.substring(start, end).takeIf { it.isNotEmpty() }
}

/**
 * Hoja para guardar o editar un subrayado: muestra el texto citado, una nota opcional y, si ya
 * existía, la opción de quitarlo.
 */
@Suppress("DEPRECATION") // LocalClipboard (nuevo) es suspendible; para copiar texto plano basta el clásico.
@Composable
internal fun HighlightSheet(
    quotedText: String,
    initialNote: String?,
    onSave: (String?) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var note by remember { mutableStateOf(initialNote.orEmpty()) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.highlight_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    "«$quotedText»",
                    style = AppTheme.typography.subheadline,
                    color = AppTheme.colors.secondaryLabel
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(MAX_NOTE) },
                    placeholder = { Text(stringResource(Res.string.bookmark_note_hint)) },
                    maxLines = 4
                )
                PlainButton(
                    stringResource(Res.string.highlight_copy),
                    { clipboard.setText(AnnotatedString(quotedText)) }
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text(stringResource(Res.string.bookmark_save)) } },
        dismissButton = {
            Row {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) { Text(stringResource(Res.string.highlight_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.reader_cancel)) }
            }
        }
    )
}

/** Lista de subrayados: texto citado o nota, capítulo y fecha. Deslizar para quitarlo. */
@Composable
internal fun HighlightsList(
    highlights: List<Highlight>,
    chapters: List<Chapter>,
    pages: List<PageText>,
    onSelect: (Highlight) -> Unit,
    onDelete: (Highlight) -> Unit
) {
    if (highlights.isEmpty()) {
        Text(
            stringResource(Res.string.highlights_empty),
            style = AppTheme.typography.subheadline,
            color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.padding(Spacing.l)
        )
        return
    }
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    LazyColumn(Modifier.heightIn(max = 560.dp)) {
        items(highlights, key = { it.id }) { highlight ->
            val dismiss = rememberSwipeToDismissBoxState()
            SwipeToDismissBox(
                state = dismiss,
                enableDismissFromStartToEnd = false,
                onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete(highlight) },
                backgroundContent = {
                    Box(
                        Modifier.fillMaxSize().background(AppTheme.colors.destructive).padding(horizontal = Spacing.l),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            stringResource(Res.string.highlight_remove),
                            tint = AppTheme.colors.surface
                        )
                    }
                }
            ) {
                val chapter = chapters.lastOrNull { highlight.page >= it.startPage }
                val details = listOfNotNull(chapter?.title, dates.format(Date(highlight.createdAt))).joinToString(" · ")
                Cell(
                    title = highlight.note ?: quotedText(highlight, pages)
                        ?: stringResource(Res.string.reader_page_short, highlight.page),
                    subtitle = details,
                    value = stringResource(Res.string.reader_page_short, highlight.page),
                    onClick = { onSelect(highlight) },
                    modifier = Modifier.background(AppTheme.colors.surface)
                )
            }
        }
    }
}

/** Píldora que aparece unos segundos al abrir un libro con marcapáginas. */
@Composable
internal fun BookmarkSuggestionChip(page: Int, onGo: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceElevated)
            .clickable(role = Role.Button, onClick = onGo)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Text(
            stringResource(Res.string.bookmark_go, page),
            style = AppTheme.typography.subheadline,
            color = colors.label
        )
    }
}

/** Tarjeta «¿Repasamos lo anterior?» para libros que llevaban más de una semana sin abrirse. */
@Composable
internal fun RecapOfferCard(onRecap: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m)
            .clip(RoundedCornerShape(Radius.card))
            .background(colors.surfaceElevated)
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(stringResource(Res.string.recap_offer_title), style = AppTheme.typography.headline, color = colors.label)
        Text(
            stringResource(Res.string.recap_offer_message),
            style = AppTheme.typography.subheadline,
            color = colors.secondaryLabel
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(stringResource(Res.string.recap_offer_yes), onRecap)
            PlainButton(stringResource(Res.string.recap_offer_no), onDismiss)
        }
    }
}

/** Fila «En esta página: Raskólnikov · Dunia» del modo PDF, donde no se puede resaltar sobre la página. */
@Composable
internal fun PageCharacterChips(
    characters: CharactersSnapshot,
    page: PageText?,
    onOpenCharacter: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val ids = remember(characters, page) {
        if (page == null || characters.index.isEmpty) emptyList() else characters.index.charactersIn(page.paragraphs)
    }
    if (ids.isEmpty()) return
    val colors = AppTheme.colors
    LazyRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            Text(
                stringResource(Res.string.characters_on_page),
                style = AppTheme.typography.caption,
                color = colors.secondaryLabel
            )
        }
        items(ids, key = { it }) { id ->
            val character = characters.character(id) ?: return@items
            Text(
                character.name,
                style = AppTheme.typography.footnote,
                color = colors.accentText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.accentFill)
                    .clickable(role = Role.Button) { onOpenCharacter(id) }
                    .padding(horizontal = Spacing.s, vertical = Spacing.xxs)
            )
        }
    }
}

private const val MAX_NOTE = 300
