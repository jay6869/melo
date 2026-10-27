package melo;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Minimal file logger at ~/.cache/melo/melo.log. Also captures System.err, so stray library output
 * can't scribble over the TUI.
 */
public final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static PrintStream out = System.err;

    private Log() {
    }

    public static void init() {
        try {
            Path dir = Sys.cacheDir();
            Files.createDirectories(dir);
            Path file = dir.resolve("melo.log");
            boolean append = !Files.exists(file) || Files.size(file) < 2_000_000;
            out = new PrintStream(new FileOutputStream(file.toFile(), append), true, UTF_8);
            System.setErr(out);
            info("melo " + Main.VERSION + " starting (java " + System.getProperty("java.version") + ")");
        } catch (IOException e) {
            out = new PrintStream(PrintStream.nullOutputStream());
            System.setErr(out);
        }
    }

    public static synchronized void info(String msg) {
        out.println(LocalDateTime.now().format(TS) + "  " + msg);
    }

    public static synchronized void error(String msg, Throwable t) {
        out.println(LocalDateTime.now().format(TS) + "  ERROR " + msg);
        if (t != null) t.printStackTrace(out);
    }
}
