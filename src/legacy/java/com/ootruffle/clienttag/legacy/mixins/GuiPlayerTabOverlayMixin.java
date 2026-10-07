package com.ootruffle.clienttag.legacy.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.ootruffle.clienttag.legacy.render.TabIconRenderer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.network.NetworkPlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * In renderPlayerlist, getPlayerName is called once while measuring the column width and
 * once per row while drawing; drawStringWithShadow ordinals 1 and 2 draw a row's name
 * (spectator and normal), while 0 and 3 are the header and footer.
 */
@Mixin(GuiPlayerTabOverlay.class)
public abstract class GuiPlayerTabOverlayMixin {

    @WrapOperation(
            method = "renderPlayerlist",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiPlayerTabOverlay;getPlayerName(Lnet/minecraft/client/network/NetworkPlayerInfo;)Ljava/lang/String;")
    )
    private String clienttag$padName(GuiPlayerTabOverlay instance, NetworkPlayerInfo info, Operation<String> original) {
        return TabIconRenderer.padName(info, original.call(instance, info));
    }

    @WrapOperation(
            method = "renderPlayerlist",
            at = {
                    @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;FFI)I", ordinal = 1),
                    @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;FFI)I", ordinal = 2)
            }
    )
    private int clienttag$drawIcon(FontRenderer fontRenderer, String text, float x, float y, int color, Operation<Integer> original) {
        TabIconRenderer.drawPendingIcons(x, y, color);
        return original.call(fontRenderer, text, x, y, color);
    }

}
