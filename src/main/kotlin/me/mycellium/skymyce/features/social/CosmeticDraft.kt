package me.mycellium.skymyce.features.social

import com.google.gson.JsonObject
import java.util.Locale

/** Blank restores the default color; incomplete or malformed hex must never change the preview. */
internal fun parseCosmeticColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (value.isBlank()) return null
    require(hex.matches(Regex("[a-fA-F0-9]{6}"))) { "Use six hex digits, such as #62D7F1, or leave blank for default." }
    return hex.toInt(16)
}

/** A style can decorate the real name without requiring a replacement name. */
internal fun cosmeticDraftName(value: String, realName: String, color: Int?, bold: Boolean): JsonObject? {
    if (value.isBlank() && color == null && !bold) return null
    val text = value.trim().ifEmpty { realName }
    return JsonObject().apply {
        addProperty("text", text)
        color?.let { require(it in 0..0xffffff); addProperty("color", "#%06x".format(Locale.ROOT, it)) }
        addProperty("bold", bold)
    }.also { parseCosmeticText(it) }
}

internal enum class CosmeticDraftField { NAME, WIDTH, HEIGHT, DEPTH, NAMETAG, CAPE }

internal fun cosmeticFieldMatches(a: CosmeticProfile, b: CosmeticProfile, field: CosmeticDraftField): Boolean = when (field) {
    CosmeticDraftField.NAME -> a.name == b.name && a.nameStyle?.let(::cosmeticTextJson) == b.nameStyle?.let(::cosmeticTextJson)
    CosmeticDraftField.WIDTH -> a.scaleX == b.scaleX
    CosmeticDraftField.HEIGHT -> a.scaleY == b.scaleY
    CosmeticDraftField.DEPTH -> a.scaleZ == b.scaleZ
    CosmeticDraftField.NAMETAG -> a.nametagScale == b.nametagScale
    CosmeticDraftField.CAPE -> a.capePreset == b.capePreset && a.capeAsset == b.capeAsset && a.localCape == b.localCape
}

/** Late reads/saves adopt authoritative metadata and untouched fields, preserving subsequent local edits. */
internal fun mergeCosmeticDraft(saved: CosmeticProfile, draft: CosmeticProfile, edited: Set<CosmeticDraftField>): CosmeticProfile {
    require(saved.uuid == draft.uuid)
    var result = saved
    if (CosmeticDraftField.NAME in edited) result = result.copy(name = draft.name, nameStyle = draft.nameStyle)
    if (CosmeticDraftField.WIDTH in edited) result = result.copy(scaleX = draft.scaleX)
    if (CosmeticDraftField.HEIGHT in edited) result = result.copy(scale = draft.scaleY, scaleY = draft.scaleY)
    if (CosmeticDraftField.DEPTH in edited) result = result.copy(scaleZ = draft.scaleZ)
    if (CosmeticDraftField.NAMETAG in edited) result = result.copy(nametagScale = draft.nametagScale)
    if (CosmeticDraftField.CAPE in edited) result = result.copy(capePreset = draft.capePreset, capeAsset = draft.capeAsset, localCape = draft.localCape)
    return result
}
