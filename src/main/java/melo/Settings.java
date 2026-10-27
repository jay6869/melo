package melo;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;

/** ~/.config/melo/settings.properties: theme, visualizer, volume, autoplay. */
final class Settings {
    private final Path file;
    private final Properties props = new Properties();

    Settings(Path dir) {
        file = dir.resolve("settings.properties");
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                Log.error("reading settings", e);
            }
        }
    }

    String get(String key, String def) {
        return props.getProperty(key, def);
    }

    double getDouble(String key, double def) {
        try {
            return Double.parseDouble(props.getProperty(key));
        } catch (Exception e) {
            return def;
        }
    }

    int getInt(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key));
        } catch (Exception e) {
            return def;
        }
    }

    boolean getBool(String key, boolean def) {
        String v = props.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    void set(String key, Object value) {
        props.setProperty(key, String.valueOf(value));
    }

    void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, UTF_8)) {
                props.store(w, "melo settings");
            }
        } catch (IOException e) {
            Log.error("saving settings", e);
        }
    }
}
