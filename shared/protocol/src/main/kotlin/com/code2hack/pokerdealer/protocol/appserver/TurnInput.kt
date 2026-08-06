package com.code2hack.pokerdealer.protocol.appserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AppServerTurnInput {
    fun text(value: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(value))
    }

    fun image(dataUrl: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("image"))
        put("image_url", JsonPrimitive(dataUrl))
        put("detail", JsonPrimitive("original"))
    }
}
