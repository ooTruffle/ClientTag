package com.ootruffle.clienttag.legacy;

import com.ootruffle.clienttag.ClientTag;
import com.ootruffle.clienttag.platform.Platform;
import net.ornithemc.osl.entrypoints.api.ModInitializer;

/** 1.8.9 (Ornithe) entrypoint. */
public class LegacyEntrypoint implements ModInitializer {

    @Override
    public void init() {
        Platform.install(new LegacyPlatform());
        ClientTag.init();
    }

}
