package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerElement
import com.code2hack.pokerdealer.protocol.PhotoAssetCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AppServerPhotoAsset(
    val mimeType: String,
    val bytes: ByteArray,
) {
    init {
        require(mimeType.isNotBlank()) { "Photo MIME type must not be blank" }
        require(bytes.isNotEmpty()) { "Photo bytes must not be empty" }
    }
}

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

    fun image(asset: AppServerPhotoAsset): JsonObject = image(
        PhotoAssetCodec.dataUrl(asset.mimeType, asset.bytes),
    )

    suspend fun fromDraft(
        draft: ComposerDraft,
        resolvePhoto: suspend (String) -> AppServerPhotoAsset?,
    ): List<JsonObject> {
        require(draft.isSubmittable) { "Turn input must not be empty" }
        return draft.elements.map { element ->
            when (element) {
                is ComposerElement.Text -> text(element.value)
                is ComposerElement.Photo -> resolvePhoto(element.assetId)?.let(::image)
                    ?: throw IllegalArgumentException(
                        "Photo asset ${element.assetId} is unavailable",
                    )
            }
        }
    }
}
