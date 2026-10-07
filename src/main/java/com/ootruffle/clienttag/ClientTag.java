package com.ootruffle.clienttag;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.render.ClientIcon;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;

/**
 * Shows Lunar Client, Dawn Client, Essential, NoRiskClient and LabyMod indicators next to the nametags of
 * players who are on those clients.
 * <p>
 * Everything is driven from mixins (client tick + nametag render); the Lunar, Dawn,
 * Essential, NoRisk and LabyMod connections are opened lazily on the first multiplayer tick, since this entrypoint runs before
 * Minecraft itself is constructed.
 * <p>
 * Each Minecraft version's entrypoint installs its {@link com.ootruffle.clienttag.platform.Platform}
 * and then calls {@link #init()}. Settings live in OneConfig when it's installed (see {@link ClientTagSettings}).
 */
public final class ClientTag {

    private ClientTag() {}

    public static void init() {
        migrateLegacyFiles();
        ClientTagSettings.init();
        System.out.println("[ClientTag] Initialized.");
    }

    /** Called at the end of every client tick. */
    public static void onClientTick() {
        NativeClients.settle();
        // Nothing connects anywhere until the first-launch warning has been accepted.
        if (!Consent.accepted()) {
            Consent.askIfNeeded();
            return;
        }
        ClientTagUsers.onClientTick();
        // A client we're running on draws its own indicators, and a second connection could disrupt its own.
        if (!NativeClients.isRunning(ClientIcon.LUNAR)) {
            LunarTagManager.onClientTick();
        }
        if (!NativeClients.isRunning(ClientIcon.DAWN)) {
            DawnTagManager.onClientTick();
        }
        if (!NativeClients.isRunning(ClientIcon.NORISK)) {
            NoRiskTagManager.onClientTick();
        }
        if (!NativeClients.isRunning(ClientIcon.ESSENTIAL)) {
            EssentialTagManager.onClientTick();
        }
        if (!NativeClients.isRunning(ClientIcon.LABYMOD)) {
            LabyModTagManager.onClientTick();
        }
        if (!NativeClients.isRunning(ClientIcon.POLYPLUS)) {
            OneConfigTagManager.onClientTick();
        }
    }

    /**
     * The mod used to be called LunerTag: moves files from config/lunertag (Lunar installation ID,
     * server list) to config/clienttag, leaving anything already at the new location alone.
     */
    private static void migrateLegacyFiles() {
        final Path configDir = FabricLoader.getInstance().getConfigDir();
        final Path legacy = configDir.resolve("lunertag");
        if (!Files.isDirectory(legacy)) {
            return;
        }
        final Path current = configDir.resolve("clienttag");
        try {
            Files.createDirectories(current);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(legacy)) {
                for (Path file : files) {
                    final Path target = current.resolve(file.getFileName());
                    if (!Files.exists(target)) {
                        Files.move(file, target);
                    }
                }
            }
            try (DirectoryStream<Path> left = Files.newDirectoryStream(legacy)) {
                if (!left.iterator().hasNext()) {
                    Files.delete(legacy);
                }
            }
        } catch (IOException e) {
            LogManager.getLogger("ClientTag").warn("Couldn't move old LunerTag files: {}", e.toString());
        }
    }

}
