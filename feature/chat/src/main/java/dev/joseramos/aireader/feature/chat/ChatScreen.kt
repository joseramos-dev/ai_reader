package dev.joseramos.aireader.feature.chat

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelState
import dev.joseramos.aireader.core.data.book.ChatMessage
import dev.joseramos.aireader.core.data.db.ChatRole
import dev.joseramos.aireader.core.designsystem.component.ApiKeySheet
import dev.joseramos.aireader.core.designsystem.component.BarIconButton
import dev.joseramos.aireader.core.designsystem.component.EmptyState
import dev.joseramos.aireader.core.designsystem.component.PrimaryButton
import dev.joseramos.aireader.core.designsystem.component.UsageRing
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.BarSize
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
data class ChatRoute(val bookId: String)

fun NavGraphBuilder.chatScreen(onBack: () -> Unit, onOpenPage: (bookId: String, page: Int) -> Unit) {
    composable<ChatRoute>(
        enterTransition = { slideIntoContainer(SlideDirection.Up) },
        popExitTransition = { slideOutOfContainer(SlideDirection.Down) }
    ) {
        val viewModel: ChatViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        var askKey by rememberSaveable { mutableStateOf(false) }
        ChatScreen(
            state = state,
            onBack = onBack,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onNewConversation = viewModel::newConversation,
            onOpenPage = { onOpenPage(viewModel.bookId, it) },
            onOpenSettings = { askKey = true },
            onDownloadModel = viewModel::downloadModel,
            onRetryIndexing = viewModel::retryIndexing,
            onSetAntiSpoilers = viewModel::setAntiSpoilers,
            onDismissError = viewModel::dismissError
        )
        // La clave se pide aquí mismo: al guardarla, el chat se habilita sin salir del libro.
        if (askKey) ApiKeySheet(onSave = viewModel::saveApiKey, onDismiss = { askKey = false })
    }
}

@Composable
private fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onDownloadModel: () -> Unit,
    onRetryIndexing: () -> Unit,
    onSetAntiSpoilers: (Boolean) -> Unit,
    onDismissError: () -> Unit
) {
    val colors = AppTheme.colors
    val listState = rememberLazyListState()
    val itemCount = state.messages.size + if (state.streaming != null) 1 else 0
    LaunchedEffect(itemCount, state.streaming?.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(BarSize.topBar)) {
            BarIconButton(
                Icons.AutoMirrored.Rounded.ArrowBackIos,
                stringResource(R.string.chat_back),
                onBack,
                Modifier.align(Alignment.CenterStart)
            )
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.chat_title), style = AppTheme.typography.headline, color = colors.label)
                state.book?.let {
                    Text(
                        it.title,
                        style = AppTheme.typography.caption,
                        color = colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Lo que queda del consumo de IA de hoy (lleno = sin usar, vacío = agotado).
                UsageRing(
                    remaining = state.usage.remaining,
                    contentDescription = stringResource(
                        R.string.chat_usage,
                        (state.usage.remaining * PERCENT).roundToInt()
                    ),
                    size = 22.dp
                )
                if (state.messages.isNotEmpty()) {
                    BarIconButton(Icons.Outlined.EditNote, stringResource(R.string.chat_new), onNewConversation)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.m)
        ) {
            if (state.messages.isEmpty() && state.streaming == null) {
                item { EmptyChat(enabled = state.availability is ChatAvailability.Ready, onSend = onSend) }
            }
            items(state.messages, key = { it.id }) { message -> MessageBubble(message, onOpenPage) }
            state.streaming?.let { partial ->
                item(key = "streaming") {
                    MessageBubble(ChatMessage(-1, ChatRole.ASSISTANT, partial.ifEmpty { "…" }, emptyList()), onOpenPage)
                }
            }
            state.error?.let { error ->
                item(key = "error") {
                    Text(
                        error,
                        style = AppTheme.typography.footnote,
                        color = colors.destructive,
                        modifier = Modifier.clickable(onClick = onDismissError).padding(Spacing.xs)
                    )
                }
            }
        }

        Box(Modifier.navigationBarsPadding()) {
            when (val availability = state.availability) {
                is ChatAvailability.Ready -> Column {
                    SearchNotice(availability.search, onDownloadModel, onRetryIndexing)
                    AntiSpoilersRow(state.antiSpoilers, state.spoilerLimit, onSetAntiSpoilers)
                    InputBar(state.answering, onSend, onStop)
                }
                else -> AvailabilityBanner(availability, onOpenSettings, onRetryIndexing)
            }
        }
    }
}

