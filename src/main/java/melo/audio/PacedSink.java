package melo.audio;

import java.io.IOException;

/**
 * Base for outputs that can't report their play position (pipes into pacat / pw-cat). We pace writes
 * against a wall clock so only a small, known amount of audio is ever buffered downstream; the clock
 * minus that latency is then a good estimate of what's audible, which keeps the visualizer in sync.
 */
abstract class PacedSink implements AudioSink {
    private static final long NS = 1_000_000_000L;

    private final long leadFrames, latencyFrames;
    private long written;
    private long startNs = -1, pausedAt = -1;
    protected volatile boolean closed;

    PacedSink(int leadMs, int latencyMs) {
        this.leadFrames = (long) Player.RATE * leadMs / 1000;
        this.latencyFrames = (long) Player.RATE * latencyMs / 1000;
    }

    protected abstract void emit(byte[] buf, int off, int len) throws IOException;

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        synchronized (this) {
            long now = System.nanoTime();
            if (startNs < 0) startNs = now;
            long clock = clock(now);
            if (clock > written) startNs += (clock - written) * NS / Player.RATE; // underrun: re-anchor
        }
        emit(buf, off, len);
        synchronized (this) {
            written += len / 4;
        }
        while (true) {
            if (closed) throw new IOException("sink closed");
            long ahead;
            synchronized (this) {
                ahead = written - clock(System.nanoTime());
            }
            if (ahead <= leadFrames) break;
            long frames = Math.min(ahead - leadFrames, Player.RATE / 50);
            sleep(Math.max(1, frames * 1000 / Player.RATE));
        }
    }

    private long clock(long now) {
        if (startNs < 0) return 0;
        long end = pausedAt >= 0 ? pausedAt : now;
        return (end - startNs) * Player.RATE / NS;
    }

    @Override
    public synchronized long played() {
        long p = clock(System.nanoTime()) - latencyFrames;
        return Math.max(0, Math.min(written, p));
    }

    @Override
    public synchronized void pause() {
        if (pausedAt < 0) pausedAt = System.nanoTime();
    }

    @Override
    public synchronized void resume() {
        if (pausedAt >= 0) {
            if (startNs >= 0) startNs += System.nanoTime() - pausedAt;
            pausedAt = -1;
        }
    }

    @Override
    public void drain() {
        long deadline = System.nanoTime() + 4 * NS;
        while (!closed) {
            boolean paused;
            long left;
            synchronized (this) {
                paused = pausedAt >= 0;
                left = written - played();
            }
            if (left <= 0) break;
            if (paused) deadline = System.nanoTime() + 4 * NS;
            else if (System.nanoTime() > deadline) break;
            sleep(10);
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
