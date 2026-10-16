package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SecondaryCameraSodiumMixin {
    @Redirect(method = "instanceNullable", at = @At(value = "FIELD", target =
        "Lnet/minecraft/client/Minecraft;levelRenderer:Lnet/minecraft/client/renderer/LevelRenderer;"), require = 0)
    private static LevelRenderer skyMyce$secondaryVisibility(Minecraft client) {
        return SecondaryWorldCamera.effectiveLevelRenderer(client.levelRenderer);
    }
}
