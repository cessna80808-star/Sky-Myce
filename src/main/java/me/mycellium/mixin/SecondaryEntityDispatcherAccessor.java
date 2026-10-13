package me.mycellium.mixin;

import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

/** Immutable asset dependencies; renderer instances and camera state are never shared. */
@Mixin(EntityRenderDispatcher.class)
public interface SecondaryEntityDispatcherAccessor {
    @Accessor("blockModelResolver") BlockModelResolver skyMyce$blockModels();
    @Accessor("itemModelResolver") ItemModelResolver skyMyce$itemModels();
    @Accessor("mapRenderer") MapRenderer skyMyce$maps();
    @Accessor("entityModels") Supplier<EntityModelSet> skyMyce$models();
    @Accessor("equipmentAssets") EquipmentAssetManager skyMyce$equipment();
    @Accessor("playerSkinRenderCache") PlayerSkinRenderCache skyMyce$skins();
}
