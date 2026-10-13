package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import me.mycellium.mixin.SecondaryEntityDispatcherAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelType;

import java.util.Map;

/** Dispatcher-local renderers: optimization mods may cache the main dispatcher's renderers globally. */
final class SecondaryEntityRenderDispatcher extends EntityRenderDispatcher {
    private final Minecraft client;
    private final SecondaryEntityDispatcherAccessor assets;
    private Map<EntityType<?>, EntityRenderer<?, ?>> localRenderers = Map.of();
    private Map<PlayerModelType, AvatarRenderer<AbstractClientPlayer>> localPlayers = Map.of();
    private Map<PlayerModelType, AvatarRenderer<ClientMannequin>> localMannequins = Map.of();

    SecondaryEntityRenderDispatcher(Minecraft client, SecondaryEntityDispatcherAccessor assets) {
        super(client, client.getTextureManager(), assets.skyMyce$blockModels(), assets.skyMyce$itemModels(),
            assets.skyMyce$maps(), client.getAtlasManager(), client.font, client.options, assets.skyMyce$models(),
            assets.skyMyce$equipment(), assets.skyMyce$skins());
        this.client = client;
        this.assets = assets;
    }

    @Override public void onResourceManagerReload(ResourceManager resources) {
        var context = new EntityRendererProvider.Context(this, assets.skyMyce$blockModels(), assets.skyMyce$itemModels(),
            assets.skyMyce$maps(), resources, assets.skyMyce$models().get(), assets.skyMyce$equipment(),
            client.getAtlasManager(), client.font, assets.skyMyce$skins());
        // Calling the base reload publishes this camera's renderers into BadOptimizations' main-view caches.
        localRenderers = EntityRenderers.createEntityRenderers(context);
        localPlayers = EntityRenderers.createAvatarRenderers(context);
        localMannequins = EntityRenderers.createAvatarRenderers(context);
    }

    @Override @SuppressWarnings("unchecked")
    public <T extends Entity> EntityRenderer<? super T, ?> getRenderer(T entity) {
        if (entity instanceof AbstractClientPlayer player) return (EntityRenderer<? super T, ?>) getPlayerRenderer(player);
        if (entity instanceof ClientMannequin mannequin)
            return (EntityRenderer<? super T, ?>) avatarRenderer(localMannequins, mannequin.getSkin().model());
        return (EntityRenderer<? super T, ?>) localRenderers.get(entity.getType());
    }

    @Override public AvatarRenderer<AbstractClientPlayer> getPlayerRenderer(AbstractClientPlayer player) {
        return avatarRenderer(localPlayers, player.getSkin().model());
    }

    @Override @SuppressWarnings("unchecked")
    public <S extends EntityRenderState> EntityRenderer<?, ? super S> getRenderer(S state) {
        // Vanilla uses its player map for every extracted AvatarRenderState, including mannequins.
        return (EntityRenderer<?, ? super S>) (state instanceof AvatarRenderState avatar
            ? avatarRenderer(localPlayers, avatar.skin.model()) : localRenderers.get(state.entityType));
    }

    static <R> R avatarRenderer(Map<PlayerModelType, R> renderers, PlayerModelType model) {
        R renderer = renderers.get(model);
        return renderer != null ? renderer : renderers.get(PlayerModelType.WIDE);
    }
}
