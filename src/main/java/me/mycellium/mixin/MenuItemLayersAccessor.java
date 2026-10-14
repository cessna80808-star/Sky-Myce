package me.mycellium.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The canvas reads Minecraft's selected layers instead of reimplementing item-model predicates. */
@Mixin(ItemStackRenderState.class)
public interface MenuItemLayersAccessor {
    @Accessor("activeLayerCount") int skymyce$activeLayerCount();
    @Accessor("layers") ItemStackRenderState.LayerRenderState[] skymyce$layers();
}
