package me.mycellium.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.List;

/** Stable tags let IRC update its own bounded vanilla history without touching signed player chat. */
@Mixin(ChatComponent.class)
public interface IrcChatAccessor {
    @Accessor("allMessages") List<GuiMessage> skymyce$getMessages();
    @Accessor("chatScrollbarPos") int skymyce$getScroll();
    @Accessor("chatScrollbarPos") void skymyce$setScroll(int value);
    @Invoker("addMessage") void skymyce$addMessage(Component text, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag);
    @Invoker("refreshTrimmedMessages") void skymyce$refreshMessages();
}
