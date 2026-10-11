package me.mycellium.mixin;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla command suggestions keep priority over IRC previews and their native text click areas. */
@Mixin(ChatScreen.class)
public interface IrcChatScreenAccessor {
    @Accessor("commandSuggestions") CommandSuggestions skymyce$getSuggestions();
}
