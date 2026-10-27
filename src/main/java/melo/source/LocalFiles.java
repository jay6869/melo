package melo.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import melo.audio.Media;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Plays files from disk: `melo ~/Music/album/` queues everything ffmpeg can decode. */
public final class LocalFiles {
    private static final Set<String> AUDIO = Set.of("mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav",
            "wma", "webm", "mka", "alac", "aiff", "ape", "wv");

    private LocalFiles() {
    }

    public static List<Track> expand(List<Path> paths) throws IOException {
        List<Track> out = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.filter(Files::isRegularFile).filter(LocalFiles::isAudio).sorted().forEach(f -> out.add(track(f)));
                }
            } else if (Files.isRegularFile(p)) {
                out.add(track(p));
            }
        }
        return out;
    }

    static boolean isAudio(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 && AUDIO.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static Track track(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        String name = abs.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String title = dot > 0 ? name.substring(0, dot) : name;
        Path parent = abs.getParent();
        String artist = parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
        return new Track("file:" + abs, abs.toString(), title, artist, null, 0, "file://" + abs);
    }

    /** Probes duration and tags with ffprobe. */
    public static Resolved resolve(Track t) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("ffprobe", "-v", "error",
                "-show_entries", "format=duration:format_tags=title,artist,album:stream=codec_name,bit_rate"
                        + ":stream_tags=title,artist,album",
                "-select_streams", "a:0", "-of", "default=nw=1", t.url())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        p.waitFor(10, TimeUnit.SECONDS);
        double dur = 0;
        String title = null, artist = null, album = null, codec = null;
        long bitrate = 0;
        for (String line : out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).toLowerCase(Locale.ROOT), v = line.substring(eq + 1).strip();
            switch (k) {
                case "duration" -> dur = parse(v);
                case "tag:title" -> title = v;
                case "tag:artist" -> artist = v;
                case "tag:album" -> album = v;
                case "codec_name" -> codec = v.toUpperCase(Locale.ROOT);
                case "bit_rate" -> bitrate = (long) parse(v);
                default -> {
                }
            }
        }
        Track rich = new Track(t.id(), t.url(),
                title != null && !title.isBlank() ? title : t.title(),
                artist != null && !artist.isBlank() ? artist : t.artist(),
                album, (long) dur, t.thumb());
        String format = codec == null ? "LOCAL" : codec + (bitrate > 0 ? " · " + bitrate / 1000 + " kbps" : "");
        return new Resolved(new Media(t.url(), null, dur, false), rich, format);
    }

    private static double parse(String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
