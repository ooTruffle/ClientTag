package com.ootruffle.clienttag.modern.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.modern.IconText;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adds the client icons in front of names in the tab list; vanilla sizes the columns to fit. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
    private Component clienttag$addIcons(Component name, PlayerInfo info) {
        if (!ClientTagSettings.showInTab()) {
            return name;
        }
        //? if >= 1.21.10 {
        return IconText.withIcons(info.getProfile().id(), name);
        //?} else
        /*return IconText.withIcons(info.getProfile().getId(), name);*/
    }

}
