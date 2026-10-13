package me.mycellium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.mycellium.skymyce.features.instances.dungeons.friends.IrcEmojiInput;
import me.mycellium.skymyce.features.instances.dungeons.friends.IrcEmojiInputLayout;
import me.mycellium.skymyce.features.social.CosmeticNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.gui.components.TextCursorUtils;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Input stays real editable text; the chat field paints bundled emoji with matching raw-index metrics. */
@Mixin(EditBox.class)
public class CosmeticEditBoxMixin {
    @Shadow @Final private static WidgetSprites SPRITES;
    @Shadow @Final private Font font;
    @Shadow private String value;
    @Shadow private int displayPos;
    @Shadow private int cursorPos;
    @Shadow private int highlightPos;
    @Shadow private int textX;
    @Shadow private int textY;
    @Shadow private int textColor;
    @Shadow private int textColorUneditable;
    @Shadow private boolean isEditable;
    @Shadow private boolean textShadow;
    @Shadow private boolean invertHighlightedTextColor;
    @Shadow private String suggestion;
    @Shadow private long focusedTime;
    @Shadow private IMEPreeditOverlay preeditOverlay;
    @Shadow private int getMaxLength() { throw new AssertionError(); }
    @Shadow private FormattedCharSequence applyFormat(String text, int offset) { throw new AssertionError(); }

    @Unique
    private boolean skymyce$emojiInput() {
        return Minecraft.getInstance().screen instanceof ChatScreen screen &&
            ((IrcChatScreenAccessor) screen).skymyce$getInput() == (Object) this && IrcEmojiInput.layout(value).getHasEmoji();
    }

    @WrapMethod(method = "updateTextPosition")
    private void skymyce$realPosition(Operation<Void> original) {
        CosmeticNames.enterOriginal();
        try { original.call(); } finally { CosmeticNames.exitOriginal(); }
    }
    @WrapMethod(method = "getScreenX")
    private int skymyce$realCursor(int index, Operation<Integer> original) {
        CosmeticNames.enterOriginal();
        try {
            if (skymyce$emojiInput()) {
                IrcEmojiInputLayout layout = IrcEmojiInput.layout(value);
                return textX + layout.position(index, font) - layout.position(displayPos, font);
            }
            return original.call(index);
        } finally { CosmeticNames.exitOriginal(); }
    }

    @WrapMethod(method = "findClickedPositionInText")
    private int skymyce$emojiClick(MouseButtonEvent event, Operation<Integer> original) {
        if (!skymyce$emojiInput()) return original.call(event);
        CosmeticNames.enterOriginal();
        try {
            IrcEmojiInputLayout layout = IrcEmojiInput.layout(value);
            int offset = Math.clamp((int) Math.floor(event.x()) - textX, 0, ((EditBox) (Object) this).getInnerWidth());
            return layout.index(offset + layout.position(displayPos, font), font);
        } finally { CosmeticNames.exitOriginal(); }
    }

    @WrapMethod(method = "scrollTo")
    private void skymyce$emojiScroll(int index, Operation<Void> original) {
        if (!skymyce$emojiInput()) { original.call(index); return; }
        CosmeticNames.enterOriginal();
        try { skymyce$scrollEmojiTo(index, IrcEmojiInput.layout(value)); }
        finally { CosmeticNames.exitOriginal(); }
    }

    @Unique
    private void skymyce$scrollEmojiTo(int index, IrcEmojiInputLayout layout) {
        int safe = Math.clamp(index, 0, value.length());
        int caretWidth = safe == value.length() && value.length() < getMaxLength() ? font.width("_") + 1 : 1;
        int available = Math.max(1, ((EditBox) (Object) this).getInnerWidth() - caretWidth);
        displayPos = layout.scroll(safe, displayPos, available, font);
    }
    @WrapMethod(method = "extractWidgetRenderState")
    private void skymyce$realInput(GuiGraphicsExtractor graphics, int x, int y, float delta, Operation<Void> original) {
        CosmeticNames.enterOriginal();
        try {
            if (skymyce$emojiInput()) skymyce$paintEmojiInput(graphics);
            else original.call(graphics, x, y, delta);
        } finally { CosmeticNames.exitOriginal(); }
    }

    @Unique
    private void skymyce$paintEmojiInput(GuiGraphicsExtractor graphics) {
        EditBox box = (EditBox) (Object) this;
        if (!box.isVisible()) return;
        if (box.isBordered()) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITES.get(box.isActive(), box.isFocused()),
            box.getX(), box.getY(), box.getWidth(), box.getHeight());
        IrcEmojiInputLayout layout = IrcEmojiInput.layout(value);
        skymyce$scrollEmojiTo(cursorPos, layout);
        int origin = textX - layout.position(displayPos, font);
        int cursorX = origin + layout.position(cursorPos, font);
        int color = isEditable ? textColor : textColorUneditable;
        graphics.enableScissor(textX, textY - 1, textX + box.getInnerWidth(), textY + font.lineHeight + 1);
        graphics.text(font, layout.display(applyFormat(value, 0)), origin, textY, color, textShadow);
        boolean insert = cursorPos < value.length() || value.length() >= getMaxLength();
        if (!insert && suggestion != null) graphics.text(font, suggestion, cursorX, textY, -8355712, textShadow);
        if (cursorPos != highlightPos) {
            int highlightX = origin + layout.position(highlightPos, font);
            graphics.textHighlight(cursorX, textY - 1, highlightX, textY + font.lineHeight + 1, invertHighlightedTextColor);
        }
        if (box.isFocused() && TextCursorUtils.isCursorVisible(Util.getMillis() - focusedTime)) {
            if (insert) TextCursorUtils.extractInsertCursor(graphics, cursorX, textY, color, font.lineHeight + 1);
            else TextCursorUtils.extractAppendCursor(graphics, font, cursorX + 1, textY, color, textShadow);
        }
        graphics.disableScissor();
        if (box.isHovered()) graphics.requestCursor(isEditable ? CursorTypes.IBEAM : CursorTypes.NOT_ALLOWED);
        if (preeditOverlay != null) {
            preeditOverlay.updateInputPosition(cursorX, textY);
            graphics.setPreeditOverlay(preeditOverlay);
        }
    }
}
