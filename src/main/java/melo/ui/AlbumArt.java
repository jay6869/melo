package melo.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import melo.Log;
import melo.source.HttpDownloader;
import melo.term.Color;
import melo.term.Screen;
import melo.term.WcWidth;

/**
 * Cover art in the terminal: images are fetched once, square-cropped, then box-filtered down to the
 * target grid and drawn with '▀' (foreground = upper pixel, background = lower pixel), which gives
 * square pixels at twice the vertical resolution of plain cells.
 */
public final class AlbumArt {
    private static final int MAX = 256, SHARP = 600;
    private static final Pattern YT_ID = Pattern.compile("/vi(?:_webp)?/([\\w-]{11})/");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<String, BufferedImage> images = new ConcurrentHashMap<>();
    private final Map<String, byte[]> pngs = new ConcurrentHashMap<>();
    private final Map<String, Integer> ids = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Set<String> requested = ConcurrentHashMap.newKeySet();
    private final Map<String, int[]> scaled = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> e) {
            return size() > 24;
        }
    };

    public void request(String url, Executor io, Runnable done) {
        if (url == null || !requested.add(url)) return;
        io.execute(() -> {
            try {
                BufferedImage img = load(url);
                if (img != null) {
                    BufferedImage sq = squareCrop(img);
                    pngs.put(url, png(sq.getWidth() > SHARP ? scale(sq, SHARP) : sq));
                    images.put(url, shrink(sq));
                }
            } catch (Exception e) {
                Log.info("artwork " + url + ": " + e);
            } finally {
                done.run();
            }
        });
    }

    public boolean draw(Screen s, String url, int x, int y, int cols, int rows) {
        if (url == null || cols <= 0 || rows <= 0) return false;
        BufferedImage img = images.get(url);
        if (img == null) return false;
        String key = url + '@' + cols + 'x' + rows;
        int[] px = scaled.get(key);
        if (px == null) {
            px = resample(img, cols, rows * 2);
            scaled.put(key, px);
        }
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                s.set(x + c, y + r, '▀', px[(2 * r) * cols + c], px[(2 * r + 1) * cols + c], 0);
        return true;
    }

    /** Average color of the artwork, for tinting. Returns -1 if not loaded. */
    public int average(String url) {
        if (url == null) return -1;
        BufferedImage img = images.get(url);
        if (img == null) return -1;
        return resample(img, 1, 1)[0];
    }

    private BufferedImage load(String url) throws IOException, InterruptedException {
        BufferedImage img;
        if (url.startsWith("file://")) {
            img = embedded(url.substring(7));
        } else {
            img = fetch(url);
            if (img == null) {
                Matcher m = YT_ID.matcher(url);
                if (m.find()) img = fetch("https://i.ytimg.com/vi/" + m.group(1) + "/hqdefault.jpg");
            }
        }
        return img;
    }

    /** Full-resolution cover as PNG, for terminals with an image protocol. Null until loaded. */
    public byte[] png(String url) {
        return url == null ? null : pngs.get(url);
    }

    /** Stable small integer per cover, used as the terminal-side image id. */
    public int idOf(String url) {
        return ids.computeIfAbsent(url, u -> nextId.getAndIncrement());
    }

    private static byte[] png(BufferedImage img) throws IOException {
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(img, 0, 0, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(rgb, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage scale(BufferedImage img, int side) {
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, side, side, resample(img, side, side), 0, side);
        return out;
    }

    private BufferedImage fetch(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", HttpDownloader.USER_AGENT)
                .header("Accept", "image/jpeg,image/png;q=0.9,*/*;q=0.5")
                .build();
        HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) return null;
        return ImageIO.read(new ByteArrayInputStream(r.body()));
    }

    /** Embedded cover art from a local file, via ffmpeg. */
    private static BufferedImage embedded(String path) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-i", path, "-an",
                "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] data = p.getInputStream().readAllBytes();
        p.waitFor();
        return data.length == 0 ? null : ImageIO.read(new ByteArrayInputStream(data));
    }

    /** Square crop; 4:3 YouTube thumbnails are letterboxed 16:9, so cut the bars first. */
    static BufferedImage squareCrop(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int top = 0, height = h;
        if (Math.abs(w * 3 - h * 4) < w / 20) {
            height = w * 9 / 16;
            top = (h - height) / 2;
        }
        int side = Math.min(w, height);
        return img.getSubimage((w - side) / 2, top + (height - side) / 2, side, side);
    }

    private static BufferedImage shrink(BufferedImage img) {
        int side = img.getWidth();
        if (side <= MAX) return img;
        int[] px = resample(img, MAX, MAX);
        BufferedImage out = new BufferedImage(MAX, MAX, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, MAX, MAX, px, 0, MAX);
        return out;
    }

    /** Box-filter downscale to tw×th, returning packed RGB. */
    static int[] resample(BufferedImage img, int tw, int th) {
        int sw = img.getWidth(), sh = img.getHeight();
        int[] src = img.getRGB(0, 0, sw, sh, null, 0, sw);
        int[] out = new int[tw * th];
        for (int ty = 0; ty < th; ty++) {
            int y0 = ty * sh / th, y1 = Math.max(y0 + 1, (ty + 1) * sh / th);
            for (int tx = 0; tx < tw; tx++) {
                int x0 = tx * sw / tw, x1 = Math.max(x0 + 1, (tx + 1) * sw / tw);
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = y0; y < y1 && y < sh; y++)
                    for (int x = x0; x < x1 && x < sw; x++) {
                        int p = src[y * sw + x];
                        r += (p >> 16) & 255;
                        g += (p >> 8) & 255;
                        b += p & 255;
                        n++;
                    }
                n = Math.max(1, n);
                out[ty * tw + tx] = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
            }
        }
        return out;
    }

    private static final String RAMP = " .:-=+*#%@";

    /**
     * Cover art as ASCII: each cell picks a glyph by brightness (contrast-stretched per image) and is
     * drawn in the cover's own color for that spot, on the panel background.
     */
    public boolean drawAscii(Screen s, String url, int x, int y, int cols, int rows, int bg) {
        if (url == null || cols <= 0 || rows <= 0) return false;
        BufferedImage img = images.get(url);
        if (img == null) return false;
        String key = url + "@ascii" + cols + 'x' + rows;
        int[] px = scaled.get(key);
        if (px == null) {
            px = resample(img, cols, rows);
            scaled.put(key, px);
        }
        double lo = 1, hi = 0;
        for (int p : px) {
            double l = luma(p);
            lo = Math.min(lo, l);
            hi = Math.max(hi, l);
        }
        double span = Math.max(0.08, hi - lo);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                int p = px[r * cols + c];
                double l = (luma(p) - lo) / span;
                int idx = (int) Math.round(l * (RAMP.length() - 1));
                char ch = RAMP.charAt(Math.max(0, Math.min(RAMP.length() - 1, idx)));
                s.set(x + c, y + r, ch, brighten(p), bg, 0);
            }
        return true;
    }

    private static double luma(int p) {
        return (0.2126 * Color.r(p) + 0.7152 * Color.g(p) + 0.0722 * Color.b(p)) / 255.0;
    }

    /** Glyphs only show their foreground, so lift dark colors until they read on a dark background. */
    private static int brighten(int p) {
        int m = Math.max(Color.r(p), Math.max(Color.g(p), Color.b(p)));
        if (m >= 150) return p;
        double f = 150.0 / Math.max(m, 30);
        return Color.rgb((int) (Color.r(p) * f), (int) (Color.g(p) * f), (int) (Color.b(p) * f));
    }

    private static final String REELS = "◐◓◑◒";

    /** Big cassette tape for tracks without artwork. Reels turn while {@code spin} advances. */
    public static void cassette(Screen s, int x, int y, int w, int h, String label, double spin, Theme th, int bg) {
        int cw = Math.min(w, 36), ch = 9;
        if (h < ch || cw < 24) {
            miniCassette(s, x + (w - 8) / 2, y + (h - 4) / 2, spin, th, bg);
            return;
        }
        int cx = x + (w - cw) / 2, cy = y + (h - ch) / 2;
        int line = th.muted(), acc = th.accent();
        frame(s, cx, cy, cw, ch, line, bg);
        frame(s, cx + 2, cy + 1, cw - 4, 3, th.border(), bg);
        s.text(cx + 4, cy + 2, "♪ " + WcWidth.fit(label == null ? "melo mixtape" : label, cw - 10), acc, bg, Screen.BOLD, cx + cw - 3);
        int reelW = 7, gap = cw - 2 * reelW - 8;
        int r1 = cx + 4, r2 = r1 + reelW + gap;
        char glyph = REELS.charAt((int) (spin * 2) % REELS.length());
        for (int rx : new int[]{r1, r2}) {
            frame(s, rx, cy + 4, reelW, 3, line, bg);
            s.set(rx + 3, cy + 5, glyph, acc, bg, Screen.BOLD);
        }
        for (int i = r1 + reelW; i < r2; i++) s.set(i, cy + 5, '═', th.dim(), bg, 0);
        s.set(cx + 3, cy + 7, '╱', line, bg, 0);
        for (int i = cx + 4; i < cx + cw - 4; i++) s.set(i, cy + 7, '─', line, bg, 0);
        s.set(cx + cw - 4, cy + 7, '╲', line, bg, 0);
        s.set(cx + cw / 2 - 1, cy + 7, '●', th.dim(), bg, 0);
        s.set(cx + cw / 2 + 1, cy + 7, '●', th.dim(), bg, 0);
    }

    /** 8×4 cassette for the player bar. */
    public static void miniCassette(Screen s, int x, int y, double spin, Theme th, int bg) {
        char glyph = REELS.charAt((int) (spin * 2) % REELS.length());
        int line = th.muted();
        frame(s, x, y, 8, 4, line, bg);
        s.set(x + 2, y + 1, glyph, th.accent(), bg, Screen.BOLD);
        s.set(x + 3, y + 1, '─', th.dim(), bg, 0);
        s.set(x + 4, y + 1, '─', th.dim(), bg, 0);
        s.set(x + 5, y + 1, glyph, th.accent(), bg, Screen.BOLD);
        s.text(x + 2, y + 2, "▔▔▔▔", th.dim(), bg, 0, x + 7);
    }

    private static void frame(Screen s, int x, int y, int w, int h, int color, int bg) {
        for (int i = 0; i < w; i++) {
            s.set(x + i, y, i == 0 ? '╭' : i == w - 1 ? '╮' : '─', color, bg, 0);
            s.set(x + i, y + h - 1, i == 0 ? '╰' : i == w - 1 ? '╯' : '─', color, bg, 0);
        }
        for (int j = 1; j < h - 1; j++) {
            s.set(x, y + j, '│', color, bg, 0);
            s.set(x + w - 1, y + j, '│', color, bg, 0);
            for (int i = 1; i < w - 1; i++) s.set(x + i, y + j, ' ', color, bg, 0);
        }
    }
}
