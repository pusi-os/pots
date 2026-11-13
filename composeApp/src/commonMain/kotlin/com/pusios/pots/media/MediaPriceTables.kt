package com.pusios.pots.media

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The provider's per-model price table (`GET /api/media/price-table`, public,
 * no key). The source of exact quality prices in the selector dropdown; the
 * rows' `chargeBasis` drives the quote-before-submit policy (spec §19.1,
 * contract `docs/knowledge/media-providers/plusvibe.md`).
 */
@Immutable
data class MediaPriceTableRow(
    /** The combination this row prices, e.g. `{resolution: 2K, quality: low, mode: text-to-image}`. */
    val opts: Map<String, String>,
    val price: Double?,
    val priceStatus: String?,
    val chargeBasis: String?,
)

@Immutable
data class MediaPriceTable(
    val model: String,
    val currency: String?,
    /** Axes that actually move the price — the row key set. */
    val priceAffecting: List<String>,
    val defaults: Map<String, String>,
    val rows: List<MediaPriceTableRow>,
) {
    /** True when the price is finalized by a `POST /api/media/price` quote before submit. */
    val isQuoteBasis: Boolean get() = rows.any { it.chargeBasis == "quote" }
}

/** Tolerant parse; null when the payload is not a usable table (`ok=false`, garbage). */
internal fun parsePriceTable(json: String): MediaPriceTable? = runCatching {
    val root = PlusVibeMediaProvider.JsonTolerant.parseToJsonElement(json).jsonObject
    if (root["ok"]?.jsonPrimitive?.booleanOrNull == false) return null
    val rows = (root["rows"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        MediaPriceTableRow(
            opts = (o["opts"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() },
            price = o["price"]?.jsonPrimitive?.doubleOrNull,
            priceStatus = o["priceStatus"]?.jsonPrimitive?.contentOrNull,
            chargeBasis = o["chargeBasis"]?.jsonPrimitive?.contentOrNull,
        )
    }
    MediaPriceTable(
        model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        currency = root["currency"]?.jsonPrimitive?.contentOrNull,
        priceAffecting = (root["priceAffecting"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
        defaults = (root["defaults"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() },
        rows = rows,
    )
}.getOrNull()

/** Conservative top-bound shown instead of unverified per-quality prices (spec §19.1). */
internal const val QUALITY_SPREAD_NOTE = "Среднее ≈ ×2, высокое ≈ ×3 к цене низкого уровня"

/** Flat catalog price: quality changes nothing (spec §19.1, rule 2). */
internal const val QUALITY_FLAT_NOTE = "Качество не влияет на цену"

/** The price block of the quality dropdown: exact lines and/or one footer note. */
@Immutable
data class QualityPriceInfo(
    /** Exact prices per quality value; values without a price row are absent. */
    val optionPrices: Map<String, Double> = emptyMap(),
    val footerNote: String? = null,
)

/**
 * Resolves the quality dropdown prices for the current combination.
 *
 * Live table: exact rows per (priceAffecting − quality) combination — the
 * selected chips, the generation mode, table defaults. A combination with no
 * row shows nothing: no guess, no note. No table: equal exact catalog prices
 * render flat, anything else gets the conservative note only.
 */
internal fun qualityPriceInfo(table: MediaPriceTable?, axis: MediaParamDef, selectedOpts: Map<String, String>, mode: String? = null): QualityPriceInfo {
    val options = axis.options
    if (options.isEmpty()) return QualityPriceInfo()
    if (table == null || table.rows.isEmpty()) {
        val prices = options.map { it.priceRub }
        val flat = prices.all { it != null } && prices.distinct().size == 1
        return if (flat) {
            QualityPriceInfo(options.associate { it.value to it.priceRub!! }, QUALITY_FLAT_NOTE)
        } else {
            QualityPriceInfo(footerNote = QUALITY_SPREAD_NOTE)
        }
    }
    if ("quality" !in table.priceAffecting) return QualityPriceInfo()
    val base = mutableMapOf<String, String>()
    for (key in table.priceAffecting) {
        if (key == "quality") continue
        val value = selectedOpts[key] ?: mode?.takeIf { key == "mode" } ?: table.defaults[key] ?: return QualityPriceInfo()
        base[key] = value
    }
    val found = linkedMapOf<String, Double>()
    for (option in options) {
        val wanted = base + ("quality" to option.value)
        val row = table.rows.firstOrNull { it.opts == wanted }
        if (row != null && row.priceStatus == "exact" && row.price != null) {
            found[option.value] = row.price
        }
    }
    val spreadConfirmed = found.size == options.size && found.values.distinct().size > 1
    return QualityPriceInfo(found, footerNote = if (spreadConfirmed) QUALITY_SPREAD_NOTE else null)
}
