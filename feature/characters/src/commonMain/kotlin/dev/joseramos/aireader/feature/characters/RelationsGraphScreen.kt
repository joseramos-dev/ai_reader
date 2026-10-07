package dev.joseramos.aireader.feature.characters

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.ai.characters.VisibleCharacter
import dev.joseramos.aireader.ai.characters.VisibleRelation
import dev.joseramos.aireader.core.designsystem.component.AppSwitch
import dev.joseramos.aireader.core.designsystem.component.BarIconButton
import dev.joseramos.aireader.core.designsystem.component.EmptyState
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.BarSize
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.characters.generated.resources.Res
import dev.joseramos.aireader.feature.characters.generated.resources.character_relation_line
import dev.joseramos.aireader.feature.characters.generated.resources.characters_back
import dev.joseramos.aireader.feature.characters.generated.resources.characters_graph
import dev.joseramos.aireader.feature.characters.generated.resources.graph_close
import dev.joseramos.aireader.feature.characters.generated.resources.graph_empty
import dev.joseramos.aireader.feature.characters.generated.resources.graph_go_to_page
import dev.joseramos.aireader.feature.characters.generated.resources.graph_hide_minor
import dev.joseramos.aireader.feature.characters.generated.resources.graph_until_chapter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RelationsGraphScreen(
    state: RelationsGraphUiState,
    onBack: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onSetChapterLimit: (Int?) -> Unit,
    onSetHideMinor: (Boolean) -> Unit
) {
    val colors = AppTheme.colors
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedEdge by remember { mutableStateOf<VisibleRelation?>(null) }

    Box(Modifier.fillMaxSize().background(colors.groupedBackground)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(BarSize.topBar).padding(start = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarIconButton(
                    Icons.AutoMirrored.Rounded.ArrowBackIos,
                    stringResource(Res.string.characters_back),
                    onBack
                )
                Text(
                    stringResource(Res.string.characters_graph),
                    style = AppTheme.typography.headline,
                    color = colors.label,
                    modifier = Modifier.weight(1f).padding(end = BarSize.minTouch),
                    maxLines = 1
                )
            }
            Filters(state, onSetChapterLimit, onSetHideMinor)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!state.loading && state.edges.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Hub,
                        title = stringResource(Res.string.characters_graph),
                        message = stringResource(Res.string.graph_empty),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    GraphCanvas(
                        nodes = state.nodes,
                        edges = state.edges,
                        positions = state.positions,
                        onTapNode = {
                            selectedEdge = null
                            selectedId = it
                        },
                        onTapEdge = { selectedEdge = it },
                        onTapEmpty = { selectedEdge = null }
                    )
                }
            }
            Legend(Modifier.navigationBarsPadding())
        }
        selectedEdge?.let { edge ->
            EdgeCard(
                edge = edge,
                from = state.snapshot.character(edge.fromId)?.name.orEmpty(),
                to = state.snapshot.character(edge.toId)?.name.orEmpty(),
                onOpenPage = { onOpenPage(edge.page) },
                onClose = { selectedEdge = null },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 56.dp)
            )
        }
    }

    selectedId?.let { state.snapshot.character(it) }?.let { character ->
        CharacterSheet(
            character = character,
            snapshot = state.snapshot,
            onOpenPage = { page ->
                selectedId = null
                onOpenPage(page)
            },
            onOpenCharacter = { selectedId = it },
            onDismiss = { selectedId = null }
        )
    }
}

@Composable
private fun Filters(
    state: RelationsGraphUiState,
    onSetChapterLimit: (Int?) -> Unit,
    onSetHideMinor: (Boolean) -> Unit
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m)
            .clip(RoundedCornerShape(Radius.card))
            .background(colors.surface)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs)
    ) {
        val chapters = state.chapters
        if (chapters.size > 1) {
            val last = chapters.lastIndex
            var dragging by remember { mutableFloatStateOf(-1f) }
            val shown = if (dragging >= 0) dragging.toInt() else state.chapterLimit ?: last
            Text(
                stringResource(Res.string.graph_until_chapter, chapters[shown].number, chapters[shown].title),
                style = AppTheme.typography.footnote,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Slider(
                value = shown.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val index = dragging.toInt()
                    onSetChapterLimit(if (index >= last) null else index)
                    dragging = -1f
                },
                valueRange = 0f..last.toFloat(),
                steps = (last - 1).coerceAtLeast(0),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = colors.accent)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.graph_hide_minor),
                style = AppTheme.typography.body,
                color = colors.label,
                modifier = Modifier.weight(1f)
            )
            AppSwitch(state.hideMinor, onSetHideMinor)
        }
    }
}

/** Convierte las coordenadas del grafo a píxeles de pantalla con el zoom y el desplazamiento actuales. */
private class Geometry(
    private val positions: Map<Long, Point>,
    private val width: Float,
    private val height: Float,
    private val scale: Float,
    private val offset: Offset
) {
    private val unit: Float = run {
        val extent = positions.values.maxOfOrNull { max(abs(it.x), abs(it.y)) }?.coerceAtLeast(1f) ?: 1f
        min(width, height) / 2f * FILL / extent
    }

    fun toScreen(p: Point) = Offset(
        width / 2f + p.x * unit * scale + offset.x,
        height / 2f + p.y * unit * scale + offset.y
    )

    fun of(id: Long): Offset? = positions[id]?.let(::toScreen)

    private companion object {
        const val FILL = 0.8f
    }
}

