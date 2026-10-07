package com.ootruffle.clienttag.legacy.mixins;

import com.ootruffle.clienttag.legacy.render.NametagIconRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the two text passes of Render#renderLivingLabel: the first drawString is the
 * see-through pass (depth disabled), the second the normal depth-tested pass. Drawing the
 * icon right before each keeps it consistent with the name behind walls.
 */
@Mixin(Render.class)
public abstract class RenderMixin {

    @Inject(
            method = "renderLivingLabel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;III)I", ordinal = 0)
    )
    private void clienttag$beforeSeeThroughText(Entity entity, String str, double x, double y, double z, int maxDistance, CallbackInfo ci) {
        NametagIconRenderer.render(entity, str, true);
    }

    @Inject(
            method = "renderLivingLabel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;III)I", ordinal = 1)
    )
    private void clienttag$beforeText(Entity entity, String str, double x, double y, double z, int maxDistance, CallbackInfo ci) {
        NametagIconRenderer.render(entity, str, false);
    }

}
