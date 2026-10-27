package melo;

import java.nio.file.Path;
import java.util.Locale;

import melo.audio.NullSink;
import melo.audio.Player;
import melo.audio.Spectrum;
import melo.source.LocalFiles;
import melo.source.Resolved;
import melo.source.Track;

/** `melo --selftest FILE`: exercises decode → ring → FFT → seek → pause headlessly, without sound. */
final class SelfTest {
    private SelfTest() {
    }

    static void run(Path file) throws Exception {
        Player p = new Player(NullSink::new, new Player.Listener() {
            @Override
            public void started() {
                System.out.println("  · started");
            }

            @Override
            public void ended() {
                System.out.println("  · ended");
            }

            @Override
            public void failed(String message, boolean beforeAudio) {
                System.out.println("  · failed (beforeAudio=" + beforeAudio + "): " + message);
            }
        });
        Track t = LocalFiles.track(file);
        Resolved r = LocalFiles.resolve(t);
        System.out.println("probe: " + r.track().title() + " / " + r.track().artist() + " · " + r.format()
                + " · " + r.media().duration() + "s");
        Spectrum sp = new Spectrum(Player.RATE);
        p.play(r.media(), 0);
        for (int i = 0; i < 12; i++) {
            Thread.sleep(150);
            sp.update(p, 0.15);
            System.out.println(String.format(Locale.ROOT, "  %-8s pos=%6.2fs  level=%.3f  bass=%.2f  %s",
                    p.state(), p.position(), sp.level, sp.bass, bars(sp.bands(24).v)));
        }
        double target = Math.max(1, r.media().duration() / 2);
        p.seek(target);
        Thread.sleep(600);
        System.out.println(String.format(Locale.ROOT, "seek → %.1fs : pos=%.2fs state=%s", target, p.position(), p.state()));
        p.togglePause();
        double a = p.position();
        Thread.sleep(500);
        double b = p.position();
        System.out.println(String.format(Locale.ROOT, "paused: %.2f → %.2f (%s)", a, b, Math.abs(b - a) < 0.01 ? "held" : "DRIFTED"));
        p.togglePause();
        Thread.sleep(400);
        System.out.println(String.format(Locale.ROOT, "resumed: pos=%.2fs state=%s", p.position(), p.state()));
        p.seek(Math.max(0, r.media().duration() - 1.5));
        Thread.sleep(2500);
        System.out.println("near end → state=" + p.state());
        p.stop();
    }

    private static String bars(float[] v) {
        String e = " ▁▂▃▄▅▆▇█";
        StringBuilder sb = new StringBuilder();
        for (float x : v) sb.append(e.charAt(Math.max(0, Math.min(8, Math.round(x * 8)))));
        return sb.toString();
    }
}
