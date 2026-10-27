package melo.term;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Double-buffered cell grid. Each frame is drawn in full, then {@link #flush} emits only the cells that
 * changed, as truecolor SGR, inside a synchronized-update block so the terminal never shows a torn frame.
 */
public final class Screen {
    /** Terminal default color. */
    public static final int DEFAULT = -1;
    /** Leave the cell's current color untouched. */
    public static final int KEEP = -2;

    public static final int BOLD = 1, DIM = 2, ITALIC = 4, UNDERLINE = 8;

    private int w, h;
    private int[] ch, fg, bg;
    private byte[] at;
    private int[] pch, pfg, pbg;
    private byte[] pat;
    private boolean full = true;
    private final StringBuilder out = new StringBuilder(1 << 16);

    /** An image drawn over a cell rectangle via the kitty graphics protocol. */
    private record Img(int id, int pid, int x, int y, int cols, int rows, byte[] png) {
        long key() {
            return ((long) id << 16) | pid;
        }

        boolean sameSpot(Img o) {
            return o != null && x == o.x && y == o.y && cols == o.cols && rows == o.rows;
        }
    }

    private Map<Long, Img> frameImgs = new HashMap<>(), shownImgs = new HashMap<>();
    private final Set<Integer> sentImgs = new HashSet<>();

    public Screen(int w, int h) {
        resize(w, h);
    }

    public void resize(int w, int h) {
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        int n = this.w * this.h;
        ch = new int[n];
        fg = new int[n];
        bg = new int[n];
        at = new byte[n];
        pch = new int[n];
        pfg = new int[n];
        pbg = new int[n];
        pat = new byte[n];
        full = true;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    public void invalidate() {
        full = true;
    }

    /**
     * Shows a PNG over the given cells this frame (kitty graphics protocol). {@code pid} distinguishes
     * several placements of the same image. The image data is sent to the terminal only once.
     */
    public void image(int id, int pid, byte[] png, int x, int y, int cols, int rows) {
        if (cols <= 0 || rows <= 0) return;
        Img im = new Img(id, pid, x, y, cols, rows, png);
        frameImgs.put(im.key(), im);
    }

    public void clear(int background) {
        frameImgs = new HashMap<>();
        Arrays.fill(ch, ' ');
        Arrays.fill(fg, DEFAULT);
        Arrays.fill(bg, background);
        Arrays.fill(at, (byte) 0);
    }

    public int bgAt(int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return DEFAULT;
        return bg[y * w + x];
    }

    public int chAt(int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? ' ' : ch[y * w + x];
    }

    public int fgAt(int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? DEFAULT : fg[y * w + x];
    }

    public int attrAt(int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? 0 : at[y * w + x];
    }

    public void set(int x, int y, int cp, int f, int b, int a) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int i = y * w + x;
        int old = ch[i];
        if (old == 0 && x > 0) ch[i - 1] = ' ';
        else if (x + 1 < w && ch[i + 1] == 0 && WcWidth.of(old) == 2) ch[i + 1] = ' ';
        ch[i] = cp;
        if (f != KEEP) fg[i] = f;
        if (b != KEEP) bg[i] = b;
        at[i] = (byte) a;
    }

    public void fill(int x, int y, int fw, int fh, int cp, int f, int b) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(w, x + fw), y1 = Math.min(h, y + fh);
        for (int yy = y0; yy < y1; yy++)
            for (int xx = x0; xx < x1; xx++) set(xx, yy, cp, f, b, 0);
    }

    /** Draws text clipped to [x, maxX). Returns the column after the last drawn cell. */
    public int text(int x, int y, String s, int f, int b, int a, int maxX) {
        if (s == null || y < 0 || y >= h) return x;
        maxX = Math.min(maxX, w);
        for (int k = 0; k < s.length(); ) {
            int cp = s.codePointAt(k);
            k += Character.charCount(cp);
            int cw = WcWidth.of(cp);
            if (cw <= 0) continue;
            if (x + cw > maxX) break;
            if (x >= 0) {
                set(x, y, cp, f, b, a);
                if (cw == 2 && x + 1 < w) {
                    int i = y * w + x + 1;
                    ch[i] = 0;
                    if (f != KEEP) fg[i] = f;
                    if (b != KEEP) bg[i] = b;
                    at[i] = (byte) a;
                }
            }
            x += cw;
        }
        return x;
    }

    public int text(int x, int y, String s, int f, int b) {
        return text(x, y, s, f, b, 0, w);
    }

    public void flush(OutputStream os) throws IOException {
        StringBuilder sb = out;
        sb.setLength(0);
        sb.append("\033[?2026h");
        int cf = Integer.MIN_VALUE, cb = Integer.MIN_VALUE, ca = -1;
        int cx = -1, cy = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int c = ch[i];
                if (c == 0) continue;
                boolean wide = WcWidth.of(c) == 2;
                if (!full && c == pch[i] && fg[i] == pfg[i] && bg[i] == pbg[i] && at[i] == pat[i]
                        && (!wide || x + 1 >= w || ch[i + 1] == pch[i + 1])) continue;
                if (cx != x || cy != y) sb.append("\033[").append(y + 1).append(';').append(x + 1).append('H');
                if (at[i] != ca) {
                    sb.append("\033[0");
                    int a = at[i];
                    if ((a & BOLD) != 0) sb.append(";1");
                    if ((a & DIM) != 0) sb.append(";2");
                    if ((a & ITALIC) != 0) sb.append(";3");
                    if ((a & UNDERLINE) != 0) sb.append(";4");
                    sb.append('m');
                    ca = a;
                    cf = cb = Integer.MIN_VALUE;
                }
                if (fg[i] != cf && (c != ' ' || (at[i] & UNDERLINE) != 0)) {
                    sgr(sb, fg[i], 38);
                    cf = fg[i];
                }
                if (bg[i] != cb) {
                    sgr(sb, bg[i], 48);
                    cb = bg[i];
                }
                sb.appendCodePoint(c);
                cx = x + (wide ? 2 : 1);
                cy = y;
            }
        }
        images(sb);
        sb.append("\033[0m\033[?2026l");
        System.arraycopy(ch, 0, pch, 0, ch.length);
        System.arraycopy(fg, 0, pfg, 0, fg.length);
        System.arraycopy(bg, 0, pbg, 0, bg.length);
        System.arraycopy(at, 0, pat, 0, at.length);
        full = false;
        os.write(sb.toString().getBytes(UTF_8));
        os.flush();
    }

    private void images(StringBuilder sb) {
        if (frameImgs.isEmpty() && shownImgs.isEmpty()) return;
        if (full) {
            sb.append("\033_Ga=d,d=a,q=2\033\\");
            shownImgs = new HashMap<>();
        }
        for (Img old : shownImgs.values()) {
            if (!frameImgs.containsKey(old.key()))
                sb.append("\033_Ga=d,d=i,i=").append(old.id).append(",p=").append(old.pid).append(",q=2\033\\");
        }
        for (Img im : frameImgs.values()) {
            if (im.sameSpot(shownImgs.get(im.key()))) continue;
            if (sentImgs.add(im.id)) transmit(sb, im);
            sb.append("\033[").append(im.y + 1).append(';').append(im.x + 1).append('H');
            sb.append("\033_Ga=p,i=").append(im.id).append(",p=").append(im.pid)
                    .append(",c=").append(im.cols).append(",r=").append(im.rows).append(",C=1,q=2\033\\");
        }
        shownImgs = frameImgs;
    }

    private static void transmit(StringBuilder sb, Img im) {
        String b64 = Base64.getEncoder().encodeToString(im.png);
        for (int i = 0; i < b64.length(); i += 4096) {
            boolean first = i == 0, last = i + 4096 >= b64.length();
            sb.append("\033_G");
            if (first) sb.append("a=t,f=100,t=d,i=").append(im.id).append(",q=2,");
            sb.append("m=").append(last ? 0 : 1).append(';');
            sb.append(b64, i, Math.min(b64.length(), i + 4096)).append("\033\\");
        }
    }

    private static void sgr(StringBuilder sb, int color, int base) {
        if (color < 0) {
            sb.append("\033[").append(base + 1).append('m');
        } else {
            sb.append("\033[").append(base).append(";2;")
                    .append(Color.r(color)).append(';').append(Color.g(color)).append(';').append(Color.b(color)).append('m');
        }
    }

    /** Plain-text dump of the current frame (used by the headless snapshot mode). */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = ch[y * w + x];
                if (c != 0) sb.appendCodePoint(c);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
