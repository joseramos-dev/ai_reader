package dev.joseramos.aireader.feature.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.ai.characters.CharactersSnapshot
import dev.joseramos.aireader.ai.characters.VisibleCharacter
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.Cell
import dev.joseramos.aireader.core.designsystem.component.GroupedSection
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.characters.generated.resources.Res
import dev.joseramos.aireader.feature.characters.generated.resources.character_disclaimer
import dev.joseramos.aireader.feature.characters.generated.resources.character_facts
import dev.joseramos.aireader.feature.characters.generated.resources.character_first_appearance
import dev.joseramos.aireader.feature.characters.generated.resources.character_nothing_yet
import dev.joseramos.aireader.feature.characters.generated.resources.character_other_names
import dev.joseramos.aireader.feature.characters.generated.resources.character_page
import dev.joseramos.aireader.feature.characters.generated.resources.character_relation_ended
import dev.joseramos.aireader.feature.characters.generated.resources.character_relation_line
import dev.joseramos.aireader.feature.characters.generated.resources.character_relations
import org.jetbrains.compose.resources.stringResource

/**
 * Ficha de un personaje: nombre vigente, otros nombres y apodos con su página, primera aparición,
 * datos (cada uno lleva a su página) y relaciones. Solo contiene lo ya leído: [snapshot] viene
 * filtrado por las reglas sin spoilers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CharacterSheet(
    character: VisibleCharacter,
    snapshot: CharactersSnapshot,
    onOpenPage: (Int) -> Unit,
    onOpenCharacter: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = AppTheme.colors
    AppBottomSheet(onDismissRequest = onDismiss, title = character.name, skipPartiallyExpanded = false) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            GroupedSection {
                row {
                    Cell(
                        title = stringResource(Res.string.character_first_appearance),
                        value = stringResource(Res.string.character_page, character.firstPage),
                        showChevron = true,
                        onClick = { onOpenPage(character.firstPage) }
                    )
                }
            }
            if (character.otherNames.isNotEmpty()) {
                Text(
                    stringResource(Res.string.character_other_names).uppercase(),
                    style = AppTheme.typography.footnote,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(start = Spacing.xxl, top = Spacing.xs, bottom = 6.dp)
                )
                FlowRow(
                    Modifier.padding(horizontal = Spacing.m),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    character.otherNames.forEach { name ->
                        Text(
                            "${name.name} · ${stringResource(Res.string.character_page, name.firstPage)}",
                            style = AppTheme.typography.subheadline,
                            color = colors.label,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(colors.surface)
                                .clickable(role = Role.Button) { onOpenPage(name.firstPage) }
                                .padding(horizontal = Spacing.s, vertical = 6.dp)
                        )
                    }
                }
            }
            if (character.facts.isNotEmpty()) {
                GroupedSection(header = stringResource(Res.string.character_facts)) {
                    character.facts.forEach { fact ->
                        row {
                            Cell(
                                title = fact.text,
                                value = stringResource(Res.string.character_page, fact.page),
                                onClick = { onOpenPage(fact.page) }
                            )
                        }
                    }
                }
            }
            val relations = snapshot.relationsOf(character.id)
            if (relations.isNotEmpty()) {
                GroupedSection(header = stringResource(Res.string.character_relations)) {
                    relations.forEach { relation ->
                        val otherId = if (relation.fromId == character.id) relation.toId else relation.fromId
                        val other = snapshot.character(otherId) ?: return@forEach
                        row {
                            val from = snapshot.character(relation.fromId)?.name.orEmpty()
                            val to = snapshot.character(relation.toId)?.name.orEmpty()
                            val line =
                                stringResource(Res.string.character_relation_line, from, relation.labelText(), to)
                            Cell(
                                title = other.name,
                                subtitle = relation.endedAt?.let {
                                    line + " · " + stringResource(Res.string.character_relation_ended, it)
                                } ?: line,
                                value = stringResource(Res.string.character_page, relation.page),
                                showChevron = true,
                                onClick = { onOpenCharacter(otherId) }
                            )
                        }
                    }
                }
            }
            if (character.facts.isEmpty() && relations.isEmpty()) {
                Text(
                    stringResource(Res.string.character_nothing_yet),
                    style = AppTheme.typography.subheadline,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xs)
                )
            }
            Text(
                stringResource(Res.string.character_disclaimer),
                style = AppTheme.typography.caption,
                color = colors.tertiaryLabel,
                modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xs)
            )
        }
    }
}
