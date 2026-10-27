package melo.audio;

import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Fallback output through javax.sound (ALSA). Exact play position, but bypasses the desktop mixer. */
final class JavaSoundSink implements AudioSink {
    private final AudioFormat format = new AudioFormat(Player.RATE, 16, 2, true, false);
    private SourceDataLine line;

    @Override
    public void open() throws IOException {
        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, Player.RATE * 4 / 8);
            line.start();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            throw new IOException("no audio output available: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        if (line == null || !line.isOpen()) throw new IOException("sink closed");
        line.write(buf, off, len);
    }

    @Override
    public long played() {
        return line == null ? 0 : line.getLongFramePosition();
    }

    @Override
    public void pause() {
        if (line != null) line.stop();
    }

    @Override
    public void resume() {
        if (line != null) line.start();
    }

    @Override
    public void drain() {
        if (line != null) line.drain();
    }

    @Override
    public void close() {
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
        }
    }
}
