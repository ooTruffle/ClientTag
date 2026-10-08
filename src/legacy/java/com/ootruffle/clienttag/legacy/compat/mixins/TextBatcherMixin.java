package com.ootruffle.clienttag.legacy.compat.mixins;

import com.ootruffle.clienttag.legacy.render.IconGlyphs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Argentum caches text as prebuilt glyph meshes, which would draw {@link IconGlyphs}
 * characters as missing glyphs. Strings containing them are left to the FontRenderer, which
 * draws the icons (see FontRendererMixin) - the same opt-out PolyPlus uses for its badge.
 * <p>
 * Optional: only applied with Argentum installed, and skipped if its code has changed shape.
 */
@Pseudo
@Mixin(targets = "dev.rdh.argentum.impl.render.gui.TextBatcher", remap = false)
public abstract class TextBatcherMixin {

    @Inject(method = "cacheable", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void clienttag$skipIcons(String text, CallbackInfoReturnable<Boolean> cir) {
        if (text != null && IconGlyphs.containsIcon(text)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "charWidth", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void clienttag$iconWidth(char c, boolean unicode, byte[] glyphWidths, CallbackInfoReturnable<Float> cir) {
        if (IconGlyphs.isIcon(c)) {
            cir.setReturnValue((float) IconGlyphs.ADVANCE);
        }
    }

}
