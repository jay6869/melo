package melo.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Streams PCM into pacat (PulseAudio / pipewire-pulse) or pw-cat (native PipeWire). Shows up as "Melo" in mixers. */
final class PipeSink extends PacedSink {
    private static final int LATENCY_MS = 80;

    private final List<String> cmd;
    private Process proc;
    private OutputStream out;

    private PipeSink(List<String> cmd) {
        super(60, LATENCY_MS);
        this.cmd = cmd;
    }

    static PipeSink pulse() {
        return new PipeSink(List.of("pacat", "--playback", "--raw",
                "--format=s16le", "--rate=" + Player.RATE, "--channels=2",
                "--latency-msec=" + LATENCY_MS,
                "--client-name=Melo", "--stream-name=Melo",
                "--property=media.role=music", "--property=application.icon_name=audio-x-generic"));
    }

    static PipeSink pipewire() {
        return new PipeSink(List.of("pw-cat", "--playback", "--raw",
                "--format", "s16", "--rate", String.valueOf(Player.RATE), "--channels", "2",
                "--latency", LATENCY_MS + "ms", "--media-role", "Music", "-"));
    }

    @Override
    public void open() throws IOException {
        proc = new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        out = proc.getOutputStream();
    }

    @Override
    protected void emit(byte[] buf, int off, int len) throws IOException {
        if (closed) throw new IOException("sink closed");
        out.write(buf, off, len);
        out.flush();
    }

    @Override
    public void drain() {
        super.drain();
        try {
            out.close();
            proc.waitFor(1, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
        }
    }

    @Override
    public void close() {
        super.close();
        if (proc != null) proc.destroyForcibly();
    }
}
