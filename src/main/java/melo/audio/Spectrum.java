package melo.audio;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Real-time analysis of what's currently audible: a 4096-point FFT once per frame, then log-spaced
 * bands (any count, cached per count) with attack/decay smoothing, falling peaks, an adaptive ceiling
 * so quiet and loud tracks both fill the display, and a simple bass-onset "pulse" for beat effects.
 */
public final class Spectrum {
    public static final int N = 4096;
    private static final double LO = 32, RANGE = 54;

    private final int rate;
    private final float[] window = new float[N], re = new float[N], im = new float[N];
    private final float[] cosT = new float[N / 2], sinT = new float[N / 2];
    private final int[] rev = new int[N];
    private final float[] left = new float[N], right = new float[N];
    private final float[] db = new float[N / 2 + 2];
    private final Map<Integer, Bands> sets = new HashMap<>();
    private long frame;
    private float dt = 1 / 30f;
    private boolean live;
    private float ceil = -30f, bassAvg;
    private double sinceBeat;
    private Demo demo;

    /** 0..1, jumps to 1 on a bass hit and decays quickly. */
    public float pulse;
    /** 0..1 low-end energy. */
    public float bass;
    /** RMS of the current window. */
    public float level;

    public Spectrum(int rate) {
        this.rate = rate;
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
            rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
        for (int i = 0; i < N / 2; i++) {
            cosT[i] = (float) Math.cos(2 * Math.PI * i / N);
            sinT[i] = (float) Math.sin(2 * Math.PI * i / N);
        }
    }

    /** Feeds a synthetic signal instead of the player (headless snapshots). */
    public void demo(boolean on) {
        demo = on ? new Demo() : null;
    }

    public void update(Player player, double frameDt) {
        dt = (float) Math.max(1e-3, Math.min(0.1, frameDt));
        frame++;
        live = demo != null ? demo.fill(left, right, rate, dt) : player.snapshot(left, right, N) > 0;

        double sum = 0;
        for (int i = 0; i < N; i++) {
            float m = (left[i] + right[i]) * 0.5f;
            sum += m * m;
            re[i] = m * window[i];
            im[i] = 0;
        }
        level = (float) Math.sqrt(sum / N);
        fft();

        float max = -200f, norm = N / 4f;
        for (int k = 1; k <= N / 2; k++) {
            int kk = Math.min(k, N - 1);
            double mag = Math.sqrt(re[kk] * re[kk] + im[kk] * im[kk]) / norm;
            double f = (double) k * rate / N;
            // +3.5 dB/octave tilt around 1 kHz so highs aren't buried under the bass
            float d = (float) (20 * Math.log10(mag + 1e-9) + 3.5 * Math.log(f / 1000) / Math.log(2));
            db[k] = d;
            if (f >= 30 && f <= 16000 && d > max) max = d;
        }
        db[0] = db[1];
        db[N / 2 + 1] = db[N / 2];
        if (live) ceil = Math.max(max, ceil - dt * 6);
        ceil = Math.max(ceil, -50f);

        float bsum = 0;
        int bc = 0;
        for (int k = bin(40); k <= bin(150); k++) {
            bsum += norm(db[k]);
            bc++;
        }
        bass = live && bc > 0 ? bsum / bc : bass * 0.9f;
        bassAvg += (bass - bassAvg) * Math.min(1f, dt * 1.5f);
        sinceBeat += dt;
        if (live && bass > bassAvg * 1.18f + 0.04f && bass > 0.35f && sinceBeat > 0.2) {
            pulse = 1f;
            sinceBeat = 0;
        }
        pulse *= (float) Math.pow(0.5, dt / 0.12);
    }

    public Bands bands(int n) {
        n = Math.max(1, n);
        Bands b = sets.get(n);
        if (b == null) {
            if (sets.size() > 16) sets.clear();
            b = new Bands(n);
            sets.put(n, b);
        }
        if (b.frame != frame) {
            b.update();
            b.frame = frame;
        }
        return b;
    }

    public float[] left() {
        return left;
    }

    public float[] right() {
        return right;
    }

    public boolean live() {
        return live;
    }

