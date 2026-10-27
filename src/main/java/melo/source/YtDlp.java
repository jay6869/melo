package melo.source;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import melo.Sys;
import melo.audio.Media;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Optional fallback when YouTube rejects a NewPipe-resolved stream: ask yt-dlp for a URL instead. */
public final class YtDlp {
    private YtDlp() {
    }

    public static boolean available() {
        return Sys.which("yt-dlp") != null;
    }

    public static Resolved resolve(Track t) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("yt-dlp", "-f", "bestaudio/best", "-g", "--no-warnings", "--no-playlist", t.url())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("yt-dlp timed out");
        }
        String url = out.isEmpty() ? "" : out.split("\n")[0].strip();
        if (!url.startsWith("http")) throw new IOException("yt-dlp found no stream");
        return new Resolved(new Media(url, null, Math.max(0, t.duration()), true), t, "yt-dlp");
    }
}
