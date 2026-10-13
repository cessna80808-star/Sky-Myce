package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.friends.RelayMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture selected IRC before other chat mods rewrite messages intended for Minecraft's server. */
@Mixin(value = ChatScreen.class, priority = 800)
public class IrcComposerMixin {
    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void skymyce$selectedIrc(String text, boolean addToHistory, CallbackInfo ci) {
        if (!RelayMessages.selectedIrcComposer()) return;
        String normalized = ((ChatScreen) (Object) this).normalizeChatMessage(text);
        if (RelayMessages.allowComposerChat(normalized)) return;
        ci.cancel();
        if (addToHistory && !normalized.isEmpty()) Minecraft.getInstance().gui.getChat().addRecentChat(normalized);
    }
}
