package melo.ui;

import melo.audio.Spectrum;
import melo.term.Braille;
import melo.term.Screen;

/** All visualizer styles, in flat theme colors. Each draws into a rectangle from the live {@link Spectrum}. */
public final class Visualizer {
    public enum Mode {
        BARS("bars"), LED("led"), MIRROR("mirror"), RAIN("ascii rain"), WAVE("wave"), RADIAL("radial"), SCOPE("scope");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private static final String EIGHTHS = " ▁▂▃▄▅▆▇█";
    private static final String DENSITY = " .:-=+*#%@";

    private Braille braille;
    private float[][] history;
    private int histW = -1, histH = -1, head;
    private double histAcc;
    private float waveGain = 1f, scopeGain = 1f;

    public void draw(Screen s, int x, int y, int w, int h, Mode mode, Spectrum sp, Theme th, double t, double dt, int bg) {
        if (w < 4 || h < 2) return;
        switch (mode) {
            case BARS -> bars(s, x, y, w, h, sp, th, bg);
            case LED -> led(s, x, y, w, h, sp, th, bg);
            case MIRROR -> mirror(s, x, y, w, h, sp, th, bg);
            case RAIN -> rain(s, x, y, w, h, sp, th, dt, bg);
            case WAVE -> wave(s, x, y, w, h, sp, th, bg);
            case RADIAL -> radial(s, x, y, w, h, sp, th, t, bg);
            case SCOPE -> scope(s, x, y, w, h, sp, th, bg);
        }
    }

    private void bars(Screen s, int x, int y, int w, int h, Spectrum sp, Theme th, int bg) {
        int bw = w >= 110 ? 3 : w >= 48 ? 2 : 1;
        int gap = 1;
        int n = Math.max(1, (w + gap) / (bw + gap));
        int ox = x + (w - (n * (bw + gap) - gap)) / 2;
        Spectrum.Bands b = sp.bands(n);
        for (int i = 0; i < n; i++) {
            int units = Math.round(b.v[i] * h * 8);
            int full = units / 8, part = units % 8;
            int bx = ox + i * (bw + gap);
            for (int r = 0; r < h; r++) {
                char ch;
                if (r < full) ch = '█';
                else if (r == full && part > 0) ch = EIGHTHS.charAt(part);
                else break;
                for (int k = 0; k < bw; k++) s.set(bx + k, y + h - 1 - r, ch, th.accent(), bg, 0);
            }
            double pk = b.peak[i] * h;
            int pr = (int) pk;
            if (b.peak[i] > 0.02 && pr < h && pr > full - (part > 0 ? 0 : 1)) {
                char cap = pk - pr >= 0.5 ? '▔' : '▁';
                for (int k = 0; k < bw; k++) s.set(bx + k, y + h - 1 - pr, cap, th.accent2(), bg, 0);
            }
        }
    }

    /** A dot-matrix display: unlit dots stay faintly visible, lit ones glow in the accent. */
    private void led(Screen s, int x, int y, int w, int h, Spectrum sp, Theme th, int bg) {
        int n = Math.max(1, (w + 1) / 2);
        int ox = x + (w - (n * 2 - 1)) / 2;
        Spectrum.Bands b = sp.bands(n);
        int off = th.faint(th.dim());
        for (int i = 0; i < n; i++) {
            int lit = Math.round(b.v[i] * h);
            int peak = Math.min(h - 1, (int) (b.peak[i] * h));
            for (int r = 0; r < h; r++) {
                int cy = y + h - 1 - r;
                if (r < lit) s.set(ox + i * 2, cy, '■', th.accent(), bg, 0);
                else if (r == peak && b.peak[i] > 0.03) s.set(ox + i * 2, cy, '■', th.accent2(), bg, 0);
                else s.set(ox + i * 2, cy, '·', off, bg, 0);
            }
        }
    }

    /** Low frequencies in the middle, spreading out to both edges, with a soft reflection below. */
    private void mirror(Screen s, int x, int y, int w, int h, Spectrum sp, Theme th, int bg) {
        int bw = w >= 90 ? 2 : 1, gap = bw > 1 ? 1 : 0;
        int n = Math.max(2, (w + gap) / (bw + gap));
        int ox = x + (w - (n * (bw + gap) - gap)) / 2;
        int half = (n + 1) / 2;
        Spectrum.Bands b = sp.bands(half);
        int upRows = Math.max(1, (h * 3) / 5), dnRows = h - upRows;
        int mid = y + upRows;
        int soft = th.faint(th.accent());
        for (int i = 0; i < n; i++) {
            float v = b.v[Math.min(half - 1, (int) Math.abs(i - (n - 1) / 2.0))];
            int bx = ox + i * (bw + gap);
            int uu = Math.round(v * upRows * 2);
            for (int r = 0; r < upRows && uu - 2 * r > 0; r++) {
                char ch = uu - 2 * r >= 2 ? '█' : '▄';
                for (int k = 0; k < bw; k++) s.set(bx + k, mid - 1 - r, ch, th.accent(), bg, 0);
            }
            int du = Math.round(v * dnRows * 2 * 0.8f);
            for (int r = 0; r < dnRows && du - 2 * r > 0; r++) {
                char ch = du - 2 * r >= 2 ? '█' : '▀';
                for (int k = 0; k < bw; k++) s.set(bx + k, mid + r, ch, soft, bg, 0);
            }
        }
    }

