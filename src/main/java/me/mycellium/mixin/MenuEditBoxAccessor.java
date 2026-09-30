package me.mycellium.mixin;

import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Selection remains owned by EditBox; custom paint reads its other endpoint. */
@Mixin(EditBox.class)
public interface MenuEditBoxAccessor {
    @Accessor("highlightPos")
    int skymyce$getHighlightPosition();
}
