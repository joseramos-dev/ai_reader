package dev.joseramos.aireader.ai.characters

import dev.joseramos.aireader.core.data.book.BookCharacter
import dev.joseramos.aireader.core.data.book.CharacterData
import dev.joseramos.aireader.core.data.book.CharacterFact
import dev.joseramos.aireader.core.data.book.CharacterName
import dev.joseramos.aireader.core.data.book.CharacterRelation
import dev.joseramos.aireader.core.data.book.PageText
import dev.joseramos.aireader.core.data.db.NameKind
import dev.joseramos.aireader.core.data.db.RelationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoilerFilterTest {
    private val rodion = BookCharacter(
        id = 1,
        firstPage = 1,
        names = listOf(
            CharacterName("Rodión Románovich Raskólnikov", NameKind.NAME, 1),
            CharacterName("Raskólnikov", NameKind.SURNAME, 1),
            CharacterName("Rodia", NameKind.NICKNAME, 40),
            CharacterName("Rodka", NameKind.NICKNAME, 120)
        ),
        facts = listOf(CharacterFact(3, "Es un antiguo estudiante."), CharacterFact(90, "Confiesa su crimen."))
    )
    private val dunia = BookCharacter(
        id = 2,
        firstPage = 50,
        names = listOf(
            CharacterName("Avdotia Románovna", NameKind.NAME, 50),
            CharacterName("Dunia", NameKind.NICKNAME, 50)
        )
    )
    private val sonia = BookCharacter(
        id = 3,
        firstPage = 20,
        names = listOf(
            CharacterName("Sonia", NameKind.NICKNAME, 20),
            CharacterName("Sofía Semiónovna Raskólnikova", NameKind.NAME, 200, primaryFrom = 200)
        )
    )
    private val relations = listOf(
        CharacterRelation(10, 2, 1, RelationType.FAMILY, "hermana de", 50),
        CharacterRelation(11, 3, 1, RelationType.ROMANCE, "enamorada de", 80, endsAtPage = 150)
    )
    private val data = CharacterData(listOf(rodion, dunia, sonia), relations)

    private val pages = listOf(
        PageText(10, listOf("Raskólnikov salió a la calle. Raskólnikov tenía fiebre."), false),
        PageText(45, listOf("—Rodia, ¿estás bien? —preguntó."), false),
        PageText(60, listOf("Dunia llegó con su madre. Raskólnikov la abrazó."), false)
    )

    @Test
    fun characterAppearingOnPage50IsHiddenBefore() {
        val before = SpoilerFilter.snapshot(data, maxPage = 49, pages)
        assertNull(before.character(2))
        assertTrue(before.relations.none { it.fromId == 2L || it.toId == 2L })

        val after = SpoilerFilter.snapshot(data, maxPage = 50, pages)
        assertEquals("Dunia", after.character(2)?.otherNames?.single()?.name ?: after.character(2)?.name)
        assertEquals(listOf(10L), after.relationsOf(2).map { it.id })
    }

    @Test
    fun nicknamesAndFactsUnlockByPage() {
        val snapshot = SpoilerFilter.snapshot(data, maxPage = 60, pages)
        val raskolnikov = snapshot.character(1)!!
        val all = listOf(raskolnikov.name) + raskolnikov.otherNames.map { it.name }
        assertTrue("Rodia" in all)
        assertFalse("Rodka" in all)
        assertEquals(listOf("Es un antiguo estudiante."), raskolnikov.facts.map { it.text })
    }

    @Test
    fun currentNameIsTheMostMentionedUntilARenameIsRead() {
        val early = SpoilerFilter.snapshot(data, maxPage = 60, pages)
        assertEquals("Raskólnikov", early.character(1)?.name)
        assertEquals(4, early.character(1)?.mentions)
        assertEquals("Sonia", early.character(3)?.name)

        val late = SpoilerFilter.snapshot(data, maxPage = 210, pages)
        assertEquals("Sofía Semiónovna Raskólnikova", late.character(3)?.name)
    }

    @Test
    fun relationStopsBeingCurrentOnlyAfterItsEndIsRead() {
        assertTrue(SpoilerFilter.snapshot(data, 100, pages).relations.single { it.id == 11L }.isCurrent)
        assertFalse(SpoilerFilter.snapshot(data, 150, pages).relations.single { it.id == 11L }.isCurrent)
    }

    @Test
    fun indexOnlyHighlightsUnlockedNames() {
        val snapshot = SpoilerFilter.snapshot(data, maxPage = 49, pages)
        val text = "Dunia miró a Rodia."
        assertEquals(listOf(1L), snapshot.index.find(text).map { it.characterId })
    }

    @Test
    fun sharedNamesAreNotCountedForAnyone() {
        val twins = CharacterData(
            listOf(
                BookCharacter(
                    1,
                    1,
                    listOf(CharacterName("Iván", NameKind.NAME, 1), CharacterName("Iván Petróvich", NameKind.NAME, 1))
                ),
                BookCharacter(
                    2,
                    1,
                    listOf(CharacterName("Iván", NameKind.NAME, 1), CharacterName("Iván Ilich", NameKind.NAME, 1))
                )
            )
        )
        val snapshot = SpoilerFilter.snapshot(
            twins,
            10,
            listOf(PageText(1, listOf("Iván entró. Iván Ilich salió."), false))
        )
        assertEquals(0, snapshot.character(1)?.mentions)
        assertEquals(1, snapshot.character(2)?.mentions)
    }
}
