package me.mycellium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.mycellium.skymyce.features.social.CosmeticNames;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** Only Font's display inputs change. Received chat/components, command strings and item NBT remain original. */
@Mixin(Font.class)
public abstract class CosmeticFontMixin {
    @ModifyVariable(method = {"width(Lnet/minecraft/util/FormattedCharSequence;)I",
        "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
        "drawInBatch8xOutline", "prepare8xTextOutline"}, at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence skymyce$sequence(FormattedCharSequence text) { return CosmeticNames.display(text); }

    @ModifyVariable(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("HEAD"), argsOnly = true)
    private FormattedText skymyce$width(FormattedText text) { return CosmeticNames.display(text); }

    @Inject(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void skymyce$stringWidth(String text, CallbackInfoReturnable<Integer> cir) {
        if (text != null && CosmeticNames.active()) cir.setReturnValue(((Font)(Object)this).width(CosmeticNames.display(text)));
    }

    @Inject(method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;", at = @At("HEAD"), cancellable = true)
    private void skymyce$stringText(String text, float x, float y, int color, boolean shadow, int background, CallbackInfoReturnable<Font.PreparedText> cir) {
        if (CosmeticNames.active()) cir.setReturnValue(((Font)(Object)this).prepareText(CosmeticNames.display(text), x, y, color, shadow, false, background));
    }

    @WrapMethod(method = "split")
    private List<FormattedCharSequence> skymyce$wrap(FormattedText text, int width, Operation<List<FormattedCharSequence>> original) {
        if (!CosmeticNames.active()) return original.call(text, width);
        // Layout uses displayed widths once. Protect its output from replacing another username inside a cosmetic name.
        FormattedText displayed = CosmeticNames.display(text);
        CosmeticNames.enterOriginal();
        try { return original.call(displayed, width).stream().map(CosmeticNames::original).toList(); }
        finally { CosmeticNames.exitOriginal(); }
    }

    @ModifyVariable(method = "wordWrapHeight", at = @At("HEAD"), argsOnly = true)
    private FormattedText skymyce$wrappedHeight(FormattedText text) { return CosmeticNames.display(text); }
}