    /** Whether anything is still moving (playing, or bars still falling after a pause). */
    public boolean active() {
        if (live || pulse > 0.01f) return true;
        for (Bands b : sets.values()) if (b.max > 0.005f) return true;
        return false;
    }

    private int bin(double f) {
        return (int) Math.max(1, Math.min(N / 2, Math.round(f * N / rate)));
    }

    private float norm(float d) {
        double v = (d - (ceil - RANGE)) / RANGE;
        return (float) (v < 0 ? 0 : v > 1 ? 1 : v);
    }

    private float dbAt(double k) {
        int i = (int) k;
        if (i >= N / 2) return db[N / 2];
        float fr = (float) (k - i);
        return db[i] + (db[i + 1] - db[i]) * fr;
    }

    private void fft() {
        for (int i = 0; i < N; i++) {
            int j = rev[i];
            if (j > i) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int size = 2; size <= N; size <<= 1) {
            int half = size >> 1, step = N / size;
            for (int i = 0; i < N; i += size) {
                for (int k = 0; k < half; k++) {
                    float wr = cosT[k * step], wi = -sinT[k * step];
                    int a = i + k, b = a + half;
                    float tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }

    public final class Bands {
        public final float[] v, peak;
        private final float[] vel, hold;
        private final double[] k0, k1, kc;
        private long frame = -1;
        private float max;

        Bands(int n) {
            v = new float[n];
            peak = new float[n];
            vel = new float[n];
            hold = new float[n];
            k0 = new double[n];
            k1 = new double[n];
            kc = new double[n];
            double hi = Math.min(16000, rate / 2.0);
            for (int i = 0; i < n; i++) {
                double f0 = LO * Math.pow(hi / LO, (double) i / n);
                double f1 = LO * Math.pow(hi / LO, (double) (i + 1) / n);
                k0[i] = f0 * N / rate;
                k1[i] = f1 * N / rate;
                kc[i] = Math.sqrt(f0 * f1) * N / rate;
            }
        }

        public int size() {
            return v.length;
        }

        void update() {
            float rise = (float) (1 - Math.pow(0.3, dt * 30)), fall = (float) (1 - Math.pow(0.8, dt * 30));
            max = 0;
            for (int i = 0; i < v.length; i++) {
                float d;
                if (k1[i] - k0[i] < 2) {
                    d = dbAt(kc[i]);
                } else {
                    d = -200f;
                    int a = (int) Math.ceil(k0[i]), b = (int) Math.floor(k1[i]);
                    for (int k = a; k <= b && k <= N / 2; k++) d = Math.max(d, db[k]);
                }
                float target = live ? (float) Math.pow(norm(d), 1.35) : 0f;
                v[i] += (target - v[i]) * (target > v[i] ? rise : fall);
                if (v[i] >= peak[i]) {
                    peak[i] = v[i];
                    vel[i] = 0;
                    hold[i] = 0.35f;
                } else if ((hold[i] -= dt) <= 0) {
                    vel[i] += 1.8f * dt;
                    peak[i] = Math.max(v[i], peak[i] - vel[i] * dt);
                }
                max = Math.max(max, Math.max(v[i], peak[i]));
            }
        }
    }

    private static final class Demo {
        private final Random rnd = new Random(7);
        private final double[] freqs = {55, 110, 220, 440, 880, 1760, 3520, 7040};
        private double t;

        boolean fill(float[] l, float[] r, int rate, float dt) {
            t += dt;
            for (int i = 0; i < l.length; i++) {
                double tt = t + (i - l.length) / (double) rate, s = 0;
                for (int j = 0; j < freqs.length; j++)
                    s += Math.sin(2 * Math.PI * freqs[j] * tt) * (0.5 + 0.5 * Math.sin(tt * (1.3 + j * 0.7) + j)) / (1 + j * 0.6);
                s = s * 0.25 + (rnd.nextDouble() - 0.5) * 0.02;
                l[i] = (float) s;
                r[i] = (float) (s * 0.85 + Math.sin(2 * Math.PI * 330 * tt) * 0.06);
            }
            return true;
        }
    }
}
