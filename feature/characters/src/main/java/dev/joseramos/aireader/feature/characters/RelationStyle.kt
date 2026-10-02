package dev.joseramos.aireader.feature.characters

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.joseramos.aireader.ai.characters.VisibleRelation
import dev.joseramos.aireader.core.data.db.RelationType

// Colores del sistema de iOS: verde, rosa, azul, rojo y gris.
private val Green = Color(0xFF34C759)
private val Pink = Color(0xFFFF2D55)
private val Blue = Color(0xFF0A84FF)
private val Red = Color(0xFFFF3B30)
private val Gray = Color(0xFF8E8E93)

/** Grupos de relaciones con su color en el grafo y en la leyenda. */
internal enum class RelationGroup(val color: Color, @StringRes val label: Int) {
    FAMILY(Green, R.string.relation_group_family),
    LOVE(Pink, R.string.relation_group_love),
    FRIEND(Blue, R.string.relation_group_friend),
    ENEMY(Red, R.string.relation_group_enemy),
    OTHER(Gray, R.string.relation_group_other)
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
            RelationType.SPOUSE -> R.string.relation_spouse
            RelationType.FAMILY -> R.string.relation_family
            RelationType.FRIEND -> R.string.relation_friend
            RelationType.ROMANCE -> R.string.relation_romance
            RelationType.ENEMY -> R.string.relation_enemy
            RelationType.ACQUAINTANCE -> R.string.relation_acquaintance
            RelationType.MET -> R.string.relation_met
            RelationType.WORKS_WITH -> R.string.relation_works_with
            RelationType.OTHER -> R.string.relation_other
        }
    )
}
