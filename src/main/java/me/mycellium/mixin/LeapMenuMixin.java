package me.mycellium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.mycellium.skymyce.features.instances.dungeons.leap.LeapMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Screen.class)
public abstract class LeapMenuMixin {
    @WrapOperation(method = "extractRenderStateWithTooltipAndSubtitles", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void skyMyce$skipLeapInventoryBackground(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, Operation<Void> original) {
        if (!LeapMenu.active(screen)) original.call(screen, graphics, mouseX, mouseY, partialTicks);
    }

    @WrapOperation(method = "extractRenderStateWithTooltipAndSubtitles", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void skyMyce$extractLeapMenu(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, Operation<Void> original) {
        if (!LeapMenu.extract(screen, graphics, mouseX, mouseY, partialTicks)) original.call(screen, graphics, mouseX, mouseY, partialTicks);
    }
}