@Composable
private fun GraphCanvas(
    nodes: List<VisibleCharacter>,
    edges: List<VisibleRelation>,
    positions: Map<Long, Point>,
    onTapNode: (Long) -> Unit,
    onTapEdge: (VisibleRelation) -> Unit,
    onTapEmpty: () -> Unit
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val nameStyle = AppTheme.typography.caption.copy(color = colors.label)
    val edgeStyle = AppTheme.typography.caption.copy(color = colors.secondaryLabel)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val maxMentions = nodes.maxOfOrNull { it.mentions }?.coerceAtLeast(1) ?: 1
    val radii = remember(nodes) {
        with(density) {
            nodes.associate { it.id to (MIN_RADIUS + EXTRA_RADIUS * sqrt(it.mentions.toFloat() / maxMentions)).toPx() }
        }
    }
    val touch = with(density) { EDGE_TOUCH.toPx() }
    val labels = edges.associate { it.id to it.labelText() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        // Zoom alrededor del punto entre los dedos.
                        offset = (offset - (centroid - center)) * (newScale / scale) + (centroid - center) + pan
                        scale = newScale
                    }
                }
                .pointerInput(nodes, edges, positions) {
                    detectTapGestures { tap ->
                        val geometry = Geometry(positions, size.width.toFloat(), size.height.toFloat(), scale, offset)
                        val node = nodes.minByOrNull { node ->
                            geometry.of(node.id)?.let { (it - tap).getDistance() } ?: Float.MAX_VALUE
                        }?.takeIf { node ->
                            val at = geometry.of(node.id) ?: return@takeIf false
                            (at - tap).getDistance() <= (radii[node.id] ?: 0f) + touch / 2
                        }
                        val edge = edges.minByOrNull { edge -> edgeDistance(geometry, edge, tap) }
                            ?.takeIf { edgeDistance(geometry, it, tap) <= touch }
                        when {
                            node != null -> onTapNode(node.id)
                            edge != null -> onTapEdge(edge)
                            else -> onTapEmpty()
                        }
                    }
                }
        ) {
            val geometry = Geometry(positions, width, height, scale, offset)
            val showEdgeLabels = scale >= EDGE_LABEL_SCALE || edges.size <= FEW_EDGES
            edges.forEach { edge ->
                val a = geometry.of(edge.fromId) ?: return@forEach
                val b = geometry.of(edge.toId) ?: return@forEach
                drawLine(edge.type.group.color, a, b, strokeWidth = 2.dp.toPx())
                if (showEdgeLabels) {
                    val layout = measurer.measure(labels[edge.id].orEmpty(), edgeStyle)
                    val mid = (a + b) / 2f
                    drawText(layout, topLeft = Offset(mid.x - layout.size.width / 2f, mid.y - layout.size.height / 2f))
                }
            }
            nodes.forEach { node ->
                val at = geometry.of(node.id) ?: return@forEach
                val radius = radii[node.id] ?: return@forEach
                drawCircle(colors.surface, radius, at)
                drawCircle(colors.accentFill, radius, at)
                drawCircle(colors.accent, radius, at, style = Stroke(1.5.dp.toPx()))
                val layout = measurer.measure(node.name, nameStyle)
                drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y + radius + 2.dp.toPx()))
            }
        }
    }
}

/** Distancia de [point] al segmento de la arista. */
private fun edgeDistance(geometry: Geometry, edge: VisibleRelation, point: Offset): Float {
    val a = geometry.of(edge.fromId) ?: return Float.MAX_VALUE
    val b = geometry.of(edge.toId) ?: return Float.MAX_VALUE
    val ab = b - a
    val lengthSquared = ab.x * ab.x + ab.y * ab.y
    if (lengthSquared == 0f) return (point - a).getDistance()
    val t = (((point.x - a.x) * ab.x + (point.y - a.y) * ab.y) / lengthSquared).coerceIn(0f, 1f)
    val closest = a + ab * t
    return hypot(point.x - closest.x, point.y - closest.y)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(modifier: Modifier) {
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally)
    ) {
        RelationGroup.entries.forEach { group ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(group.color))
                Spacer(Modifier.size(Spacing.xxs))
                Text(
                    stringResource(group.label),
                    style = AppTheme.typography.caption,
                    color = AppTheme.colors.secondaryLabel
                )
            }
        }
    }
}

@Composable
private fun EdgeCard(
    edge: VisibleRelation,
    from: String,
    to: String,
    onOpenPage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier
) {
    val colors = AppTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m)
            .clip(RoundedCornerShape(Radius.card))
            .background(colors.surfaceElevated)
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        Text(
            stringResource(Res.string.character_relation_line, from, edge.labelText(), to),
            style = AppTheme.typography.body,
            color = colors.label
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            PlainButton(stringResource(Res.string.graph_go_to_page, edge.page), onOpenPage)
            PlainButton(stringResource(Res.string.graph_close), onClose)
        }
    }
}

private val MIN_RADIUS = 10.dp
private val EXTRA_RADIUS = 14.dp
private val EDGE_TOUCH = 24.dp
private const val MIN_SCALE = 0.4f
private const val MAX_SCALE = 4f
private const val EDGE_LABEL_SCALE = 1.3f
private const val FEW_EDGES = 12
