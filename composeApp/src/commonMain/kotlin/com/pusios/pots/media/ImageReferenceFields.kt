package com.pusios.pots.media

import androidx.compose.runtime.Immutable

// Where a picture goes inside `opts`, and how many pictures fit in one request.
//
// The catalog lists the *URL* fields a model declares; the paired base64 field
// is documented provider-wide (`docs/knowledge/media-providers/plusvibe.md`,
// «Свои файлы»): `image_base64` for a single file, `input_base64[]` for up to
// 16 files. So a model that declares only `image_url` still takes local bytes
// through `image_base64`, and a model that declares `input_urls` takes several
// pictures through `input_urls` / `input_base64`.
//
// This matters because the mapper used to look at the single key first: two
// references on a model that declares both `image_url` and `input_urls` ended up
// as one `image_url`, and the second picture silently replaced the first. The
// canvas prompt (canvas + zone) is exactly that case, so the arity is now an
// input of the decision instead of a surprise.

/** One `opts` field a picture goes into, and whether that field is an array. */
@Immutable
data class ImageReferenceField(val key: String, val plural: Boolean)

/** URL fields in the order the mapper prefers them. */
private val SINGLE_URL_FIELDS = listOf("image_url", "reference_image", "first_frame_url")
private val PLURAL_URL_FIELDS = listOf("image_urls", "reference_image_urls", "input_urls")

/** The paired base64 field of every single URL field. */
private val SINGLE_BASE64_BY_URL = mapOf(
    "image_url" to "image_base64",
    "reference_image" to "reference_image_base64",
    "first_frame_url" to "first_frame_base64",
)

/** The provider's multi-file base64 field (up to 16 files), paired with any plural URL field. */
private const val PLURAL_BASE64_FIELD = "input_base64"

/** Pictures one request may carry through `input_base64[]`. */
const val MAX_MULTI_IMAGE_REFERENCES = 16

private fun MediaModel.declaredParamKeys(): Set<String> = params.mapTo(mutableSetOf()) { it.key }

/**
 * The URL field one picture of [several] goes into; null when the model declares
 * no picture field at all.
 *
 * [several] asks for a field that can carry more than one picture. A model with
 * only a single-key field has none — and that is why a second reference must not
 * be sent to it.
 */
fun MediaModel.imageReferenceField(several: Boolean): ImageReferenceField? {
    val keys = declaredParamKeys()
    val single = SINGLE_URL_FIELDS.firstOrNull { it in keys }
    val plural = PLURAL_URL_FIELDS.firstOrNull { it in keys }
    return when {
        several && plural != null -> ImageReferenceField(plural, plural = true)
        several -> single?.let { ImageReferenceField(it, plural = false) }
        single != null -> ImageReferenceField(single, plural = false)
        plural != null -> ImageReferenceField(plural, plural = true)
        else -> null
    }
}

/** The base64 field the same picture goes into (local bytes never fit a URL field). */
fun MediaModel.imageReferenceBase64Field(several: Boolean): ImageReferenceField? {
    val keys = declaredParamKeys()
    val single = SINGLE_URL_FIELDS.firstOrNull { it in keys }
    val plural = PLURAL_URL_FIELDS.firstOrNull { it in keys }
    return when {
        several && plural != null -> ImageReferenceField(PLURAL_BASE64_FIELD, plural = true)
        several -> single?.let { ImageReferenceField(SINGLE_BASE64_BY_URL.getValue(single), plural = false) }
        single != null -> ImageReferenceField(SINGLE_BASE64_BY_URL.getValue(single), plural = false)
        plural != null -> ImageReferenceField(PLURAL_BASE64_FIELD, plural = true)
        else -> null
    }
}

/**
 * How many pictures the model can take in one request: [MAX_MULTI_IMAGE_REFERENCES]
 * through a plural field, 1 through a single-key field, 0 when it declares none.
 */
fun MediaModel.imageReferenceLimit(): Int {
    val field = imageReferenceField(several = true) ?: return 0
    return if (field.plural) MAX_MULTI_IMAGE_REFERENCES else 1
}
