package dev.joseramos.aireader.ai.characters

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.ChapterEntity
import dev.joseramos.aireader.core.data.db.ChapterSource
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.db.RelationType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Simula las respuestas del modelo para dos capítulos de *Crimen y castigo*. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CharacterMergerTest {
    private lateinit var db: AppDatabase
    private lateinit var merger: CharacterMerger
    private lateinit var chapter1: Chapter
    private lateinit var chapter2: Chapter

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        merger = CharacterMerger(db, db.characterDao())
        db.bookDao().upsert(
            BookEntity("b", "Crimen y castigo", null, "b.pdf", "/b.pdf", 500, null, 1, null, IndexStatus.READY, 1f, 1)
        )
        val ids = db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = "b",
                    number = 1,
                    title = "I",
                    startPage = 1,
                    endPage = 40,
                    source = ChapterSource.OUTLINE
                ),
                ChapterEntity(
                    bookId = "b",
                    number = 2,
                    title = "II",
                    startPage = 41,
                    endPage = 80,
                    source = ChapterSource.OUTLINE
                )
            )
        )
        chapter1 = Chapter(ids[0], 1, "I", 1, 40)
        chapter2 = Chapter(ids[1], 2, "II", 41, 80)
    }

    @After
    fun tearDown() = db.close()

    private fun names(vararg pairs: Pair<String, Int>, kind: String = "NAME") =
        pairs.map { (name, page) -> ExtractedName(name, kind, page) }

    @Test
    fun nicknamesJoinTheKnownCharacterAndNewOnesUnlockLater() = runTest {
        merger.merge(
            "b",
            chapter1,
            Extraction(
                characters = listOf(
                    ExtractedCharacter(
                        ref = "n1",
                        names = names("Rodión Románovich Raskólnikov" to 1, "Raskólnikov" to 2),
                        facts = listOf(ExtractedFact(3, "Es un antiguo estudiante."))
                    )
                )
            )
        )
        val rodion = db.characterDao().getCharacters("b").single().id

        merger.merge(
            "b",
            chapter2,
            Extraction(
                characters = listOf(
                    ExtractedCharacter("c$rodion", names("Rodia" to 61, "Rodka" to 62, kind = "NICKNAME")),
                    ExtractedCharacter("n1", names("Avdotia Románovna" to 70, "Dunia" to 999))
                ),
                relations = listOf(ExtractedRelation("n1", "c$rodion", "FAMILY", "hermana de", 70))
            )
        )

        val characters = db.characterDao().getCharacters("b")
        assertEquals(2, characters.size)
        val dunia = characters.single { it.id != rodion }
        assertEquals(70, dunia.firstPage)
        val allNames = db.characterDao().getNames("b")
        assertEquals(
            setOf("Rodión Románovich Raskólnikov", "Raskólnikov", "Rodia", "Rodka"),
            allNames.filter { it.characterId == rodion }.map { it.name }.toSet()
        )
        // Una página fuera del capítulo se lleva al final del capítulo, nunca antes.
        assertEquals(80, allNames.single { it.name == "Dunia" }.firstPage)
        val relation = db.characterDao().getRelations("b").single()
        assertEquals(RelationType.FAMILY, relation.type)
        assertEquals(dunia.id to rodion, relation.fromId to relation.toId)
    }

    @Test
    fun newCharacterWithAKnownFullNameIsTheSamePerson() = runTest {
        merger.merge("b", chapter1, Extraction(listOf(ExtractedCharacter("n1", names("Sofía Semiónovna" to 20)))))
        merger.merge(
            "b",
            chapter2,
            Extraction(
                listOf(ExtractedCharacter("n1", names("Sofía Semiónovna" to 50, "Sonia" to 50, kind = "NICKNAME")))
            )
        )

        val characters = db.characterDao().getCharacters("b")
        assertEquals(1, characters.size)
        assertEquals(setOf("Sofía Semiónovna", "Sonia"), db.characterDao().getNames("b").map { it.name }.toSet())
    }

    @Test
    fun sameGroupMergesDuplicatesAndEndedRelationsGetTheirPage() = runTest {
        merger.merge(
            "b",
            chapter1,
            Extraction(
                characters = listOf(
                    ExtractedCharacter("n1", names("Razumijin" to 10)),
                    ExtractedCharacter("n2", names("Dmitri Prokófich" to 12)),
                    ExtractedCharacter("n3", names("Raskólnikov" to 1))
                ),
                relations = listOf(ExtractedRelation("n1", "n3", "FRIEND", "amigo de", 10))
            )
        )
        val byName = db.characterDao().getNames("b").associate { it.name to it.characterId }
        val razumijin = byName.getValue("Razumijin")
        val dmitri = byName.getValue("Dmitri Prokófich")
        val raskolnikov = byName.getValue("Raskólnikov")

        merger.merge(
            "b",
            chapter2,
            Extraction(
                relations = listOf(ExtractedRelation("c$razumijin", "c$raskolnikov", "FRIEND", "", 75, ended = true)),
                same = listOf(listOf("c$dmitri", "c$razumijin"))
            )
        )

        val characters = db.characterDao().getCharacters("b").map { it.id }
        assertEquals(2, characters.size)
        assertTrue(minOf(razumijin, dmitri) in characters)
        val relation = db.characterDao().getRelations("b").single()
        assertEquals(75, relation.endsAtPage)
        assertNull(db.characterDao().getCharacters("b").firstOrNull { it.id == maxOf(razumijin, dmitri) })
    }
}