/**
 * Casilla «Anti-spoilers»: marcada, las respuestas no revelan nada posterior a la página más
 * avanzada leída. Al desmarcarla se pide confirmación, porque a partir de ahí puede haber spoilers.
 */
@Composable
private fun AntiSpoilersRow(enabled: Boolean, limit: Int, onChange: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    var confirmOff by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Checkbox) { checked ->
                if (checked) onChange(true) else confirmOff = true
            }
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = enabled,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.accent,
                uncheckedColor = colors.secondaryLabel,
                checkmarkColor = Color.White
            )
        )
        Text(stringResource(R.string.chat_anti_spoilers), style = AppTheme.typography.subheadline, color = colors.label)
        Text(
            stringResource(
                if (enabled) R.string.chat_anti_spoilers_until else R.string.chat_anti_spoilers_off,
                limit
            ),
            style = AppTheme.typography.footnote,
            color = colors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Spacing.xs)
        )
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.chat_spoilers_dialog_title)) },
            text = { Text(stringResource(R.string.chat_spoilers_dialog_message, limit)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    onChange(false)
                }) { Text(stringResource(R.string.chat_spoilers_disable), color = colors.destructive) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.chat_spoilers_cancel)) }
            }
        )
    }
}

@Composable
private fun EmptyChat(enabled: Boolean, onSend: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = stringResource(R.string.chat_empty_title),
            message = stringResource(R.string.chat_empty_message)
        )
        if (enabled) {
            val suggestions =
                listOf(
                    R.string.chat_suggestion_topic,
                    R.string.chat_suggestion_chapter,
                    R.string.chat_suggestion_ideas,
                    R.string.chat_suggestion_characters
                )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                suggestions.forEach { id ->
                    val text = stringResource(id)
                    Text(
                        text,
                        style = AppTheme.typography.subheadline,
                        color = AppTheme.colors.accentText,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(AppTheme.colors.accentFill)
                            .clickable(role = Role.Button) { onSend(text) }
                            .padding(horizontal = Spacing.s, vertical = Spacing.xs)
                    )
                }
            }
        }
        Text(
            stringResource(R.string.chat_privacy),
            style = AppTheme.typography.caption,
            color = AppTheme.colors.tertiaryLabel,
            modifier = Modifier.padding(top = Spacing.l)
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onOpenPage: (Int) -> Unit) {
    val colors = AppTheme.colors
    val fromUser = message.role == ChatRole.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
        Text(
            message.text,
            style = AppTheme.typography.body,
            color = colors.label,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(Radius.card))
                .background(if (fromUser) colors.accentFill else colors.fill)
                .padding(horizontal = Spacing.s, vertical = Spacing.xs)
        )
        if (message.citations.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = Spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                message.citations.forEach { page ->
                    Text(
                        stringResource(R.string.chat_page, page),
                        style = AppTheme.typography.footnote,
                        color = colors.accentText,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.accentFill)
                            .clickable(role = Role.Button) { onOpenPage(page) }
                            .padding(horizontal = Spacing.xs, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InputBar(answering: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.chat_placeholder)) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            maxLines = 5,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.fill,
                unfocusedContainerColor = colors.fill,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
        val canSend = text.isNotBlank() && !answering
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(50))
                .background(if (canSend || answering) colors.accent else colors.fill)
                .clickable(enabled = canSend || answering, role = Role.Button) {
                    if (answering) {
                        onStop()
                    } else {
                        onSend(text)
                        text = ""
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (answering) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(if (answering) R.string.chat_stop else R.string.chat_send),
                tint = colors.onAccent
            )
        }
    }
}

