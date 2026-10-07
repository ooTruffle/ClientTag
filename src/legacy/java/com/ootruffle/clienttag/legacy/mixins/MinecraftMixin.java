package com.ootruffle.clienttag.legacy.mixins;

import com.ootruffle.clienttag.ClientTag;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "runTick", at = @At("TAIL"))
    private void clienttag$onTick(CallbackInfo ci) {
        ClientTag.onClientTick();
    }

}
