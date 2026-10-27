package melo.audio;

import java.util.function.Supplier;

import melo.Sys;

public final class Sinks {
    private Sinks() {
    }

    /** @param pref auto | pulse | pipewire | java | null */
    public static Supplier<AudioSink> factory(String pref) {
        String p = pref == null ? "auto" : pref;
        return switch (p) {
            case "pulse", "pacat" -> PipeSink::pulse;
            case "pipewire", "pw" -> PipeSink::pipewire;
            case "java", "alsa" -> JavaSoundSink::new;
            case "null", "none" -> NullSink::new;
            default -> {
                if (Sys.which("pacat") != null) yield PipeSink::pulse;
                if (Sys.which("pw-cat") != null) yield PipeSink::pipewire;
                yield JavaSoundSink::new;
            }
        };
    }
}
