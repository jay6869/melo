package melo.audio;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import melo.Log;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Audio engine. ffmpeg decodes anything (YouTube opus/m4a streams, local files) to s16le PCM; we apply
 * volume, keep a ring of recent samples for the visualizer, and hand the PCM to an {@link AudioSink}.
 * Every play/seek starts a fresh {@link Session} (ffmpeg process + sink); the old one is torn down.
 */
public final class Player {
    public static final int RATE = 48000;

    public enum State { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    public interface Listener {
        void started();

        void ended();

        /** @param beforeAudio true if the stream failed before producing any sound (e.g. HTTP 403) */
        void failed(String message, boolean beforeAudio);
    }

    private static final int RING = 1 << 17;
    private final float[] ringL = new float[RING], ringR = new float[RING];
    private volatile long ringPos;

    private final Supplier<AudioSink> sinks;
    private final Listener listener;
    private volatile Session session;
    private volatile State state = State.IDLE;
    private volatile Media media;
    private volatile double volume = 0.8;
    private volatile double idlePosition;
    private int restarts;

    public Player(Supplier<AudioSink> sinks, Listener listener) {
        this.sinks = sinks;
        this.listener = listener;
    }

    public State state() {
        return state;
    }

    public Media media() {
        return media;
    }

    public double volume() {
        return volume;
    }

    public void setVolume(double v) {
        volume = Math.max(0, Math.min(1, v));
    }

    public double position() {
        Session s = session;
        return s != null ? s.position() : idlePosition;
    }

    public double duration() {
        Media m = media;
        return m == null ? 0 : m.duration();
    }

    /** Stops current playback and shows a loading state while the next stream is resolved. */
    public synchronized void prepare() {
        cancel();
        media = null;
        idlePosition = 0;
        state = State.LOADING;
    }

    public synchronized void play(Media m, double at) {
        cancel();
        media = m;
        restarts = 0;
        startSession(at, false);
    }

    public synchronized void stop() {
        cancel();
        media = null;
        idlePosition = 0;
        state = State.IDLE;
    }

    public synchronized void togglePause() {
        Session s = session;
        if (s == null) return;
        if (state == State.PAUSED) {
            s.setPaused(false);
            state = s.started ? State.PLAYING : State.LOADING;
        } else if (state == State.PLAYING || state == State.LOADING) {
            s.setPaused(true);
            state = State.PAUSED;
        }
    }

    public synchronized void seek(double sec) {
        Media m = media;
        if (m == null || session == null) return;
        if (m.duration() > 0) sec = Math.min(sec, m.duration() - 0.5);
        sec = Math.max(0, sec);
        boolean paused = state == State.PAUSED;
        cancel();
        startSession(sec, paused);
    }

    /** Copies the {@code n} most recently heard frames. Returns 0 (and zero-fills) when nothing is playing. */
    public int snapshot(float[] l, float[] r, int n) {
        Session s = session;
        AudioSink sink = s == null ? null : s.sink;
        if (sink == null || state != State.PLAYING) {
            Arrays.fill(l, 0, n, 0f);
            Arrays.fill(r, 0, n, 0f);
            return 0;
        }
        long head = ringPos;
        long end = Math.min(s.startAbs + sink.played(), head);
        long start = end - n, oldest = head - RING;
        for (int i = 0; i < n; i++) {
            long a = start + i;
            if (a < 0 || a < oldest) {
                l[i] = 0f;
                r[i] = 0f;
            } else {
                int j = (int) (a & (RING - 1));
                l[i] = ringL[j];
                r[i] = ringR[j];
            }
        }
        return n;
    }

    private void cancel() {
        Session s = session;
        if (s != null) {
            idlePosition = s.position();
            s.cancel();
        }
        session = null;
    }

    private void startSession(double at, boolean paused) {
        Session s = new Session(media, at, paused);
        session = s;
        state = paused ? State.PAUSED : State.LOADING;
        Thread.ofPlatform().daemon().name("melo-decoder").start(s);
    }

    private void onStarted(Session s) {
        synchronized (this) {
            if (s != session) return;
            if (state == State.LOADING) state = State.PLAYING;
        }
        listener.started();
    }

    private void finished(Session s, String err, boolean audible, double pos) {
        Runnable cb;
        synchronized (this) {
            if (s != session) return;
            Media m = media;
            boolean truncated = m != null && m.remote() && m.duration() > 0 && pos < m.duration() - 5;
            if (audible && m != null && m.remote() && restarts < 3 && (err != null ? (m.duration() <= 0 || pos < m.duration() - 3) : truncated)) {
                restarts++;
                Log.info(String.format(Locale.ROOT, "stream dropped at %.1fs (%s), reconnecting", pos, err));
                startSession(pos, false);
                return;
            }
            session = null;
            idlePosition = pos;
            if (err == null) {
                state = State.IDLE;
                cb = listener::ended;
            } else {
                state = State.ERROR;
                cb = () -> listener.failed(err, !audible);
            }
        }
        cb.run();
    }

