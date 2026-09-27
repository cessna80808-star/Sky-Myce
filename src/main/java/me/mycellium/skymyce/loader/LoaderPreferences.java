package me.mycellium.skymyce.loader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;

/** Instance-local preferences, also readable by the standalone installer helper. */
public final class LoaderPreferences {
    private LoaderPreferences() {}
    private static Path file(Path game) { return game.resolve(".sky-myce/loader-settings.properties"); }

    public static boolean autoUpdateLoader(Path game) {
        try {
            var file = file(game);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096) return false;
            var values = new Properties();
            try (var input = Files.newInputStream(file)) { values.load(input); }
            return "true".equals(values.getProperty("autoUpdateLoader"));
        } catch (java.io.IOException | IllegalArgumentException ignored) { return false; }
    }

    public static void saveAutoUpdateLoader(Path game, boolean enabled) throws Exception {
        SignedFiles.atomicWrite(file(game), ("autoUpdateLoader=" + enabled + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
