package melo.ui;

import melo.term.Screen;

/** Three-row box-drawing digits for the large clock on the Now Playing screen. */
public final class BigDigits {
    private static final String[][] GLYPHS = {
            {"╭─╮", "│ │", "╰─╯"},
            {"╶┐ ", " │ ", "╶┴╴"},
            {"╭─╮", "╭─╯", "╰─╴"},
            {"╶─╮", " ─┤", "╶─╯"},
            {"╷ ╷", "╰─┤", "  ╵"},
            {"╭─╴", "╰─╮", "╶─╯"},
            {"╭─╴", "├─╮", "╰─╯"},
            {"╶─╮", "  │", "  ╵"},
            {"╭─╮", "├─┤", "╰─╯"},
            {"╭─╮", "╰─┤", "╶─╯"}};
    private static final String[] COLON = {" ", ":", " "};

    private BigDigits() {
    }

    public static int width(String text) {
        int w = 0;
        for (char c : text.toCharArray()) w += Character.isDigit(c) ? 4 : 2;
        return w;
    }

    /** Draws digits and ':' — digits in {@code color}, the colon in {@code colonColor}. Returns the width. */
    public static int draw(Screen s, int x, int y, String text, int color, int colonColor, int bg) {
        int cx = x;
        for (char c : text.toCharArray()) {
            boolean digit = Character.isDigit(c);
            String[] g = digit ? GLYPHS[c - '0'] : COLON;
            for (int r = 0; r < 3; r++) {
                String row = g[r];
                for (int i = 0; i < row.length(); i++) {
                    char ch = row.charAt(i);
                    if (ch != ' ') s.set(cx + i, y + r, ch, digit ? color : colonColor, bg, Screen.BOLD);
                }
            }
            cx += digit ? 4 : 2;
        }
        return cx - x;
    }
}
