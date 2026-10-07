package com.ootruffle.clienttag.modern;

import com.ootruffle.clienttag.ClientTag;
import com.ootruffle.clienttag.platform.Platform;
import net.fabricmc.api.ClientModInitializer;

/** Fabric entrypoint for every version after 1.8.9. */
public class ModernEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Platform.install(new ModernPlatform());
        ClientTag.init();
    }

}
