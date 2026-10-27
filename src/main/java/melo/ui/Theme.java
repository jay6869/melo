package melo.ui;

import java.util.List;

import melo.term.Color;

/** A flat palette: background, raised panel, text, muted text, one accent and one secondary color. */
public record Theme(String name, int bg, int panel, int fg, int dim, int accent, int accent2) {
    public static final List<Theme> ALL = List.of(
            new Theme("rose", 0x191724, 0x1f1d2e, 0xe0def4, 0x6e6a86, 0xeb6f92, 0x9ccfd8),
            new Theme("mocha", 0x1e1e2e, 0x24243a, 0xcdd6f4, 0x6c7086, 0xcba6f7, 0xf5c2e7),
            new Theme("matcha", 0x1a1d17, 0x21251d, 0xe4e8d8, 0x6b735e, 0xa6d189, 0xf2d5cf),
            new Theme("peach", 0x1f1a17, 0x28211d, 0xf3e5d8, 0x7d6b5e, 0xf5a97f, 0x8bd5ca),
            new Theme("nord", 0x2e3440, 0x353c4a, 0xeceff4, 0x6b7385, 0x88c0d0, 0xb48ead),
            new Theme("neon", 0x0e0b1a, 0x161027, 0xece6ff, 0x6c6390, 0xff2a6d, 0x05d9e8),
            new Theme("mono", 0x111111, 0x181818, 0xe6e6e6, 0x5c5c5c, 0xffffff, 0x9a9a9a));

    public static int indexOf(String name) {
        for (int i = 0; i < ALL.size(); i++) if (ALL.get(i).name.equalsIgnoreCase(name)) return i;
        return -1;
    }

    /** Row highlight: a faint wash of the accent over the panel. */
    public int selection() {
        return Color.lerp(panel, accent, 0.16);
    }

    public int border() {
        return Color.lerp(panel, dim, 0.6);
    }

    public int muted() {
        return Color.lerp(dim, fg, 0.5);
    }

    /** A color pushed toward the background, for reflections and unlit elements. */
    public int faint(int c) {
        return Color.lerp(bg, c, 0.3);
    }
}