@Composable
private fun AvailabilityBanner(availability: ChatAvailability, onOpenSettings: () -> Unit, onRetry: () -> Unit) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        when (availability) {
            ChatAvailability.NoApiKey -> {
                Text(
                    stringResource(R.string.chat_no_key),
                    style = AppTheme.typography.subheadline,
                    color = colors.secondaryLabel
                )
                PrimaryButton(stringResource(R.string.chat_add_key), onOpenSettings, Modifier.fillMaxWidth())
            }
            is ChatAvailability.Indexing -> {
                Text(
                    stringResource(R.string.chat_indexing, (availability.progress * PERCENT).toInt()),
                    style = AppTheme.typography.subheadline,
                    color = colors.secondaryLabel
                )
                LinearProgressIndicator(progress = {
                    availability.progress
                }, Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.fill)
            }
            ChatAvailability.Unavailable -> {
                Text(
                    stringResource(R.string.chat_unavailable),
                    style = AppTheme.typography.subheadline,
                    color = colors.secondaryLabel
                )
                PrimaryButton(stringResource(R.string.chat_retry), onRetry, Modifier.fillMaxWidth())
            }
            ChatAvailability.Failed -> {
                Text(
                    stringResource(R.string.chat_index_failed),
                    style = AppTheme.typography.subheadline,
                    color = colors.destructive
                )
                PrimaryButton(stringResource(R.string.chat_retry), onRetry, Modifier.fillMaxWidth())
            }
            ChatAvailability.Loading, is ChatAvailability.Ready -> Unit
        }
    }
}

/**
 * Aviso discreto sobre la barra de escribir cuando se busca solo por palabras (sin embeddings):
 * se puede preguntar igual, pero se explica por qué las respuestas pueden ser peores y qué hacer.
 */
@Composable
private fun SearchNotice(search: SearchQuality, onDownloadModel: () -> Unit, onRetry: () -> Unit) {
    if (search == SearchQuality.Full) return
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        when (search) {
            is SearchQuality.Improving -> {
                Notice(stringResource(R.string.chat_search_improving, (search.progress * PERCENT).toInt()))
                LinearProgressIndicator(progress = {
                    search.progress
                }, Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.fill)
            }
            is SearchQuality.ModelMissing -> ModelMissing(search.model, onDownloadModel)
            SearchQuality.Unsupported -> {
                Notice(stringResource(R.string.chat_search_unsupported))
                NoticeAction(stringResource(R.string.chat_retry), onRetry)
            }
            SearchQuality.Full -> Unit
        }
    }
}

@Composable
private fun ModelMissing(model: ModelState, onDownload: () -> Unit) {
    val sizeMb = (ModelCatalog.e5Small.sizeBytes / BYTES_PER_MB).toInt()
    Notice(stringResource(R.string.chat_model_missing, sizeMb))
    when (model) {
        is ModelState.Downloading -> {
            Notice(stringResource(R.string.chat_downloading, (model.progress * PERCENT).toInt()))
            LinearProgressIndicator(progress = {
                model.progress
            }, Modifier.fillMaxWidth(), color = AppTheme.colors.accent, trackColor = AppTheme.colors.fill)
        }
        ModelState.Installing -> Notice(stringResource(R.string.chat_installing))
        is ModelState.Failed -> {
            Text(model.message, style = AppTheme.typography.footnote, color = AppTheme.colors.destructive)
            NoticeAction(stringResource(R.string.chat_download_model), onDownload)
        }
        else -> NoticeAction(stringResource(R.string.chat_download_model), onDownload)
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = AppTheme.typography.footnote, color = AppTheme.colors.secondaryLabel)
}

@Composable
private fun NoticeAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = AppTheme.typography.footnote,
        color = AppTheme.colors.accentText,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AppTheme.colors.accentFill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.s, vertical = Spacing.xxs)
    )
}

private const val PERCENT = 100
private const val BYTES_PER_MB = 1_000_000
