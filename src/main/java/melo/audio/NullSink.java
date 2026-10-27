package melo.audio;

/** Silent output that still runs in real time. Used by the self-test and as an explicit --audio null. */
public final class NullSink extends PacedSink {
    public NullSink() {
        super(60, 0);
    }

    @Override
    public void open() {
    }

    @Override
    protected void emit(byte[] buf, int off, int len) {
    }
}
