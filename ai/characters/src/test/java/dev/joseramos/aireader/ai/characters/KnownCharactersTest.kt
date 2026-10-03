package dev.joseramos.aireader.ai.characters

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownCharactersTest {
    @Test
    fun onlyCharactersNamedInTheBlockAndTheMainOnesAreSent() {
        // 25 secundarios con un solo nombre y un protagonista con varios.
        val minor = (1L..25L).associateWith { listOf("Secundario$it") }
        val names = minor + (100L to listOf("Rodión Románovich Raskólnikov", "Rodia", "Rodka"))

        val selected = KnownCharacters.select(names, "Esa tarde Rodion salió a la calle y se cruzó con Secundario7.")

        assertTrue("Nombrado sin tilde en el texto", 100L in selected)
        assertTrue("Nombrado en el bloque", 7L in selected)
        // Sin nombrar: solo van los 20 principales (el protagonista y 19 secundarios), no los 25.
        assertEquals(20, selected.size)
    }

    @Test
    fun shortWordsDoNotMatch() {
        val selected = KnownCharacters.select(
            (1L..30L).associateWith { listOf("Personaje$it") } + (99L to listOf("el de la casa")),
            "Bla bla."
        )
        // «el», «de», «la» no cuentan; «casa» no está en el texto: el 99 solo entraría por principal.
        assertEquals(20, selected.size)
    }

    @Test
    fun schemaRequiresTheFieldsTheMergerReads() {
        val properties = Extraction.schema["properties"]!!.jsonObject
        assertEquals(setOf("characters", "relations", "same"), properties.keys)
        val character = properties["characters"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(
            listOf("ref", "names", "renamedTo", "facts"),
            character["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals("OBJECT", character["type"]!!.jsonPrimitive.content)
    }
}
