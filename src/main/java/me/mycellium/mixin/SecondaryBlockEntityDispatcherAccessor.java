package me.mycellium.mixin;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockEntityRenderDispatcher.class)
public interface SecondaryBlockEntityDispatcherAccessor {
    @Accessor("sprites") SpriteGetter skyMyce$sprites();
}
