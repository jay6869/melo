package melo.term;

/** 24-bit colors packed as 0xRRGGBB. Negative values are terminal-default sentinels and pass through. */
public final class Color {
    private Color() {
    }

    public static int rgb(int r, int g, int b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    public static int r(int c) {
        return (c >> 16) & 255;
    }

    public static int g(int c) {
        return (c >> 8) & 255;
    }

    public static int b(int c) {
        return c & 255;
    }

    public static int lerp(int a, int b, double t) {
        if (a < 0) return b;
        if (b < 0) return a;
        if (t <= 0) return a;
        if (t >= 1) return b;
        return rgb(
                (int) Math.round(r(a) + (r(b) - r(a)) * t),
                (int) Math.round(g(a) + (g(b) - g(a)) * t),
                (int) Math.round(b(a) + (b(b) - b(a)) * t));
    }

    public static int scale(int c, double f) {
        if (c < 0) return c;
        return rgb((int) (r(c) * f), (int) (g(c) * f), (int) (b(c) * f));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
