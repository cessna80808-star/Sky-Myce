package me.mycellium.skymyce.features.social

import net.minecraft.client.renderer.entity.state.AvatarRenderState
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Size
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.hud.bootstrapHeadlessOwo
import java.util.UUID

internal fun cosmeticDraftChecks() {
    cosmeticWorkspaceChecks()
    check(parseCosmeticColor("#62d7F1") == 0x62D7F1)
    check(parseCosmeticColor("  000000  ") == 0 && parseCosmeticColor("ffffff") == 0xffffff)
    check(parseCosmeticColor(" ") == null)
    for (value in listOf("#", "fff", "#12345", "#1234567", "#gg0000", "#12 456", "#123456\nextra"))
        check(runCatching { parseCosmeticColor(value) }.isFailure)
    check(cosmeticDraftName("", "ExamplePlayer", null, false) == null)
    val cyan = parseCosmeticText(requireNotNull(cosmeticDraftName("", "ExamplePlayer", 0x62D7F1, false)))
    check(cyan.string == "ExamplePlayer" && cyan.style.color?.value == 0x62D7F1)
    val bold = parseCosmeticText(requireNotNull(cosmeticDraftName("  ", "ExamplePlayer", null, true)))
    check(bold.string == "ExamplePlayer" && bold.style.isBold && bold.style.color == null)
    val custom = parseCosmeticText(requireNotNull(cosmeticDraftName(" Other Name ", "ExamplePlayer", 0, true)))
    check(custom.string == "Other Name" && custom.style.color?.value == 0 && custom.style.isBold)
    check(runCatching { cosmeticDraftName("@everyone", "ExamplePlayer", 0, false) }.isFailure)

    val initial = CosmeticProfile(UUID(0, 3), null, 1f, 0, 0)
    val saved = initial.copy(name = cyan.string, nameStyle = cyan, scale = 1.4f, scaleX = .8f, scaleY = 1.4f, scaleZ = 1.7f, nametagScale = 1.2f, revision = 8, updatedAt = 1000)
    // A late first refresh keeps a newly selected cape while adopting the actual saved name/body/tag.
    val localCape = initial.copy(capePreset = "forest")
    val merged = mergeCosmeticDraft(saved, localCape, setOf(CosmeticDraftField.CAPE))
    check(merged.capePreset == "forest" && merged.nameStyle == cyan && merged.scaleY == 1.4f && merged.nametagScale == 1.2f)
    check(merged.revision == 8L && saved.capePreset == null) // Preview does not mutate public state or lose CAS revision.
    val widthEdit = mergeCosmeticDraft(saved, initial.copy(scaleX = 2f), setOf(CosmeticDraftField.WIDTH))
    check(widthEdit.scaleX == 2f && widthEdit.scaleY == 1.4f && widthEdit.scaleZ == 1.7f && widthEdit.scale == 1.4f)
    // Each untouched axis comes from the server; editing one axis cannot reset the other two.
    val depthEdit = mergeCosmeticDraft(saved, initial.copy(scaleZ = 2.5f), setOf(CosmeticDraftField.DEPTH))
    check(depthEdit.scaleX == .8f && depthEdit.scaleY == 1.4f && depthEdit.scaleZ == 2.5f)
    val resetBody = mergeCosmeticDraft(saved, initial, setOf(CosmeticDraftField.WIDTH, CosmeticDraftField.HEIGHT, CosmeticDraftField.DEPTH))
    check(resetBody.scale == 1f && resetBody.scaleX == 1f && resetBody.scaleY == 1f && resetBody.scaleZ == 1f)
    // A save response cannot overwrite a name edited after the submitted snapshot.
    val later = merged.copy(name = custom.string, nameStyle = custom, scale = 2f, scaleY = 2f)
    val response = saved.copy(capePreset = "forest", revision = 9, updatedAt = 2000)
    val afterSave = mergeCosmeticDraft(response, later, setOf(CosmeticDraftField.NAME, CosmeticDraftField.HEIGHT))
    check(afterSave.nameStyle == custom && afterSave.scaleY == 2f && afterSave.revision == 9L)
    check(cosmeticFieldMatches(response, later, CosmeticDraftField.CAPE))
    check(!cosmeticFieldMatches(response, later, CosmeticDraftField.NAME))
    check(mergeCosmeticDraft(response, later, emptySet()) == response) // Explicit discard adopts saved state.
    check(runCatching { mergeCosmeticDraft(response.copy(uuid = UUID(0, 4)), later, emptySet()) }.isFailure)

    // Real screen rebuilds (resize/F11/theme) must not restore the equipped state over an edited draft.
    var draftOwner: UUID? = null
    var rebuiltDraft = initialCosmeticDraft(draftOwner, initial.uuid, saved)!!
    draftOwner = initial.uuid
    check(rebuiltDraft === saved)
    rebuiltDraft = rebuiltDraft.copy(name = custom.string, nameStyle = custom, scaleX = 2.2f)
    val submittedBeforeResize = rebuiltDraft
    val editedBeforeResize = setOf(CosmeticDraftField.NAME, CosmeticDraftField.WIDTH)
    repeat(3) {
        initialCosmeticDraft(draftOwner, initial.uuid, saved)?.let { rebuiltDraft = it }
        check(rebuiltDraft === submittedBeforeResize)
    }
    // A subsequent accepted local save still merges against the surviving captured draft.
    val completedLocalSave = saved.copy(capePreset = "forest", revision = 10)
    rebuiltDraft = mergeCosmeticDraft(completedLocalSave, rebuiltDraft, editedBeforeResize)
    check(rebuiltDraft.nameStyle == custom && rebuiltDraft.scaleX == 2.2f && rebuiltDraft.capePreset == "forest")
    val nextOwner = UUID(0, 4)
    val resetOwner = initialCosmeticDraft(draftOwner, nextOwner, saved)!!
    check(resetOwner.uuid == nextOwner && resetOwner.nameStyle == null && resetOwner.scaleX == 1f && resetOwner.capePreset == null)
    val ownNextProfile = resetOwner.copy(capePreset = "sunset", revision = 1, updatedAt = 1000)
    check(initialCosmeticDraft(draftOwner, nextOwner, ownNextProfile) === ownNextProfile)
    check(initialCosmeticDraft(null, initial.uuid, null) == initial) // Loading later uses the existing guarded merge path.

    val state = AvatarRenderState().apply { isInvisible = true; showCape = false; capeFlap = 1f; capeLean = 2f; capeLean2 = 3f }
    prepareCosmeticPreview(state)
    check(!state.isInvisible && state.showCape && state.chestEquipment.isEmpty)
    check(state.capeFlap == 0f && state.capeLean == 0f && state.capeLean2 == 0f)
}

private fun cosmeticWorkspaceChecks() {
    bootstrapHeadlessOwo()
    for (width in listOf(191, 320, 499, 500, 680, 960)) {
        val preview = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            child(UIContainers.verticalFlow(Sizing.fill(), Sizing.fixed(180)))
        }
        val controls = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            child(UIContainers.verticalFlow(Sizing.fill(), Sizing.fixed(500)))
        }
        val workspace = cosmeticsWorkspace(width, preview, controls).apply { sizing(Sizing.fixed(width), Sizing.fixed(240)) }
        workspace.inflate(Size.of(width, 240)); workspace.mount(null, 10, 10)
        check(controls.width() >= minOf(width, 300)) { "Cosmetics controls became too narrow at width $width" }
        check(preview.x() >= workspace.x() && controls.x() >= workspace.x() &&
            preview.x() + preview.width() <= workspace.x() + width && controls.x() + controls.width() <= workspace.x() + width) {
            "Cosmetics workspace escaped its viewport at width $width"
        }
        check(preview.x() + preview.width() <= controls.x() || preview.y() + preview.height() <= controls.y()) {
            "Cosmetics preview overlaps its controls at width $width"
        }
    }
}
