package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object GlobalChatConfig : CategoryKt("Global SkyMyce Chat") {
    var enabled by boolean(false) {
        name = TranslatableValue.literal("Global chat")
        description = TranslatableValue.literal("Receive the shared Minecraft and Discord global channel. /sm irc selects it; /sm chat returns to normal chat. Separate from in-game Party IRC.")
    }
    var partyEnabled by boolean(false) {
        name = TranslatableValue.literal("Party IRC")
        description = TranslatableValue.literal("Minecraft-only chat with opted-in Sky-Myce users reporting the same complete current Hypixel party roster. Never forwarded to Discord. /sm party selects it; an incomplete roster stops routing.")
    }
    val mediaPreviews by boolean(true) {
        name = TranslatableValue.literal("Chat thumbnails")
        description = TranslatableValue.literal("Show image and GIF previews inside chat. Click a preview for the media viewer. Downloads use a bounded memory cache; nothing is saved to disk.")
    }
    val previewSize by int(100) {
        name = TranslatableValue.literal("Chat preview size (%)")
        description = TranslatableValue.literal("Resize image and GIF previews in chat. 100% is the normal size; previews always fit the chat width and show the whole image.")
        range = 50..200
        slider = true
    }
    val animateGifs by boolean(true) {
        name = TranslatableValue.literal("Animate GIFs")
        description = TranslatableValue.literal("Play GIFs in chat previews and the media viewer. The viewer also has a pause button.")
    }
    val mentionHighlight by boolean(true) {
        name = TranslatableValue.literal("Highlight mentions")
        description = TranslatableValue.literal("Highlight new messages that mention your linked account. Editing or reacting to a message does not repeat its notification.")
    }
    val mentionSounds by boolean(true) {
        name = TranslatableValue.literal("Mention sound")
        description = TranslatableValue.literal("Play one subtle ping for a new party or global message that mentions your account.")
    }
    val emojiInNormalChat by boolean(true) {
        name = TranslatableValue.literal("Emoji in Normal Chat")
        description = TranslatableValue.literal("Use the emoji picker and :shortcode: suggestions in normal chat too. Emoji sent to Hypixel become readable :names: (as many as fit in 256 characters), and :names: in received chat show as emoji for you. Sky-Myce IRC always sends real emoji.")
    }
}
