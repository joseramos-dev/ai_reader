package dev.joseramos.aireader.ai.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Esquemas para [LlmRequest.jsonSchema], en el formato `Schema` de la API de Gemini (subconjunto de
 * OpenAPI: tipos en mayúsculas, `required`, `enum`, `nullable`). Por defecto todas las propiedades de
 * un objeto son obligatorias, para que el modelo no las omita.
 */
object ResponseSchema {
    /**
     * [ordered]: el modelo escribe las propiedades en este orden (`propertyOrdering`; si no, Gemini las
     * ordena alfabéticamente). Sirve para que razone en orden, por ejemplo citando algo antes de decidir.
     */
    fun obj(
        vararg properties: Pair<String, JsonObject>,
        nullable: Boolean = false,
        ordered: Boolean = false
    ): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put("properties", JsonObject(properties.toMap()))
        putJsonArray("required") { properties.forEach { add(JsonPrimitive(it.first)) } }
        if (nullable) put("nullable", true)
        if (ordered) putJsonArray("propertyOrdering") { properties.forEach { add(JsonPrimitive(it.first)) } }
    }

    fun array(items: JsonObject): JsonObject = buildJsonObject {
        put("type", "ARRAY")
        put("items", items)
    }

    fun string(enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "STRING")
        if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
    }

    val integer: JsonObject = buildJsonObject { put("type", "INTEGER") }

    val boolean: JsonObject = buildJsonObject { put("type", "BOOLEAN") }
}
