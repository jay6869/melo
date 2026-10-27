package melo.ui;

import melo.term.Screen;

/** The big "MELO" wordmark, ANSI Shadow style: solid faces in the accent, outlines in the muted color. */
public final class Logo {
    private static final String[] M = {
            "███╗   ███╗",
            "████╗ ████║",
            "██╔████╔██║",
            "██║╚██╔╝██║",
            "██║ ╚═╝ ██║",
            "╚═╝     ╚═╝"};
    private static final String[] E = {
            "███████╗",
            "██╔════╝",
            "█████╗  ",
            "██╔══╝  ",
            "███████╗",
            "╚══════╝"};
    private static final String[] L = {
            "██╗     ",
            "██║     ",
            "██║     ",
            "██║     ",
            "███████╗",
            "╚══════╝"};
    private static final String[] O = {
            " ██████╗ ",
            "██╔═══██╗",
            "██║   ██║",
            "██║   ██║",
            "╚██████╔╝",
            " ╚═════╝ "};

    public static final String[] LINES = new String[6];
    public static final int WIDTH, HEIGHT = 6;

    static {
        for (int i = 0; i < 6; i++) LINES[i] = M[i] + E[i] + L[i] + O[i];
        WIDTH = LINES[0].length();
    }

    private Logo() {
    }

    /** @param reveal 0..1 fraction of columns visible, for the intro wipe */
    public static void draw(Screen s, int x, int y, Theme th, double reveal) {
        int visible = (int) Math.ceil(WIDTH * Math.max(0, Math.min(1, reveal)));
        for (int r = 0; r < HEIGHT; r++) {
            String line = LINES[r];
            for (int c = 0; c < Math.min(visible, line.length()); c++) {
                char ch = line.charAt(c);
                if (ch == ' ') continue;
                boolean face = ch == '█';
                s.set(x + c, y + r, ch, face ? th.accent() : th.dim(), Screen.KEEP, face ? Screen.BOLD : 0);
            }
        }
    }
}
