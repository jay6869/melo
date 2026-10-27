package melo.ui;

import melo.term.Screen;

/**
 * Melo's mascot. A three-line ASCII cat whose mood follows the player: it dances to the music,
 * naps when paused, ponders while loading, cries on errors and gets heart eyes when you like a song.
 */
public final class Cat {
    public enum Mood { IDLE, DANCE, SLEEP, THINK, SAD, LOVE, WAVE }

    /** Cat body is 7 columns; effects (notes, z's) use the next 5. */
    public static final int WIDTH = 12, HEIGHT = 3;

    private static final String EARS = " /\\_/\\ ";

    private Cat() {
    }

    public static void draw(Screen s, int x, int y, Mood mood, double t, float pulse, Theme th, int bg) {
        String face, paws;
        int beat = (int) (t * 2.2) % 2;
        int dx = 0;
        switch (mood) {
            case DANCE -> {
                face = pulse > 0.6f ? "( >ω< )" : "( ^ω^ )";
                paws = beat == 0 ? " />  > " : " <  <\\ ";
                dx = beat;
            }
            case SLEEP -> {
                face = "( -.- )";
                paws = " > ^ < ";
            }
            case THINK -> {
                face = (int) (t * 1.5) % 2 == 0 ? "( o.o )" : "( o.O )";
                paws = " > ^ < ";
            }
            case SAD -> {
                face = "( ;_; )";
                paws = " > ^ < ";
            }
            case LOVE -> {
                face = "( ♥ω♥ )";
                paws = " > ^ < ";
            }
            case WAVE -> {
                face = beat == 0 ? "( ^.^ )/" : "( ^.^ )-";
                paws = " > ^ < ";
            }
            default -> {
                boolean blink = (t % 4.0) < 0.15;
                face = blink ? "( -.- )" : "( o.o )";
                paws = " > ^ < ";
            }
        }
        int body = th.fg(), cheeks = th.accent();
        s.text(x + dx, y, EARS, body, bg, 0, x + WIDTH);
        s.text(x + dx, y + 1, face, body, bg, Screen.BOLD, x + WIDTH);
        if (mood == Mood.LOVE) {
            s.set(x + dx + 2, y + 1, '♥', cheeks, bg, Screen.BOLD);
            s.set(x + dx + 4, y + 1, '♥', cheeks, bg, Screen.BOLD);
        }
        s.text(x + dx, y + 2, paws, body, bg, 0, x + WIDTH);

        int fx = x + 8;
        switch (mood) {
            case DANCE -> {
                for (int i = 0; i < 2; i++) {
                    int phase = (int) (t * 2 + i * 1.5) % 3;
                    s.set(fx + i * 2 + (phase == 1 ? 1 : 0), y + 2 - phase, i == 0 ? '♪' : '♫', th.accent2(), bg, 0);
                }
            }
            case SLEEP -> {
                int phase = (int) (t * 1.2) % 3;
                s.set(fx, y + 1, 'z', th.dim(), bg, 0);
                if (phase >= 1) s.set(fx + 1, y, 'z', th.muted(), bg, 0);
                if (phase >= 2) s.set(fx + 2, y, 'Z', th.muted(), bg, Screen.BOLD);
            }
            case THINK -> s.set(fx, y, (int) (t * 3) % 2 == 0 ? '?' : ' ', th.accent2(), bg, Screen.BOLD);
            case SAD -> s.set(fx, y + 1, '·', th.accent2(), bg, 0);
            case LOVE -> {
                int phase = (int) (t * 2) % 3;
                s.set(fx + (phase % 2), y + 2 - phase, '♥', th.accent(), bg, 0);
            }
            default -> {
            }
        }
    }
}
