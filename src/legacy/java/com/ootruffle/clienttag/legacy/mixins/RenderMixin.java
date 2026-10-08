package com.ootruffle.clienttag.legacy.mixins;

import com.ootruffle.clienttag.legacy.render.NametagIconRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Puts the client icons into the label at the start of Render#renderLivingLabel, so everything
 * that draws or measures the label - vanilla's background and both text passes, or a mod
 * replacing them - includes them.
 * <p>
 * Priority above the default so this runs after PolyPlus's own HEAD modifier, which only
 * badges a label that is exactly the player's display name.
 */
@Mixin(value = Render.class, priority = 1100)
public abstract class RenderMixin {

    @ModifyVariable(method = "renderLivingLabel", at = @At("HEAD"), argsOnly = true)
    private String clienttag$addIcons(String str, Entity entity) {
        return NametagIconRenderer.withIcons(entity, str);
    }

}
