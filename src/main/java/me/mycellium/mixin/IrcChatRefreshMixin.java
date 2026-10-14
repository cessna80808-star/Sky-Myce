package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.friends.IrcChatRendering;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

/** Other chat hooks may mutate history while an IRC redraw rebuilds the visible rows. */
@Mixin(ChatComponent.class)
public class IrcChatRefreshMixin {
    @ModifyArg(method = "refreshTrimmedMessages", at = @At(value = "INVOKE",
        target = "Lcom/google/common/collect/Lists;reverse(Ljava/util/List;)Ljava/util/List;"), index = 0)
    private List<GuiMessage> skymyce$stableIrcRefresh(List<GuiMessage> messages) {
        return IrcChatRendering.snapshotForRefresh(messages);
    }
}
