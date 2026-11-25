package com.ootruffle.clienttag.update;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Swaps the mod's jar for a downloaded update once the game has closed (see {@link Updater}). Runs
 * as its own small Java process, started as the game exits, because the running game holds its jar
 * open (Windows won't let it be deleted until then).
 * <p>
 * Uses only the JDK, never Minecraft or Fabric classes. Arguments: the game's process ID, the
 * downloaded jar, the old jar, and where the new jar goes. If the old jar can't be removed, the new
 * one is taken back out of {@code mods}, so two versions are never loaded together; the download is
 * kept and the next exit tries again.
 */
public final class UpdateApplier {

    /** Same as {@link Updater#PENDING_FILE}; not referenced, so this class needs nothing else from the jar. */
    private static final String PENDING_FILE = "pending.txt";
    private static final int DELETE_ATTEMPTS = 60;

    private UpdateApplier() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: UpdateApplier <pid> <downloaded jar> <old jar> <new jar>");
            System.exit(2);
        }
        final long pid = Long.parseLong(args[0]);
        final Path staged = Paths.get(args[1]);
        final Path oldJar = Paths.get(args[2]);
        final Path newJar = Paths.get(args[3]);
        final Path log = staged.resolveSibling("update.log");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND), true)) {
            out.println(LocalDateTime.now() + " waiting for the game (pid " + pid + ") to close");
            ProcessHandle.of(pid).ifPresent(game -> {
                try {
                    game.onExit().get(10, TimeUnit.MINUTES);
                } catch (Exception e) {
                    out.println("  gave up waiting: " + e);
                }
            });
            Thread.sleep(500);
            try {
                apply(staged, oldJar, newJar, out);
                out.println(LocalDateTime.now() + " installed " + newJar.getFileName());
            } catch (Exception e) {
                out.println(LocalDateTime.now() + " update not installed: " + e);
            }
        }
    }

    static void apply(Path staged, Path oldJar, Path newJar, PrintWriter out) throws IOException, InterruptedException {
        if (!Files.isRegularFile(staged)) {
            throw new IOException("the download is missing: " + staged);
        }
        final boolean sameName = oldJar.toAbsolutePath().normalize().equals(newJar.toAbsolutePath().normalize());
        if (!sameName) {
            // A .tmp name first: Fabric only loads *.jar, so a half-copied file is never picked up.
            final Path tmp = newJar.resolveSibling(newJar.getFileName() + ".tmp");
            Files.copy(staged, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, newJar, StandardCopyOption.REPLACE_EXISTING);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                if (sameName) {
                    Files.copy(staged, newJar, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(oldJar);
                }
                break;
            } catch (IOException e) {
                if (attempt >= DELETE_ATTEMPTS) {
                    if (!sameName) {
                        Files.deleteIfExists(newJar); // Never leave two versions in mods.
                    }
                    throw new IOException("the old jar is still in use: " + e, e);
                }
                if (attempt == 1) {
                    out.println("  old jar still locked, retrying: " + e);
                }
                Thread.sleep(500);
            }
        }
        Files.deleteIfExists(staged);
        Files.deleteIfExists(staged.resolveSibling(PENDING_FILE));
    }

}
