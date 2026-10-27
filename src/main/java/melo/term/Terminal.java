package melo.term;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Raw-mode handling for the controlling TTY. Linux only: relies on stty and /dev/tty. */
public final class Terminal {
    private final OutputStream out = new FileOutputStream(FileDescriptor.out);
    private InputStream in;
    private String saved;

    public static boolean isInteractive() {
        try {
            stty("-g");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether the terminal speaks the kitty graphics protocol (kitty, ghostty, WezTerm, Konsole).
     * MELO_GRAPHICS=0/1 overrides the guess. Off inside tmux/screen, which don't pass it through.
     */
    public static boolean supportsGraphics() {
        String force = System.getenv("MELO_GRAPHICS");
        if (force != null) return !force.equals("0");
        if (System.getenv("TMUX") != null || System.getenv("STY") != null) return false;
        String term = String.valueOf(System.getenv("TERM")), prog = String.valueOf(System.getenv("TERM_PROGRAM"));
        return term.contains("kitty") || term.contains("ghostty") || System.getenv("KITTY_WINDOW_ID") != null
                || prog.equalsIgnoreCase("ghostty") || prog.equalsIgnoreCase("WezTerm")
                || System.getenv("KONSOLE_VERSION") != null;
    }

    public void enter() throws IOException {
        saved = stty("-g").trim();
        stty("-echo", "-icanon", "-isig", "-ixon", "-iexten", "-icrnl", "min", "1", "time", "0");
        in = new BufferedInputStream(new FileInputStream("/dev/tty"), 4096);
        // alt screen, hide cursor, no autowrap, mouse (button + drag, SGR encoding), title
        write("\033[?1049h\033[?25l\033[?7l\033[?1002h\033[?1006h\033]0;Melo\007\033[2J");
    }

    public void exit() {
        write("\033_Ga=d,d=A,q=2\033\\");
        write("\033[?1002l\033[?1006l\033[0m\033[?7h\033[?25h\033[?1049l\033]0;\007");
        if (saved != null) {
            try {
                stty(saved);
            } catch (IOException ignored) {
            }
            saved = null;
        }
    }

    public InputStream input() {
        return in;
    }

    public OutputStream output() {
        return out;
    }

    /** @return {columns, rows} */
    public int[] size() {
        try {
            String[] p = stty("size").trim().split("\\s+");
            return new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[0])};
        } catch (Exception e) {
            return new int[]{envInt("COLUMNS", 100), envInt("LINES", 30)};
        }
    }

    public synchronized void write(String s) {
        try {
            out.write(s.getBytes(UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static int envInt(String name, int def) {
        try {
            return Integer.parseInt(System.getenv(name));
        } catch (Exception e) {
            return def;
        }
    }

    static String stty(String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("stty");
        Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd)
                .redirectInput(new File("/dev/tty"))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String result = new String(p.getInputStream().readAllBytes(), UTF_8);
        try {
            if (p.waitFor() != 0) throw new IOException("stty " + String.join(" ", args) + " failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return result;
    }
}
