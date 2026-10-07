package dev.joseramos.aireader.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.ai.rag.AskStage
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ChapterKeyPoints
import dev.joseramos.aireader.core.data.db.KeyPointsStatus
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.chat.generated.resources.Res
import dev.joseramos.aireader.feature.chat.generated.resources.chat_key_points_count_events
import dev.joseramos.aireader.feature.chat.generated.resources.chat_key_points_count_ideas
import dev.joseramos.aireader.feature.chat.generated.resources.chat_key_points_empty
import dev.joseramos.aireader.feature.chat.generated.resources.chat_key_points_help
import dev.joseramos.aireader.feature.chat.generated.resources.chat_key_points_pending
import dev.joseramos.aireader.feature.chat.generated.resources.chat_key_points_ready
import dev.joseramos.aireader.feature.chat.generated.resources.chat_key_points_refused
import dev.joseramos.aireader.feature.chat.generated.resources.chat_page
import dev.joseramos.aireader.feature.chat.generated.resources.chat_pages
import dev.joseramos.aireader.feature.chat.generated.resources.chat_source_key_events
import dev.joseramos.aireader.feature.chat.generated.resources.chat_source_key_ideas
import dev.joseramos.aireader.feature.chat.generated.resources.chat_stage_key_events
import dev.joseramos.aireader.feature.chat.generated.resources.chat_stage_key_ideas
import dev.joseramos.aireader.feature.chat.generated.resources.chat_stage_retrying
import dev.joseramos.aireader.feature.chat.generated.resources.chat_stage_searching
import dev.joseramos.aireader.feature.chat.generated.resources.chat_stage_sending
import dev.joseramos.aireader.feature.chat.generated.resources.chat_stage_waiting
import dev.joseramos.aireader.feature.chat.generated.resources.chat_stage_waiting_retry
import dev.joseramos.aireader.feature.chat.generated.resources.chat_stage_waiting_seconds
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Lo que está haciendo el chat mientras aún no llega el texto de la respuesta: buscar en el libro,
 * preparar los hechos clave que faltan, enviar la pregunta o esperar a que el modelo responda.
 */
@Composable
internal fun StageBubble(stage: AskStage?, literature: Boolean) {
    val colors = AppTheme.colors
    // Los segundos que lleva en esta fase: si Gemini tarda, que se vea que sigue esperando.
    var seconds by remember(stage) { mutableIntStateOf(0) }
    LaunchedEffect(stage) {
        while (true) {
            delay(SECOND_MS)
            seconds++
        }
    }
    val text = when (stage) {
        is AskStage.LoadingKeyPoints -> stringResource(
            if (literature) Res.string.chat_stage_key_events else Res.string.chat_stage_key_ideas,
            stage.done,
            stage.total
        )
        AskStage.Sending -> stringResource(Res.string.chat_stage_sending)
        is AskStage.Waiting -> when {
            stage.attempt > 1 ->
                stringResource(Res.string.chat_stage_waiting_retry, stage.attempt, stage.maxAttempts, seconds)
            seconds >= SHOW_SECONDS_AFTER -> stringResource(Res.string.chat_stage_waiting_seconds, seconds)
            else -> stringResource(Res.string.chat_stage_waiting)
        }
        is AskStage.Retrying -> stringResource(Res.string.chat_stage_retrying, stage.attempt, stage.maxAttempts)
        AskStage.Searching, null -> stringResource(Res.string.chat_stage_searching)
    }
    Row(
        Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(colors.fill)
            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = colors.accent, strokeWidth = 2.dp)
        Text(text, style = AppTheme.typography.subheadline, color = colors.secondaryLabel)
    }
}

/**
 * Menú de la barra del chat: los capítulos del libro y si ya tienen hechos clave (ideas clave si no es
 * una novela). Se generan solo cuando una pregunta los necesita, así que muchos estarán sin preparar.
 * Al desplegar un capítulo se ven sus puntos; tocar uno abre el libro en su página ([onOpenPage]).
 */
@Composable
internal fun KeyPointsSheet(
    overview: KeyPointsOverview,
    literature: Boolean,
    onOpenPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = AppTheme.colors
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(if (literature) Res.string.chat_source_key_events else Res.string.chat_source_key_ideas),
        skipPartiallyExpanded = false
    ) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                stringResource(Res.string.chat_key_points_ready, overview.ready, overview.chapters.size),
                style = AppTheme.typography.headline,
                color = colors.label
            )
            Text(
                stringResource(Res.string.chat_key_points_help),
                style = AppTheme.typography.footnote,
                color = colors.secondaryLabel
            )
        }
        LazyColumn(Modifier.heightIn(max = 560.dp).padding(top = Spacing.s)) {
            items(overview.chapters, key = { it.first.id }) { (chapter, keyPoints) ->
                ChapterRow(chapter, keyPoints, literature, onOpenPage)
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, keyPoints: ChapterKeyPoints?, literature: Boolean, onOpenPage: (Int) -> Unit) {
    val colors = AppTheme.colors
    var expanded by rememberSaveable(chapter.id) { mutableStateOf(false) }
    val points = keyPoints?.points.orEmpty()
    val status = when (keyPoints?.status) {
        null -> stringResource(Res.string.chat_key_points_pending)
        KeyPointsStatus.REFUSED -> stringResource(Res.string.chat_key_points_refused)
        KeyPointsStatus.EMPTY -> stringResource(Res.string.chat_key_points_empty)
        KeyPointsStatus.READY -> pluralStringResource(
            if (literature) Res.plurals.chat_key_points_count_events else Res.plurals.chat_key_points_count_ideas,
            points.size,
            points.size
        )
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = points.isNotEmpty(), role = Role.Button) { expanded = !expanded }
                .padding(horizontal = Spacing.l, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (keyPoints?.status == KeyPointsStatus.READY) colors.accent else colors.separator,
                modifier = Modifier.size(20.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    chapter.title,
                    style = AppTheme.typography.body,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(Res.string.chat_pages, chapter.startPage, chapter.endPage) + " · " + status,
                    style = AppTheme.typography.caption,
                    color = colors.secondaryLabel
                )
            }
            if (points.isNotEmpty()) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = colors.secondaryLabel
                )
            }
        }
        if (expanded) {
            points.forEach { point ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenPage(point.page) }
                        .padding(start = Spacing.xxl + Spacing.l, end = Spacing.l, top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        stringResource(Res.string.chat_page, point.page),
                        style = AppTheme.typography.footnote,
                        color = colors.accentText
                    )
                    Text(point.text, style = AppTheme.typography.footnote, color = colors.label)
                }
            }
        }
    }
}

private const val SECOND_MS = 1_000L

/** Hasta este segundo de espera no se muestra la cuenta: casi siempre responde antes. */
private const val SHOW_SECONDS_AFTER = 5
