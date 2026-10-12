package me.mycellium.skymyce.features.social

import me.mycellium.skymyce.community.CommunityFailure

internal fun capePreviewChecks(profile: CosmeticProfile, asset: CapeAsset, png: ByteArray) {
    val uuid = profile.uuid.toString().replace("-", "")
    val owner = StudioOwner("account-a", uuid, "s".repeat(43), 7)
    val draft = profile.copy(capePreset = null, capeAsset = asset)
    val request = requireNotNull(capePreviewRequest(draft, owner, 4))
    check(request.path == "/community/cosmetics/preview/${asset.id}?uuid=$uuid")
    check("/assets/" !in request.path) // Unsaved previews cannot use or populate the public active-only path.
    check(capePreviewRequest(draft, null, 4) == null)
    check(capePreviewRequest(draft, owner.copy(uuid = "d".repeat(32)), 4) == null)
    check(capePreviewRequest(draft.copy(capePreset = "none"), owner, 4) == null)
    check(capePreviewRequest(draft.copy(capeAsset = null), owner, 4) == null)
    check(request.current(owner, uuid, true, 4))
    for (changed in listOf(null, owner.copy(accountId = "account-b"), owner.copy(uuid = "d".repeat(32)),
        owner.copy(token = "t".repeat(43)), owner.copy(generation = 8))) {
        check(!request.current(changed, uuid, true, 4))
        check(capePreviewRequest(draft, changed, 4) != request) // The private cache key pins the whole captured authority.
    }
    check(!request.current(owner, "d".repeat(32), true, 4))
    check(!request.current(owner, uuid, false, 4))
    check(!request.current(owner, uuid, true, 5)) // Screen close/reopen invalidates old private work.

    var authority: StudioOwner? = owner
    var epoch = 4L
    var transmissions = 0
    fun current() = request.current(authority, uuid, true, epoch)
    val fetched = loadCapePreview(request, ::current) { path, token ->
        transmissions++; check(path == request.path && token == owner.token); png
    }
    check(fetched.contentEquals(png) && transmissions == 1 && profile.capeAsset == null && draft.revision == profile.revision)
    authority = owner.copy(generation = 8)
    val before = runCatching { loadCapePreview(request, ::current) { _, _ -> transmissions++; png } }.exceptionOrNull()
    check((before as? CommunityFailure)?.code == "session_identity_changed" && transmissions == 1)
    authority = owner
    val after = runCatching { loadCapePreview(request, ::current) { _, _ -> transmissions++; authority = null; png } }.exceptionOrNull()
    check((after as? CommunityFailure)?.code == "session_identity_changed" && transmissions == 2)
    authority = owner
    check(runCatching { loadCapePreview(request, ::current) { _, _ -> epoch++; png } }.isFailure)
    epoch = 4
    check(runCatching { loadCapePreview(request, ::current) { _, _ -> png.copyOf().apply { this[lastIndex] = 0 } } }.isFailure)
    check(runCatching { loadCapePreview(request.copy(asset = asset.copy(sha256 = "0".repeat(64))), ::current) { _, _ -> png } }.isFailure)
    println("Cape preview checks passed: private unsaved upload path, exact session/MC/screen pins, pre/post-HTTP authority and canonical PNG integrity")
}
