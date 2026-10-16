package me.mycellium.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only menu bounds and the hovered slot, so the assistant panel can sit beside a menu and profit can rise from a clicked slot. */
@Mixin(AbstractContainerScreen.class)
public interface BazaarAssistContainerAccessor {
    @Accessor("leftPos") int skymyce$getLeftPos();
    @Accessor("topPos") int skymyce$getTopPos();
    @Accessor("imageWidth") int skymyce$getImageWidth();
    @Accessor("imageHeight") int skymyce$getImageHeight();
    @Accessor("hoveredSlot") Slot skymyce$getHoveredSlot();
}
