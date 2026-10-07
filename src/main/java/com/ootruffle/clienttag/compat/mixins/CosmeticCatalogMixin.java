package com.ootruffle.clienttag.compat.mixins;

import com.ootruffle.clienttag.ClientTagUsers;
import com.ootruffle.clienttag.render.ClientIcon;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ClientTag logs its users into PolyPlus, so PolyPlus would give them its badge too. Its
 * nametag and tab badges both ask {@code CosmeticCatalog.isPolyPlusUser}, which then says no
 * for ClientTag users who told the ClientTag server they aren't really on PolyPlus/OneClient.
 * Nothing else in PolyPlus asks it, so their cosmetics are unaffected.
 * <p>
 * Optional: only applied with PolyPlus installed, and skipped if its code has changed shape.
 */
@Pseudo
@Mixin(targets = "org.polyfrost.polyplus.client.cosmetics.CosmeticCatalog", remap = false)
public abstract class CosmeticCatalogMixin {

    @Inject(method = "isPolyPlusUser", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void clienttag$hideClientTagUsers(UUID uuid, CallbackInfoReturnable<Boolean> cir) {
        if (ClientTagUsers.isKnownNotOn(uuid, ClientIcon.POLYPLUS)) {
            cir.setReturnValue(false);
        }
    }

}
