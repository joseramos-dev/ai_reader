package dev.joseramos.aireader.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import coil3.compose.AsyncImage
import dev.joseramos.aireader.core.data.book.Book
import dev.joseramos.aireader.core.data.db.DocumentType
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.BarIconButton
import dev.joseramos.aireader.core.designsystem.component.Cell
import dev.joseramos.aireader.core.designsystem.component.EmptyState
import dev.joseramos.aireader.core.designsystem.component.GroupedSection
import dev.joseramos.aireader.core.designsystem.component.LargeTitleScaffold
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute

/** [onOpenBook] abre el libro donde se quedó la lectura o, con página, en esa página. */
fun NavGraphBuilder.libraryScreen(onOpenBook: (bookId: String, page: Int?) -> Unit) {
    composable<LibraryRoute> {
        val viewModel: LibraryViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let(viewModel::import)
        }
        LibraryScreen(
            state = state,
            onOpenBook = onOpenBook,
            onImport = { picker.launch(arrayOf("application/pdf")) },
            onSort = viewModel::setSort,
            onSetType = viewModel::setDocumentType,
            onDelete = viewModel::delete,
            onDismissError = viewModel::dismissError
        )
    }
}

private const val COLUMNS = 3

private enum class LibrarySheet { SORT }

@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    onOpenBook: (String, Int?) -> Unit,
    onImport: () -> Unit,
    onSort: (SortMode) -> Unit,
    onSetType: (String, DocumentType) -> Unit,
    onDelete: (String) -> Unit,
    onDismissError: () -> Unit
) {
    var sheet by rememberSaveable { mutableStateOf<LibrarySheet?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var typeBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

    LargeTitleScaffold(
        title = stringResource(R.string.library_title),
        actions = {
            if (state.books.isNotEmpty()) {
                BarIconButton(Icons.Outlined.SwapVert, stringResource(R.string.library_sort), {
                    sheet =
                        LibrarySheet.SORT
                })
            }
            BarIconButton(Icons.Rounded.Add, stringResource(R.string.library_import), onImport)
        }
    ) {
        if (state.importing) {
            item(key = "importing") {
                Column(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
                    Text(
                        stringResource(R.string.library_importing),
                        style = AppTheme.typography.footnote,
                        color = AppTheme.colors.secondaryLabel
                    )
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = AppTheme.colors.accent,
                        trackColor = AppTheme.colors.fill
                    )
                }
            }
        }
        if (!state.loading && state.books.isEmpty() && !state.importing) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.AutoStories,
                    title = stringResource(R.string.library_empty_title),
                    message = stringResource(R.string.library_empty_message),
                    actionLabel = stringResource(R.string.library_import),
                    onAction = onImport,
                    modifier = Modifier.padding(top = 96.dp)
                )
            }
        }
        state.books.chunked(COLUMNS).forEach { row ->
            item(key = row.first().id) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.l)
                ) {
                    row.forEach { book ->
                        BookTile(book, { onOpenBook(book.id, null) }, { selectedId = book.id }, Modifier.weight(1f))
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    if (sheet == LibrarySheet.SORT) {
        AppBottomSheet(onDismissRequest = { sheet = null }, title = stringResource(R.string.library_sort)) {
            GroupedSection {
                SortMode.entries.forEach { mode ->
                    row {
                        Cell(
                            title = stringResource(
                                if (mode ==
                                    SortMode.RECENT
                                ) {
                                    R.string.library_sort_recent
                                } else {
                                    R.string.library_sort_title
                                }
                            ),
                            value = if (mode == state.sort) "✓" else null,
                            onClick = {
                                onSort(mode)
                                sheet = null
                            }
                        )
                    }
                }
            }
        }
    }

    state.books.firstOrNull { it.id == selectedId }?.let { book ->
        BookInfoSheet(
            book = book,
            onOpen = { page ->
                selectedId = null
                onOpenBook(book.id, page)
            },
            onChangeType = {
                selectedId = null
                typeBookId = book.id
            },
            onDelete = {
                selectedId = null
                confirmDeleteId = book.id
            },
            onDismiss = { selectedId = null }
        )
    }

    state.books.firstOrNull { it.id == typeBookId }?.let { book ->
        AppBottomSheet(onDismissRequest = { typeBookId = null }, title = stringResource(R.string.library_type)) {
            GroupedSection(footer = stringResource(R.string.library_type_footer)) {
                DocumentType.entries.forEach { type ->
                    row {
                        Cell(
                            title = stringResource(typeLabel(type)),
                            onClick = {
                                onSetType(book.id, type)
                                typeBookId = null
                            },
                            trailing = {
                                if (type == book.documentType) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = AppTheme.colors.accentText,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    state.books.firstOrNull { it.id == confirmDeleteId }?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text(stringResource(R.string.library_delete_title, book.title)) },
            text = { Text(stringResource(R.string.library_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(book.id)
                    confirmDeleteId = null
                }) { Text(stringResource(R.string.library_delete), color = AppTheme.colors.destructive) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text(stringResource(R.string.library_cancel)) }
            }
        )
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.library_error_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.library_ok)) } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(book: Book, onOpen: () -> Unit, onLongPress: () -> Unit, modifier: Modifier) {
    val colors = AppTheme.colors
    val haptics = LocalHapticFeedback.current
    Column(
        modifier.combinedClickable(
            role = Role.Button,
            onClick = onOpen,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress()
            }
        )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_RATIO)
                .shadow(4.dp, RoundedCornerShape(Radius.cell))
                .clip(RoundedCornerShape(Radius.cell))
                .background(colors.accentFill),
            contentAlignment = Alignment.Center
        ) {
            if (book.coverPath != null) {
                AsyncImage(
                    model = File(book.coverPath!!),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Outlined.AutoStories, contentDescription = null, tint = colors.accentText)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            style = AppTheme.typography.subheadline,
            color = colors.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        TileStatus(book)
    }
}

@Composable
private fun TileStatus(book: Book) {
    val colors = AppTheme.colors
    // El texto es la primera mitad de la indexación; la segunda (capítulos y búsqueda del chat) ya
    // no impide leer, así que se muestra aparte y sin barra de «preparando».
    val preparing = book.indexStatus in setOf(IndexStatus.PENDING, IndexStatus.EXTRACTING_TEXT)
    val text = when {
        book.indexStatus == IndexStatus.FAILED -> stringResource(R.string.library_prepare_failed)
        preparing -> stringResource(R.string.library_preparing, (book.indexProgress * 2 * PERCENT).toInt())
        book.indexStatus == IndexStatus.EMBEDDING && book.indexProgress < 1f -> stringResource(
            R.string.library_preparing_chat,
            ((book.indexProgress - TEXT_SHARE) * 2 * PERCENT).toInt().coerceIn(0, PERCENT)
        )
        book.currentPage != null -> stringResource(R.string.library_progress, (book.readingProgress * PERCENT).toInt())
        else -> stringResource(R.string.library_new)
    }
    Text(
        text,
        style = AppTheme.typography.caption,
        color = if (book.indexStatus ==
            IndexStatus.FAILED
        ) {
            colors.destructive
        } else {
            colors.secondaryLabel
        }
    )
    val progress = if (preparing) book.indexProgress * 2 else book.readingProgress
    if (preparing || book.currentPage != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = if (preparing) colors.tertiaryLabel else colors.accent,
            trackColor = colors.fill,
            drawStopIndicator = {}
        )
    }
}

@Composable
private fun BookInfoSheet(
    book: Book,
    onOpen: (page: Int?) -> Unit,
    onChangeType: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AppBottomSheet(onDismissRequest = onDismiss, title = book.title) {
        GroupedSection {
            row { Cell(stringResource(R.string.library_info_pages), value = book.pageCount.toString()) }
            row {
                Cell(
                    stringResource(R.string.library_info_imported),
                    value = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(book.importedAt))
                )
            }
            row {
                Cell(
                    stringResource(R.string.library_info_status),
                    value = stringResource(statusLabel(book.indexStatus))
                )
            }
            row {
                Cell(
                    stringResource(R.string.library_type),
                    value = book.documentType?.let { stringResource(typeLabel(it)) }
                        ?: stringResource(R.string.library_type_pending),
                    showChevron = true,
                    onClick = onChangeType
                )
            }
        }
        GroupedSection {
            row { Cell(stringResource(R.string.library_open), onClick = { onOpen(null) }, showChevron = true) }
            book.lastBookmarkPage?.let { page ->
                row {
                    Cell(
                        stringResource(R.string.library_open_bookmark, page),
                        onClick = { onOpen(page) },
                        showChevron = true
                    )
                }
            }
            row { Cell(stringResource(R.string.library_delete), destructive = true, onClick = onDelete) }
        }
    }
}

private fun statusLabel(status: IndexStatus) = when (status) {
    IndexStatus.READY -> R.string.library_status_ready
    IndexStatus.TEXT_READY -> R.string.library_status_needs_model
    IndexStatus.FAILED -> R.string.library_status_failed
    else -> R.string.library_status_preparing
}

private fun typeLabel(type: DocumentType) = when (type) {
    DocumentType.LITERATURE -> R.string.library_type_literature
    DocumentType.SCIENTIFIC -> R.string.library_type_scientific
    DocumentType.EDUCATIONAL -> R.string.library_type_educational
    DocumentType.GENERIC -> R.string.library_type_generic
}

private const val COVER_RATIO = 0.7f
private const val PERCENT = 100

/** Parte del progreso de indexación que corresponde al texto (ver IndexWorker). */
private const val TEXT_SHARE = 0.5f
