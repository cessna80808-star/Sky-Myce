package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import me.mycellium.mixin.SecondaryEntityDispatcherAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Map;

/** Block-entity caches also belong to one view, even when a mod replaces vanilla's lookup. */
final class SecondaryBlockEntityRenderDispatcher extends BlockEntityRenderDispatcher {
    private final Minecraft client;
    private final SecondaryEntityDispatcherAccessor assets;
    private final EntityRenderDispatcher entities;
    private final SpriteGetter sprites;
    private Map<BlockEntityType<?>, BlockEntityRenderer<?, ?>> localRenderers = Map.of();

    SecondaryBlockEntityRenderDispatcher(Minecraft client, SecondaryEntityDispatcherAccessor assets,
        EntityRenderDispatcher entities, SpriteGetter sprites) {
        super(client.font, assets.skyMyce$models(), assets.skyMyce$blockModels(), assets.skyMyce$itemModels(),
            entities, sprites, assets.skyMyce$skins());
        this.client = client;
        this.assets = assets;
        this.entities = entities;
        this.sprites = sprites;
    }

    @Override public void onResourceManagerReload(ResourceManager resources) {
        var context = new BlockEntityRendererProvider.Context(this, assets.skyMyce$blockModels(), assets.skyMyce$itemModels(),
            entities, assets.skyMyce$models().get(), client.font, sprites, assets.skyMyce$skins());
        // Bypass singleton-oriented reload callbacks, while retaining the public registered-provider factory.
        localRenderers = BlockEntityRenderers.createEntityRenderers(context);
    }

    @Override @SuppressWarnings("unchecked")
    public <E extends BlockEntity, S extends BlockEntityRenderState> BlockEntityRenderer<E, S> getRenderer(E entity) {
        return (BlockEntityRenderer<E, S>) localRenderers.get(entity.getType());
    }

    @Override @SuppressWarnings("unchecked")
    public <E extends BlockEntity, S extends BlockEntityRenderState> BlockEntityRenderer<E, S> getRenderer(S state) {
        return (BlockEntityRenderer<E, S>) localRenderers.get(state.blockEntityType);
    }
}
