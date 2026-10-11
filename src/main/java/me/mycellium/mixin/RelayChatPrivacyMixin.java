package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.friends.RelayMessages;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Relay chat suppresses the synchronous log line; signed player and ordinary system chat are unchanged. */
@Mixin(ChatComponent.class)
public class RelayChatPrivacyMixin {
    @Inject(method = "logChatMessage", at = @At("HEAD"), cancellable = true)
    private void skymyce$privateRelayMessage(GuiMessage message, CallbackInfo ci) {
        if (RelayMessages.suppressChatLog()) ci.cancel();
    }
}
