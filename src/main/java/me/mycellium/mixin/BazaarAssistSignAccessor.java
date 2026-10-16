package me.mycellium.mixin;

import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the sign being edited and, for the opt-in Auto Sign, type into its first line through the normal text field. */
@Mixin(AbstractSignEditScreen.class)
public interface BazaarAssistSignAccessor {
    @Accessor("messages") String[] skymyce$getMessages();
    @Accessor("line") int skymyce$getLine();
    @Accessor("signField") TextFieldHelper skymyce$getSignField();
}
