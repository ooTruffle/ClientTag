package com.ootruffle.clienttag.legacy.compat.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.ootruffle.clienttag.legacy.render.IconGlyphs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Argentum draws text from its own glyph atlas, built from {@code minecraft:default}
 * (assets/minecraft/font/default.json) rather than through the FontRenderer, so the
 * {@link IconGlyphs} characters are looked up there as their icon's white glyph, and drawn tinted
 * with the icon's color instead of the text's - and, like on the vanilla path, without a shadow.
 * The same approach PolyPlus takes for its badge and emoji.
 * <p>
 * Each icon glyph advances 10px, matching vanilla (see IconArt#main), so widths need no help.
 * Optional: only applied with Argentum installed, and skipped if its code has changed shape.
 */
@Pseudo
@Mixin(targets = "dev.rdh.argentum.impl.render.gui.TextBatcher", remap = false)
public abstract class TextBatcherMixin {

    /** The tint of the glyph being laid out, or -1 for none (an ordinary character). */
    @Unique private int clienttag$iconColor = -1;
    /** Whether the vertices being written are an icon's, to be drawn in {@link #clienttag$iconColor}. */
    @Unique private boolean clienttag$tint;

    @ModifyArg(
            method = "layout",
            at = @At(value = "INVOKE", target = "Ldev/rdh/argentum/impl/render/gui/font/FontSet;glyph(IZ)Ldev/rdh/argentum/impl/render/gui/font/Glyph;"),
            index = 0,
            require = 0
    )
    private int clienttag$layoutIcon(int codepoint) {
        final int fontChar = IconGlyphs.fontChar(codepoint);
        clienttag$iconColor = fontChar != codepoint ? IconGlyphs.color((char) codepoint) : -1;
        clienttag$tint = false;
        return fontChar;
    }

    @ModifyArg(
            method = {"stringWidth", "charWidth"},
            at = @At(value = "INVOKE", target = "Ldev/rdh/argentum/impl/render/gui/font/FontSet;glyph(IZ)Ldev/rdh/argentum/impl/render/gui/font/Glyph;"),
            index = 0,
            require = 0
    )
    private int clienttag$measureIcon(int codepoint) {
        return IconGlyphs.fontChar(codepoint);
    }

    @ModifyExpressionValue(
            method = "layout",
            at = @At(value = "INVOKE", target = "Ldev/rdh/argentum/impl/render/gui/font/Glyph;visible()Z"),
            require = 0
    )
    private boolean clienttag$skipShadow(boolean visible, @Local(argsOnly = true, ordinal = 0) boolean shadow) {
        if (clienttag$iconColor == -1) {
            return visible;
        }
        clienttag$tint = !shadow;
        return visible && !shadow;
    }

    // Underline and strikethrough under an icon keep the text's color.
    @ModifyArg(method = "layout", at = @At(value = "INVOKE", target = "Ldev/rdh/argentum/impl/render/gui/TextBatcher;decoration"), index = 0, require = 0)
    private float clienttag$untintDecoration(float x) {
        clienttag$tint = false;
        return x;
    }

    // Text ending in an icon mustn't leave the tint on for whatever is drawn next.
    @Inject(method = "layout", at = @At("RETURN"), require = 0)
    private void clienttag$endTint(CallbackInfoReturnable<Float> cir) {
        clienttag$iconColor = -1;
        clienttag$tint = false;
    }

    @ModifyArg(method = "vertex", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WorldRenderer;color(FFFF)Lnet/minecraft/client/renderer/WorldRenderer;", remap = true), index = 0, require = 0)
    private float clienttag$red(float red) {
        return clienttag$tint ? (clienttag$iconColor >> 16 & 0xFF) / 255.0F : red;
    }

    @ModifyArg(method = "vertex", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WorldRenderer;color(FFFF)Lnet/minecraft/client/renderer/WorldRenderer;", remap = true), index = 1, require = 0)
    private float clienttag$green(float green) {
        return clienttag$tint ? (clienttag$iconColor >> 8 & 0xFF) / 255.0F : green;
    }

    @ModifyArg(method = "vertex", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WorldRenderer;color(FFFF)Lnet/minecraft/client/renderer/WorldRenderer;", remap = true), index = 2, require = 0)
    private float clienttag$blue(float blue) {
        return clienttag$tint ? (clienttag$iconColor & 0xFF) / 255.0F : blue;
    }

}
