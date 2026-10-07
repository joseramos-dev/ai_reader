package dev.joseramos.aireader.feature.characters

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.joseramos.aireader.ai.characters.VisibleRelation
import dev.joseramos.aireader.core.data.db.RelationType
import dev.joseramos.aireader.feature.characters.generated.resources.Res
import dev.joseramos.aireader.feature.characters.generated.resources.relation_acquaintance
import dev.joseramos.aireader.feature.characters.generated.resources.relation_enemy
import dev.joseramos.aireader.feature.characters.generated.resources.relation_family
import dev.joseramos.aireader.feature.characters.generated.resources.relation_friend
import dev.joseramos.aireader.feature.characters.generated.resources.relation_group_enemy
import dev.joseramos.aireader.feature.characters.generated.resources.relation_group_family
import dev.joseramos.aireader.feature.characters.generated.resources.relation_group_friend
import dev.joseramos.aireader.feature.characters.generated.resources.relation_group_love
import dev.joseramos.aireader.feature.characters.generated.resources.relation_group_other
import dev.joseramos.aireader.feature.characters.generated.resources.relation_met
import dev.joseramos.aireader.feature.characters.generated.resources.relation_other
import dev.joseramos.aireader.feature.characters.generated.resources.relation_romance
import dev.joseramos.aireader.feature.characters.generated.resources.relation_spouse
import dev.joseramos.aireader.feature.characters.generated.resources.relation_works_with
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// Colores del sistema de iOS: verde, rosa, azul, rojo y gris.
private val Green = Color(0xFF34C759)
private val Pink = Color(0xFFFF2D55)
private val Blue = Color(0xFF0A84FF)
private val Red = Color(0xFFFF3B30)
private val Gray = Color(0xFF8E8E93)

/** Grupos de relaciones con su color en el grafo y en la leyenda. */
internal enum class RelationGroup(val color: Color, val label: StringResource) {
    FAMILY(Green, Res.string.relation_group_family),
    LOVE(Pink, Res.string.relation_group_love),
    FRIEND(Blue, Res.string.relation_group_friend),
    ENEMY(Red, Res.string.relation_group_enemy),
    OTHER(Gray, Res.string.relation_group_other)
}

internal val RelationType.group: RelationGroup
    get() = when (this) {
        RelationType.FAMILY -> RelationGroup.FAMILY
        RelationType.SPOUSE, RelationType.ROMANCE -> RelationGroup.LOVE
        RelationType.FRIEND -> RelationGroup.FRIEND
        RelationType.ENEMY -> RelationGroup.ENEMY
        RelationType.ACQUAINTANCE, RelationType.MET, RelationType.WORKS_WITH, RelationType.OTHER -> RelationGroup.OTHER
    }

/** La etiqueta extraída («hermana de») o, si falta, una genérica según el tipo. */
@Composable
internal fun VisibleRelation.labelText(): String = label.ifBlank {
    stringResource(
        when (type) {
            RelationType.SPOUSE -> Res.string.relation_spouse
            RelationType.FAMILY -> Res.string.relation_family
            RelationType.FRIEND -> Res.string.relation_friend
            RelationType.ROMANCE -> Res.string.relation_romance
            RelationType.ENEMY -> Res.string.relation_enemy
            RelationType.ACQUAINTANCE -> Res.string.relation_acquaintance
            RelationType.MET -> Res.string.relation_met
            RelationType.WORKS_WITH -> Res.string.relation_works_with
            RelationType.OTHER -> Res.string.relation_other
        }
    )
}
