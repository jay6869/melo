package melo.audio;

import java.io.IOException;

/** Plays interleaved s16le stereo PCM at {@link Player#RATE}. One instance per playback session. */
public interface AudioSink extends AutoCloseable {
    void open() throws IOException;

    /** Blocks roughly in real time. */
    void write(byte[] buf, int off, int len) throws IOException;

    /** Frames that have actually reached the speakers since {@link #open}. */
    long played();

    void pause();

    void resume();

    /** Waits until everything written has been heard. */
    void drain();

    @Override
    void close();
}
