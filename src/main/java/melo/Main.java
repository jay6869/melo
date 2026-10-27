package melo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import melo.term.Terminal;
import melo.ui.Theme;

public final class Main {
    public static final String VERSION = "0.1.0";

    record Options(String theme, String audio, boolean transparent, List<Path> files, String snapshot, Path selftest) {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Options o = parse(args);
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"))
            die("Melo is built for Linux only.");
        if (Sys.which("ffmpeg") == null)
            die("""
                    Melo needs ffmpeg on your PATH.
                      Arch:    sudo pacman -S ffmpeg
                      Debian:  sudo apt install ffmpeg
                      Fedora:  sudo dnf install ffmpeg""");
        if (o.selftest() != null) {
            SelfTest.run(o.selftest());
            return;
        }
        if (o.snapshot() != null) {
            App.snapshot(o);
            return;
        }
        if (!Terminal.isInteractive()) die("Melo is a terminal app — run it from an interactive terminal.");
        Log.init();
        new App(o, null).run();
    }

    private static Options parse(String[] args) {
        String theme = null, audio = "auto", snapshot = null;
        boolean transparent = false;
        Path selftest = null;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> {
                    usage();
                    System.exit(0);
                }
                case "-V", "--version" -> {
                    System.out.println("melo " + VERSION);
                    System.exit(0);
                }
                case "-t", "--theme" -> {
                    theme = need(args, ++i, a);
                    if (Theme.indexOf(theme) < 0) die("unknown theme: " + theme + " (" + themes() + ")");
                }
                case "-a", "--audio" -> audio = need(args, ++i, a);
                case "--transparent" -> transparent = true;
                case "--snapshot" -> snapshot = need(args, ++i, a);
                case "--selftest" -> selftest = Path.of(need(args, ++i, a));
                default -> {
                    if (a.startsWith("-")) die("unknown option: " + a + "  (try --help)");
                    Path p = Path.of(a);
                    if (!Files.exists(p)) die("no such file or directory: " + a);
                    files.add(p);
                }
            }
        }
        return new Options(theme, audio, transparent, files, snapshot, selftest);
    }

    private static String need(String[] args, int i, String opt) {
        if (i >= args.length) die("missing value for " + opt);
        return args[i];
    }

    private static String themes() {
        return String.join(", ", Theme.ALL.stream().map(Theme::name).toList());
    }

    private static void usage() {
        System.out.println("""
                melo %s — music from YouTube Music, in your terminal

                usage: melo [options] [files or folders…]

                  -t, --theme NAME     %s
                  -a, --audio OUTPUT   auto | pulse | pipewire | java   (default: auto)
                      --transparent    keep the terminal's own background
                  -V, --version
                  -h, --help

                Local files and folders given on the command line are queued and played.
                Inside melo press ? for every key binding.
                Settings: ~/.config/melo   Library: ~/.local/share/melo   Log: ~/.cache/melo/melo.log
                """.formatted(VERSION, themes()));
    }

    static void die(String msg) {
        System.err.println("melo: " + msg);
        System.exit(1);
    }
}