    /** ASCII spectrogram: frequency across, time falling down, loudness as character density. */
    private void rain(Screen s, int x, int y, int w, int h, Spectrum sp, Theme th, double dt, int bg) {
        if (history == null || histW != w || histH != h) {
            history = new float[h][w];
            histW = w;
            histH = h;
            head = 0;
        }
        Spectrum.Bands b = sp.bands(w);
        histAcc += dt;
        if (histAcc >= 1 / 14.0) {
            histAcc = Math.min(histAcc - 1 / 14.0, 0.1);
            head = (head + h - 1) % h;
            System.arraycopy(b.v, 0, history[head], 0, w);
        }
        for (int r = 0; r < h; r++) {
            float[] row = r == 0 ? b.v : history[(head + r) % h];
            int color = r == 0 ? th.accent2() : th.accent();
            for (int c = 0; c < w; c++) {
                int idx = Math.min(DENSITY.length() - 1, Math.round(row[c] * (DENSITY.length() - 1) * 1.15f));
                char ch = DENSITY.charAt(idx);
                if (ch != ' ') s.set(x + c, y + r, ch, color, bg, 0);
            }
        }
    }

    private Braille braille(int w, int h) {
        if (braille == null || braille.cols != w || braille.rows != h) braille = new Braille(w, h);
        braille.clear();
        return braille;
    }

    /** Triggered oscilloscope of the mono mix. */
    private void wave(Screen s, int x, int y, int w, int h, Spectrum sp, Theme th, int bg) {
        Braille br = braille(w, h);
        float[] l = sp.left(), r = sp.right();
        int n = l.length, pw = br.pw(), ph = br.ph();
        int span = Math.min(n / 2, 1400);
        int trig = n - span - 1;
        for (int i = n - span - 1; i > n - span - 1200 && i > 1; i--) {
            if (l[i - 1] + r[i - 1] < 0 && l[i] + r[i] >= 0) {
                trig = i;
                break;
            }
        }
        float peak = 0.02f;
        for (int i = trig; i < trig + span; i++) peak = Math.max(peak, Math.abs((l[i] + r[i]) * 0.5f));
        waveGain += (Math.min(30f, 0.9f / peak) - waveGain) * 0.15f;
        for (int px = 0; px < pw; px += 4) br.dot(px, ph / 2, th.faint(th.dim()));
        int prev = -1;
        for (int px = 0; px < pw; px++) {
            int i = trig + (int) ((long) px * span / pw);
            float m = (l[i] + r[i]) * 0.5f * waveGain;
            int py = (int) Math.round((ph - 1) / 2.0 * (1 - Math.max(-1, Math.min(1, m))));
            if (prev >= 0) br.line(px - 1, prev, px, py, th.accent());
            else br.dot(px, py, th.accent());
            prev = py;
        }
        br.render(s, x, y, bg);
    }

    /** Spectrum as spokes around a ring that breathes with the bass. */
    private void radial(Screen s, int x, int y, int w, int h, Spectrum sp, Theme th, double t, int bg) {
        Braille br = braille(w, h);
        int pw = br.pw(), ph = br.ph();
        double cx = pw / 2.0, cy = ph / 2.0;
        double radius = Math.min(pw, ph) / 2.0 - 1;
        double r0 = radius * (0.36 + 0.06 * sp.pulse);
        int half = (int) Math.max(18, Math.min(90, Math.PI * r0 / 1.7));
        int spokes = half * 2;
        Spectrum.Bands b = sp.bands(half);
        double rot = t * 0.25;
        for (int j = 0; j < spokes; j++) {
            float v = b.v[j < half ? j : spokes - 1 - j];
            double a = rot + j * 2 * Math.PI / spokes - Math.PI / 2;
            double ca = Math.cos(a), sa = Math.sin(a);
            double r1 = r0 + 1.5 + v * (radius - r0 - 1.5);
            br.line((int) Math.round(cx + ca * (r0 + 1.5)), (int) Math.round(cy + sa * (r0 + 1.5)),
                    (int) Math.round(cx + ca * r1), (int) Math.round(cy + sa * r1), th.accent());
        }
        int steps = (int) Math.max(24, 2 * Math.PI * r0 * 1.2);
        for (int k = 0; k < steps; k++) {
            double a = k * 2 * Math.PI / steps;
            br.dot((int) Math.round(cx + Math.cos(a) * r0), (int) Math.round(cy + Math.sin(a) * r0), th.accent2());
        }
        br.render(s, x, y, bg);
        String heart = sp.pulse > 0.5f ? "♥" : "♡";
        s.text(x + w / 2, y + h / 2, heart, th.accent(), bg, Screen.BOLD, x + w);
    }

    /** Goniometer: mid on the vertical axis, side on the horizontal; stereo width becomes shape. */
    private void scope(Screen s, int x, int y, int w, int h, Spectrum sp, Theme th, int bg) {
        Braille br = braille(w, h);
        int pw = br.pw(), ph = br.ph();
        double cx = (pw - 1) / 2.0, cy = (ph - 1) / 2.0, radius = Math.min(pw, ph) / 2.0 - 1;
        float[] l = sp.left(), r = sp.right();
        int n = l.length, count = 1536;
        float peak = 0.02f;
        for (int i = n - count; i < n; i++) peak = Math.max(peak, Math.max(Math.abs(l[i] + r[i]), Math.abs(l[i] - r[i])) * 0.5f);
        scopeGain += (Math.min(40f, 0.92f / peak) - scopeGain) * 0.15f;
        int guide = th.faint(th.dim());
        for (int k = -(int) radius; k <= radius; k += 3) {
            br.dot((int) Math.round(cx + k), (int) Math.round(cy), guide);
            br.dot((int) Math.round(cx), (int) Math.round(cy + k), guide);
        }
        for (int i = n - count; i < n; i++) {
            double mid = (l[i] + r[i]) * 0.5 * scopeGain, side = (l[i] - r[i]) * 0.5 * scopeGain;
            br.dot((int) Math.round(cx + side * radius), (int) Math.round(cy - mid * radius), th.accent());
        }
        br.render(s, x, y, bg);
    }
}