    static List<String> ffmpegCommand(Media m, double at) {
        List<String> c = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error"));
        if (m.remote()) {
            c.addAll(List.of("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_on_network_error", "1",
                    "-reconnect_delay_max", "5"));
            if (m.userAgent() != null) c.addAll(List.of("-user_agent", m.userAgent()));
        }
        if (at > 0.05) c.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", at)));
        c.addAll(List.of("-i", m.url(), "-vn", "-sn", "-dn", "-ac", "2", "-ar", String.valueOf(RATE),
                "-f", "s16le", "-acodec", "pcm_s16le", "pipe:1"));
        return c;
    }

    private final class Session implements Runnable {
        final Media m;
        final double at;
        volatile boolean cancelled, paused, started;
        volatile AudioSink sink;
        volatile Process proc;
        volatile long startAbs;
        volatile String lastError;

        Session(Media m, double at, boolean paused) {
            this.m = m;
            this.at = at;
            this.paused = paused;
        }

        double position() {
            AudioSink s = sink;
            return at + (s == null ? 0 : s.played()) / (double) RATE;
        }

        synchronized void setPaused(boolean p) {
            paused = p;
            AudioSink s = sink;
            if (s != null) {
                if (p) s.pause();
                else s.resume();
            }
            notifyAll();
        }

        void cancel() {
            cancelled = true;
            synchronized (this) {
                notifyAll();
            }
            Process p = proc;
            if (p != null) p.destroyForcibly();
            AudioSink s = sink;
            if (s != null) s.close();
        }

        @Override
        public void run() {
            AudioSink sk = null;
            Process p = null;
            boolean audible = false;
            try {
                sk = sinks.get();
                sk.open();
                p = new ProcessBuilder(ffmpegCommand(m, at))
                        .redirectInput(new File("/dev/null"))
                        .start();
                proc = p;
                startAbs = ringPos;
                synchronized (this) {
                    sink = sk;
                    if (paused) sk.pause();
                }
                if (cancelled) return;
                watchErrors(p);

                InputStream in = p.getInputStream();
                byte[] buf = new byte[4096 * 4];
                int fill = 0;
                while (!cancelled) {
                    synchronized (this) {
                        while (paused && !cancelled) wait();
                    }
                    if (cancelled) break;
                    int n = in.read(buf, fill, buf.length - fill);
                    if (n < 0) break;
                    fill += n;
                    int usable = fill & ~3;
                    if (usable == 0) continue;
                    process(buf, usable);
                    if (cancelled) break;
                    sk.write(buf, 0, usable);
                    if (!started) {
                        started = true;
                        audible = true;
                        onStarted(this);
                    }
                    int rest = fill - usable;
                    if (rest > 0) System.arraycopy(buf, usable, buf, 0, rest);
                    fill = rest;
                }
                if (cancelled) return;
                sk.drain();
                int code = p.waitFor();
                if (cancelled) return;
                String err = null;
                if (code != 0 || !audible) {
                    String le = lastError;
                    err = le != null ? le : "ffmpeg exited with code " + code;
                }
                finished(this, err, audible, position());
            } catch (Exception e) {
                if (!cancelled) {
                    String le = lastError;
                    finished(this, le != null ? le : String.valueOf(e.getMessage()), audible, position());
                }
            } finally {
                if (p != null) p.destroyForcibly();
                if (sk != null) sk.close();
            }
        }

        private void process(byte[] buf, int len) {
            double v = volume;
            float gain = (float) (v * v);
            long pos = ringPos;
            for (int i = 0; i < len; i += 4) {
                short sl = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                short sr = (short) ((buf[i + 2] & 0xff) | (buf[i + 3] << 8));
                int j = (int) (pos & (RING - 1));
                ringL[j] = sl / 32768f;
                ringR[j] = sr / 32768f;
                pos++;
                int ol = Math.round(sl * gain), or = Math.round(sr * gain);
                buf[i] = (byte) ol;
                buf[i + 1] = (byte) (ol >> 8);
                buf[i + 2] = (byte) or;
                buf[i + 3] = (byte) (or >> 8);
            }
            ringPos = pos;
        }

        private void watchErrors(Process p) {
            Thread.ofVirtual().start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.isBlank()) continue;
                        lastError = line.strip();
                        Log.info("ffmpeg: " + line);
                    }
                } catch (IOException ignored) {
                }
            });
        }
    }
}
