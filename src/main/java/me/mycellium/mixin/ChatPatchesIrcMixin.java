package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.friends.IrcChatRendering;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Distinct IRC attachments can have identical labels but different native click actions. */
@Pseudo
@Mixin(targets = "obro1961.chatpatches.util.ChatUtil", remap = false)
public class ChatPatchesIrcMixin {
    @Inject(method = "tryCondenseDupes(Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/Component;",
        at = @At("HEAD"), cancellable = true, require = 0)
    private static void skymyce$keepIrcMessages(Component message, CallbackInfoReturnable<Component> cir) {
        if (IrcChatRendering.addingMessage()) cir.setReturnValue(message);
    }
}
