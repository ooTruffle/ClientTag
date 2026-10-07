package com.ootruffle.clienttag.modern.mixins;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.modern.IconText;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >= 1.21.4 {
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
//?} else {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
*///?}

/**
 * Adds the client icons to players' nametags. Only the name itself is touched - the
 * below-name scoreboard line is drawn from separate text.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    //? if >= 1.21.4 {
    /** getNameTag fills the render state's nametag, which is extracted again every frame. */
    @ModifyReturnValue(method = "getNameTag", at = @At("RETURN"))
    private Component clienttag$addIcons(Component name, Entity entity) {
        return entity instanceof Player && ClientTagSettings.showOnNametags() ? IconText.withIcons(entity.getUUID(), name) : name;
    }
    //?} else {
    /*@WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;renderNameTag(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V")
    )
    private void clienttag$addIcons(EntityRenderer<?> renderer, Entity entity, Component name, PoseStack poseStack,
                                    MultiBufferSource buffers, int light, float partialTick, Operation<Void> original) {
        if (entity instanceof Player && ClientTagSettings.showOnNametags()) {
            name = IconText.withIcons(entity.getUUID(), name);
        }
        original.call(renderer, entity, name, poseStack, buffers, light, partialTick);
    }
    *///?}

}
