package melo.term;

import java.util.Arrays;

/** A dot canvas at 2×4 sub-cell resolution using Unicode braille patterns. Dots come out roughly square. */
public final class Braille {
    private static final int[][] BITS = {{0x01, 0x02, 0x04, 0x40}, {0x08, 0x10, 0x20, 0x80}};

    public final int cols, rows;
    private final int[] dots, color;

    public Braille(int cols, int rows) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        dots = new int[this.cols * this.rows];
        color = new int[this.cols * this.rows];
    }

    public int pw() {
        return cols * 2;
    }

    public int ph() {
        return rows * 4;
    }

    public void clear() {
        Arrays.fill(dots, 0);
    }

    public void dot(int x, int y, int c) {
        if (x < 0 || y < 0 || x >= cols * 2 || y >= rows * 4) return;
        int i = (y >> 2) * cols + (x >> 1);
        dots[i] |= BITS[x & 1][y & 3];
        color[i] = c;
    }

    public void line(int x0, int y0, int x1, int y1, int c) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        for (int guard = 0; guard < 4096; guard++) {
            dot(x0, y0, c);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    public void render(Screen s, int ox, int oy, int bg) {
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                if (dots[i] != 0) s.set(ox + c, oy + r, 0x2800 | dots[i], color[i], bg, 0);
            }
    }
}
