package melo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Sys {
    private Sys() {
    }

    /** Absolute path of an executable on PATH, or null. */
    public static String which(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    public static Path dataDir() {
        return xdg("XDG_DATA_HOME", ".local/share").resolve("melo");
    }

    public static Path configDir() {
        return xdg("XDG_CONFIG_HOME", ".config").resolve("melo");
    }

    public static Path cacheDir() {
        return xdg("XDG_CACHE_HOME", ".cache").resolve("melo");
    }

    private static Path xdg(String env, String fallback) {
        String v = System.getenv(env);
        if (v != null && !v.isBlank()) return Path.of(v);
        return Path.of(System.getProperty("user.home"), fallback);
    }
}
