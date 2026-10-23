package me.mycellium.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only hovered slot, so Price History can graph the item under the cursor. */
@Mixin(AbstractContainerScreen.class)
public interface BazaarAssistContainerAccessor {
    @Accessor("hoveredSlot") Slot skymyce$getHoveredSlot();
}
