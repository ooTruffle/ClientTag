package com.ootruffle.clienttag.legacy.mixins;

import com.ootruffle.clienttag.legacy.render.IconGlyphs;
import net.minecraft.client.gui.FontRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draws {@link IconGlyphs} characters as their icons, and measures them as such. */
@Mixin(FontRenderer.class)
public abstract class FontRendererMixin {

    @Shadow private float posX;
    @Shadow private float posY;
    @Shadow private float alpha;

    @Unique private boolean clienttag$shadowPass;

    @Inject(method = "renderStringAtPos", at = @At("HEAD"))
    private void clienttag$trackShadow(String text, boolean shadow, CallbackInfo ci) {
        clienttag$shadowPass = shadow;
    }

    @Inject(method = "renderChar", at = @At("HEAD"), cancellable = true)
    private void clienttag$drawIcon(char c, boolean italic, CallbackInfoReturnable<Float> cir) {
        if (IconGlyphs.isIcon(c)) {
            // Icons cast no shadow.
            if (!clienttag$shadowPass) {
                IconGlyphs.draw(c, posX, posY, alpha);
            }
            cir.setReturnValue((float) IconGlyphs.ADVANCE);
        }
    }

    @Inject(method = "getCharWidth", at = @At("HEAD"), cancellable = true)
    private void clienttag$iconWidth(char c, CallbackInfoReturnable<Integer> cir) {
        if (IconGlyphs.isIcon(c)) {
            cir.setReturnValue(IconGlyphs.ADVANCE);
        }
    }

}
