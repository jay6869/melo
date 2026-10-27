package melo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import melo.audio.Player;
import melo.audio.Spectrum;
import melo.source.Fmt;
import melo.source.Item;
import melo.source.MusicSource;
import melo.source.Playlist;
import melo.source.Track;
import melo.term.Screen;
import melo.term.WcWidth;
import melo.ui.AlbumArt;
import melo.ui.BigDigits;
import melo.ui.Cat;
import melo.ui.Logo;
import melo.ui.Theme;

/** Draws the whole interface from {@link App} state, every frame. Also records click targets. */
final class Ui {
    interface HitAction {
        void on(int mx, int my, boolean drag);
    }

    record Hit(int x, int y, int w, int h, HitAction action) {
        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private static final String EIGHTHS = " ▁▂▃▄▅▆▇█";
    private static final String SPIN = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
    private static final int ERROR = 0xff6b81;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final App app;
    final List<Hit> hits = new ArrayList<>();
    private Screen s;
    private Theme th;
    private double t;
    private int bg, pb;

    Ui(App app) {
        this.app = app;
    }

    void draw(Screen screen) {
        s = screen;
        th = app.theme();
        t = app.time();
        hits.clear();
        bg = app.transparent ? Screen.DEFAULT : th.bg();
        pb = app.transparent ? Screen.DEFAULT : th.panel();
        int W = s.width(), H = s.height();
        s.clear(bg);
        if (W < 60 || H < 18) {
            tooSmall(W, H);
            return;
        }
        if (app.splash) {
            splash(W, H);
            return;
        }
        if (app.zen) {
            zen(W, H);
            return;
        }
        header(W);
        int playerH = 6, playerY = H - 1 - playerH;
        playerBar(0, playerY, W, playerH);
        footer(H - 1, W);
        int bodyY = 3, bodyH = playerY - bodyY - 1;
        if (app.tab == App.Tab.NOW) {
            nowView(1, bodyY, W - 2, bodyH);
        } else {
            boolean side = W >= 110;
            int sideW = side ? Math.max(38, Math.min(64, W * 34 / 100)) : 0;
            int mainW = W - sideW - 2 - (side ? 2 : 0), mainH = bodyH;
            if (!side && bodyH >= 20) {
                int vh = Math.min(9, bodyH / 3);
                vizStrip(1, bodyY + bodyH - vh, W - 2, vh);
                mainH -= vh + 1;
            }
            switch (app.tab) {
                case SEARCH -> searchView(1, bodyY, mainW, mainH);
                case QUEUE -> queueView(1, bodyY, mainW, mainH);
                case LIBRARY -> libraryView(1, bodyY, mainW, mainH);
                default -> {
                }
            }
            if (side) sidePanel(W - sideW - 1, bodyY, sideW, bodyH);
        }
        toast(W, playerY);
        if (app.help) help(W, H);
    }

    // ───────────────────────────── chrome ─────────────────────────────

    private void header(int W) {
        for (int i = 0; i < W; i++) s.set(i, 1, '─', th.border(), bg, 0);
        int x = s.text(2, 0, "♫ melo", th.accent(), bg, Screen.BOLD, W);
        x += 2;
        Spectrum.Bands b = app.spectrum.bands(5);
        for (int i = 0; i < 5; i++) {
            float v = b.v[i];
            s.set(x + i, 0, EIGHTHS.charAt(Math.max(1, Math.round(v * 8))), v > 0.02 ? th.accent2() : th.border(), bg, 0);
        }
        x += 9;

        App.Tab[] tabs = App.Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            App.Tab tb = tabs[i];
            boolean active = app.tab == tb;
            String label = (W < 110 && tb == App.Tab.NOW ? "now" : tb.label.toLowerCase())
                    + (tb == App.Tab.QUEUE && !app.queue.isEmpty() ? " " + app.queue.size() : "");
            int start = x;
            x = s.text(x, 0, String.valueOf(i + 1), active ? th.accent() : th.dim(), bg, 0, W - 18);
            x = s.text(x + 1, 0, label, active ? th.fg() : th.muted(), bg, active ? Screen.BOLD : 0, W - 18);
            if (active) for (int k = start; k < x; k++) s.set(k, 1, '━', th.accent(), bg, 0);
            App.Tab target = tb;
            hits.add(new Hit(start, 0, x - start, 2, (mx, my, d) -> app.selectTab(target)));
            x += 4;
        }

        int rx = W - 2;
        String clock = LocalTime.now().format(CLOCK);
        rx -= clock.length();
        s.text(rx, 0, clock, th.dim(), bg);
        rx -= 3;
        rx = icon(rx, "∞", app.autoplay, app::toggleAutoplay);
        rx = icon(rx, app.repeat == App.Repeat.ONE ? "↻1" : "↻", app.repeat != App.Repeat.OFF, app::cycleRepeat);
        icon(rx, "⇄", app.shuffle, app::toggleShuffle);

    }

    private int icon(int rx, String glyph, boolean on, Runnable action) {
        int w = WcWidth.width(glyph);
        rx -= w;
        s.text(rx, 0, glyph, on ? th.accent() : th.border(), bg, on ? Screen.BOLD : 0, rx + w);
        hits.add(new Hit(rx, 0, w, 1, (mx, my, d) -> {
            if (!d) action.run();
        }));
        return rx - 2;
    }

    private void footer(int y, int W) {
        List<String[]> hints = new ArrayList<>();
        if (app.typing) {
            add(hints, "enter", "search", "tab", "filter", "↓", "results", "esc", "done", "^u", "clear");
        } else {
            switch (app.tab) {
                case SEARCH -> add(hints, "/", "search", "⏎", "play", "a", "queue", "r", "radio", "f", "like");
                case QUEUE -> add(hints, "⏎", "play", "d", "remove", "J K", "move", "C", "clear");
                case LIBRARY -> add(hints, "⏎", "play", "[ ]", "liked/history", "a", "queue", "f", "like");
                case NOW -> add(hints, "v", "visual", "i", "cover", "z", "zen", "f", "like");
            }
            add(hints, "␣", "pause", "n p", "skip", "← →", "seek", "t", "theme", "?", "help");
        }
        String right = app.vizMode().label + " · " + th.name();
        int rightX = W - 2 - WcWidth.width(right);
        s.text(rightX, y, right, th.dim(), bg, 0, W);
        int x = 2;
        for (String[] h : hints) {
            int need = WcWidth.width(h[0]) + WcWidth.width(h[1]) + 3;
            if (x + need > rightX - 2) break;
            x = s.text(x, y, h[0], th.accent(), bg, Screen.BOLD, W);
            x = s.text(x + 1, y, h[1], th.dim(), bg, 0, W) + 3;
        }
    }

    private static void add(List<String[]> list, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) list.add(new String[]{kv[i], kv[i + 1]});
    }

    // ───────────────────────────── player bar ─────────────────────────────

    private Cat.Mood mood() {
        Player.State st = app.player.state();
        if (app.toast != null && app.toastError) return Cat.Mood.SAD;
        if (st == Player.State.ERROR) return Cat.Mood.SAD;
        if (System.nanoTime() - app.likedAt < 2_500_000_000L) return Cat.Mood.LOVE;
        if (app.resolving || st == Player.State.LOADING || app.searching) return Cat.Mood.THINK;
        if (st == Player.State.PLAYING) return Cat.Mood.DANCE;
        if (st == Player.State.PAUSED) return Cat.Mood.SLEEP;
        return Cat.Mood.IDLE;
    }

    private void playerBar(int x, int y, int w, int h) {
        frame(x, y, w, h, th.border(), pb);
        Player.State st = app.player.state();
        Track tr = app.playing;
        int ix = x + 2, iy = y + 1;
        if (tr == null || !(sharp(tr, ix, iy, 8, 4, 2, pb) || app.art.draw(s, tr.thumb(), ix, iy, 8, 4)))
            AlbumArt.miniCassette(s, ix, iy, app.vinylAngle, th, pb);

        boolean cat = w >= 72;
        int right = x + w - 2 - (cat ? Cat.WIDTH + 3 : 0);
        int cx = ix + 10;
        String glyph = app.resolving || st == Player.State.LOADING ? spinner()
                : switch (st) {
            case PLAYING -> "▶";
            case PAUSED -> "❚❚";
            case ERROR -> "✕";
            default -> "■";
        };
        s.text(cx, iy, glyph, st == Player.State.ERROR ? ERROR : th.accent(), pb, Screen.BOLD, cx + 2);
        int tx = cx + 3;
        String title = tr == null ? "nothing playing" : tr.title();
        int ex = s.text(tx, iy, WcWidth.fit(title, right - tx - 2), tr == null ? th.muted() : th.fg(), pb, Screen.BOLD, right);
        if (tr != null && app.library.isLiked(tr.id())) s.text(ex + 2, iy, "♥", th.accent(), pb, 0, right);

        String sub = tr == null ? "press / to find something to listen to" : tr.subtitle();
        int sx = s.text(tx, iy + 1, WcWidth.fit(sub, Math.max(4, right - tx - 20)), th.muted(), pb, 0, right);
        String fmt = app.radioLoading ? spinner() + " finding similar songs" : app.format;
        if (fmt != null) s.text(sx + 3, iy + 1, WcWidth.fit(fmt, right - sx - 3), th.dim(), pb, 0, right);

        progress(cx, iy + 2, right - cx, tr, st);

        int nx = app.current + 1;
        if (nx > 0 && nx < app.queue.size()) {
            Track n = app.queue.get(nx);
            int px = s.text(cx, iy + 3, "next", th.dim(), pb, 0, right) + 2;
            px = s.text(px, iy + 3, WcWidth.fit(n.title(), Math.max(4, (right - px) * 6 / 10)), th.muted(), pb, 0, right);
            s.text(px + 2, iy + 3, WcWidth.fit(n.artist(), right - px - 2), th.dim(), pb, 0, right);
        } else if (tr != null) {
            String msg = app.autoplay && !tr.local() ? "next  autoplay will find something similar ♪" : "next  end of queue";
            s.text(cx, iy + 3, msg, th.dim(), pb, 0, right);
        }

        if (cat) {
            int catX = x + w - 2 - Cat.WIDTH;
            Cat.draw(s, catX, iy, mood(), t, app.spectrum.pulse, th, pb);
            volume(catX, iy + 3);
        } else {
            volume(right - 12, iy);
        }
    }

    private void progress(int x, int y, int w, Track tr, Player.State st) {
        double pos = tr == null ? 0 : app.player.position();
        double dur = app.player.duration() > 0 ? app.player.duration() : tr != null ? Math.max(0, tr.duration()) : 0;
        String a = Fmt.time(pos), b = Fmt.time(dur);
        s.text(x, y, a, th.fg(), pb);
        int bx = x + a.length() + 2, bw = w - a.length() - b.length() - 4;
        s.text(bx + bw + 2, y, b, th.dim(), pb);
        if (bw < 4) return;
        if (app.resolving || st == Player.State.LOADING) {
            int head = (int) (t * 24) % (bw + 6) - 3;
            for (int i = 0; i < bw; i++) {
                boolean on = i >= head && i < head + 4;
                s.set(bx + i, y, on ? '━' : '─', on ? th.accent() : th.border(), pb, 0);
            }
        } else {
            double f = dur > 0 ? Math.max(0, Math.min(1, pos / dur)) : 0;
            int full = (int) (f * bw);
            for (int i = 0; i < bw; i++) s.set(bx + i, y, i < full ? '━' : '─', i < full ? th.accent() : th.border(), pb, 0);
            if (tr != null) s.set(bx + Math.min(bw - 1, full), y, '●', app.spectrum.pulse > 0.6f ? th.accent2() : th.fg(), pb, Screen.BOLD);
        }
        int fbx = bx, fbw = bw;
        hits.add(new Hit(bx, y, bw, 1, (mx, my, d) -> app.seekFraction((mx - fbx + 0.5) / fbw)));
    }

    private void volume(int x, int y) {
        double v = app.player.volume();
        int lv = (int) Math.round(v * 8);
        for (int i = 0; i < 8; i++) {
            s.set(x + i, y, EIGHTHS.charAt(i + 1), i < lv ? th.accent2() : th.border(), pb, 0);
            int level = i + 1;
            hits.add(new Hit(x + i, y, 1, 1, (mx, my, d) -> app.setVolume(level / 8.0)));
        }
        String pct = app.muted() ? " mute" : String.format(" %d%%", Math.round(v * 100));
        s.text(x + 8, y, pct, app.muted() ? ERROR : th.dim(), pb, 0, x + 13);
    }

    // ───────────────────────────── views ─────────────────────────────

    private void searchView(int x, int y, int w, int h) {
        boolean focus = app.typing;
        frame(x, y, w, 3, focus ? th.accent() : th.border(), bg);
        s.text(x + 2, y, " search ", focus ? th.accent() : th.muted(), bg, 0, x + w - 2);
        s.text(x + 2, y + 1, "❯", focus ? th.accent() : th.dim(), bg, Screen.BOLD, x + 3);
        int qx = x + 4, maxX = x + w - 2;
        String q = app.query.toString();
        String before = q.substring(0, Math.min(app.cursor, q.length()));
        int avail = maxX - qx - 1;
        if (q.isEmpty()) {
            s.text(qx, y + 1, "songs, artists, albums… or paste a youtube link", th.dim(), bg, Screen.ITALIC, maxX);
        } else {
            int shift = Math.max(0, WcWidth.width(before) - avail);
            s.text(qx, y + 1, shift > 0 ? dropColumns(q, shift) : q, th.fg(), bg, 0, maxX);
        }
        if (focus && ((int) (t * 2.2)) % 2 == 0) {
            int cxp = qx + Math.min(WcWidth.width(before), avail);
            int under = app.cursor < q.length() ? q.codePointAt(app.cursor) : ' ';
            s.set(cxp, y + 1, under, th.bg(), th.accent(), 0);
        }
        hits.add(new Hit(x, y, w, 3, (mx, my, d) -> app.focusSearch()));

        int fx = x + 2;
        for (MusicSource.Filter f : MusicSource.Filter.values()) {
            boolean on = f == app.filter;
            int start = fx;
            fx = s.text(fx, y + 3, on ? "●" : "○", on ? th.accent() : th.border(), bg, 0, x + w);
            fx = s.text(fx + 1, y + 3, f.label.toLowerCase(), on ? th.fg() : th.muted(), bg, on ? Screen.BOLD : 0, x + w);
            MusicSource.Filter target = f;
            hits.add(new Hit(start, y + 3, fx - start, 1, (mx, my, d) -> {
                if (!d) app.setFilter(target);
            }));
            fx += 3;
        }
        String status = app.searching ? spinner() + " searching"
                : app.loadingMore ? spinner() + " loading more"
                : app.results.isEmpty() ? "" : app.results.size() + (app.more != null ? "+" : "") + " results";
        s.text(x + w - 1 - WcWidth.width(status), y + 3, status, th.dim(), bg, 0, x + w);

        int ly = y + 5, lh = h - 5;
        if (lh < 2) return;
        if (app.results.isEmpty()) {
            if (app.searching) loading(x, ly, w, lh, "asking youtube music");
            else empty(x, ly, w, lh);
            return;
        }
        if (app.resultsTitle != null) {
            int bx = s.text(x + 1, ly, "‹ esc", th.dim(), bg, 0, x + w);
            s.text(bx + 2, ly, WcWidth.fit(app.resultsTitle, x + w - bx - 3), th.accent(), bg, Screen.BOLD, x + w);
            hits.add(new Hit(x, ly, 6, 1, (mx, my, d) -> app.goBack()));
            ly++;
            lh--;
        }
        list(x, ly, w, lh, app.results, app.resultsState, !focus, -1, -1, i -> app.click(App.Tab.SEARCH, i));
    }

    private void queueView(int x, int y, int w, int h) {
        long total = 0;
        for (Track tr : app.queue) total += Math.max(0, tr.duration());
        int tx = s.text(x + 1, y, "queue", th.fg(), bg, Screen.BOLD, x + w);
        if (!app.queue.isEmpty())
            s.text(tx + 2, y, app.queue.size() + " songs · " + Fmt.span(total), th.dim(), bg, 0, x + w);
        if (app.queue.isEmpty()) {
            int cy = y + Math.max(1, h / 2 - 3);
            Cat.draw(s, x + (w - 10) / 2, cy, Cat.Mood.SLEEP, t, 0, th, bg);
            center(x, cy + 4, w, "the queue is empty", th.muted(), Screen.BOLD);
            center(x, cy + 5, w, "press a on a song to add it · r to start a radio", th.dim(), 0);
            return;
        }
        list(x, y + 2, w, h - 2, app.queue, app.queueState, true, app.current, app.current,
                i -> app.click(App.Tab.QUEUE, i));
    }

    private void libraryView(int x, int y, int w, int h) {
        int fx = x + 1;
        String[] labels = {"liked " + app.library.liked().size(), "history " + app.library.history().size()};
        String[] icons = {"♥", "◷"};
        for (int i = 0; i < 2; i++) {
            boolean on = i == app.libSection;
            int start = fx;
            fx = s.text(fx, y, icons[i], on ? th.accent() : th.border(), bg, 0, x + w);
            fx = s.text(fx + 1, y, labels[i], on ? th.fg() : th.muted(), bg, on ? Screen.BOLD : 0, x + w);
            int sec = i;
            hits.add(new Hit(start, y, fx - start, 1, (mx, my, d) -> {
                if (!d && app.libSection != sec) app.switchLibrary();
            }));
            fx += 4;
        }
        List<Track> l = app.libraryList();
        if (l.isEmpty()) {
            int cy = y + Math.max(2, h / 2 - 3);
            Cat.draw(s, x + (w - 10) / 2, cy, Cat.Mood.IDLE, t, 0, th, bg);
            center(x, cy + 4, w, app.libSection == 0 ? "no liked songs yet" : "nothing played yet", th.muted(), Screen.BOLD);
            if (app.libSection == 0) center(x, cy + 5, w, "press f on a song to give it a ♥", th.dim(), 0);
            return;
        }
        list(x, y + 2, w, h - 2, l, app.libState, true, -1, -1, i -> app.click(App.Tab.LIBRARY, i));
    }

    private void nowView(int x, int y, int w, int h) {
        if (w < 88 || h < 14) {
            vizStrip(x, y, w, h);
            return;
        }
        Track tr = app.playing;
        int artRows = Math.min(h - 1, (w * 40 / 100) / 2), artCols = artRows * 2;
        int ax = x + 2, ay = y;
        drawCover(tr, ax, ay, artCols, artRows, bg);

        int rx = ax + artCols + 5, rw = x + w - rx - 1, ry = ay + 1;
        s.text(rx, ry++, WcWidth.fit(tr == null ? "nothing playing" : tr.title(), rw), th.fg(), bg, Screen.BOLD, rx + rw);
        s.text(rx, ry++, WcWidth.fit(tr == null ? "press / to search" : tr.artist(), rw), th.accent(), bg, 0, rx + rw);
        if (tr != null && tr.album() != null) s.text(rx, ry, WcWidth.fit(tr.album(), rw), th.muted(), bg, Screen.ITALIC, rx + rw);
        ry += 2;

        if (h >= 22) {
            int dw = BigDigits.draw(s, rx, ry, Fmt.time(app.player.position()), th.fg(), th.accent(), bg);
            double dur = app.player.duration() > 0 ? app.player.duration() : tr == null ? 0 : tr.duration();
            s.text(rx + dw + 1, ry + 2, "/ " + Fmt.time(dur), th.dim(), bg, 0, rx + rw);
            ry += 4;
        }

        StringBuilder meta = new StringBuilder();
        if (app.format != null) meta.append("♪ ").append(app.format);
        if (tr != null && app.library.isLiked(tr.id())) meta.append(meta.isEmpty() ? "" : "   ").append("♥ liked");
        s.text(rx, ry, WcWidth.fit(meta.toString(), rw), th.accent2(), bg, 0, rx + rw);
        ry += 2;

        int upcoming = Math.min(3, app.queue.size() - app.current - 1);
        if (upcoming > 0 && h >= 26) {
            s.text(rx, ry++, "up next", th.dim(), bg, Screen.BOLD, rx + rw);
            for (int i = 1; i <= upcoming; i++) {
                Track n = app.queue.get(app.current + i);
                int px = s.text(rx, ry, String.valueOf(i), th.dim(), bg, 0, rx + rw) + 2;
                px = s.text(px, ry, WcWidth.fit(n.title(), Math.max(6, (rx + rw - px) * 6 / 10)), th.muted(), bg, 0, rx + rw);
                s.text(px + 2, ry, WcWidth.fit(n.artist(), rx + rw - px - 2), th.dim(), bg, 0, rx + rw);
                ry++;
            }
            ry++;
        }
        int vh = y + h - ry;
        if (vh >= 5) vizStrip(rx, ry, rw, vh);
    }

    /** Cover art in the chosen style, or a cassette tape when there is none. */
    private void drawCover(Track tr, int x, int y, int cols, int rows, int background) {
        if (rows <= 0) return;
        boolean drawn = tr != null && switch (app.artMode) {
            case SHARP -> sharp(tr, x, y, cols, rows, 1, background) || app.art.drawAscii(s, tr.thumb(), x, y, cols, rows, background);
            case ASCII -> app.art.drawAscii(s, tr.thumb(), x, y, cols, rows, background);
            case PIXELS -> app.art.draw(s, tr.thumb(), x, y, cols, rows);
        };
        if (!drawn) AlbumArt.cassette(s, x, y, cols, rows, tr == null ? null : tr.title(), app.vinylAngle, th, background);
        hits.add(new Hit(x, y, cols, rows, (mx, my, d) -> {
            if (!d) app.toggleArtStyle();
        }));
    }

    /** Real-resolution cover through the terminal's image protocol. Hidden while the help overlay is up. */
    private boolean sharp(Track tr, int x, int y, int cols, int rows, int pid, int background) {
        if (!app.graphics || app.artMode != App.ArtMode.SHARP || app.help) return false;
        byte[] png = app.art.png(tr.thumb());
        if (png == null) return false;
        s.fill(x, y, cols, rows, ' ', th.fg(), background);
        s.image(app.art.idOf(tr.thumb()), pid, png, x, y, cols, rows);
        return true;
    }

    private void sidePanel(int x, int y, int w, int h) {
        frame(x, y, w, h, th.border(), pb);
        s.text(x + 2, y, " now playing ", th.muted(), pb, 0, x + w - 2);
        int iw = w - 4, ih = h - 2;
        int artRows = Math.max(0, Math.min(iw / 2, ih * 55 / 100)), artCols = artRows * 2;
        drawCover(app.playing, x + (w - artCols) / 2, y + 1, artCols, artRows, pb);
        int vy = y + 1 + artRows + 1, vh = y + h - 1 - vy;
        if (vh >= 4) vizStripIn(x + 1, vy, w - 2, vh, pb);
    }

    private void vizStrip(int x, int y, int w, int h) {
        vizStripIn(x, y, w, h, bg);
    }

    /** Visualizer with a dotted caption line above it. */
    private void vizStripIn(int x, int y, int w, int h, int background) {
        for (int i = x; i < x + w; i++) s.set(i, y, '┄', th.border(), background, 0);
        s.text(x + 2, y, " ◈ " + app.vizMode().label + " ", th.muted(), background, 0, x + w);
        app.visualizer.draw(s, x + 1, y + 1, w - 2, h - 1, app.vizMode(), app.spectrum, th, t, app.dt, background);
        hits.add(new Hit(x, y, w, h, (mx, my, d) -> {
            if (!d) app.cycleViz(1);
        }));
    }

    /** Track/collection list: column header, selection, now-playing marker, likes and a scrollbar. */
    private void list(int x, int y, int w, int h, List<? extends Item> items, App.ListState st, boolean focused,
                      int playingIndex, int dimBefore, IntConsumer onClick) {
        int durW = 9, numW = 4;
        int flex = Math.max(10, w - numW - durW - 4);
        int titleW = Math.max(8, flex * 58 / 100), artistW = Math.max(0, flex - titleW - 2);
        int tx = x + numW + 2, ax = tx + titleW + 2;

        s.text(x + numW - 1, y, "#", th.dim(), bg, 0, x + w);
        s.text(tx, y, "title", th.dim(), bg, 0, x + w);
        s.text(ax, y, "artist", th.dim(), bg, 0, x + w);
        s.text(x + w - 6, y, "time", th.dim(), bg, 0, x + w);
        y++;
        h--;
        if (h <= 0) return;

        int n = items.size();
        st.clamp(n);
        if (st.sel < st.scroll) st.scroll = st.sel;
        if (st.sel >= st.scroll + h) st.scroll = st.sel - h + 1;
        st.scroll = Math.max(0, Math.min(st.scroll, Math.max(0, n - h)));
        boolean bar = n > h;
        int cw = w - (bar ? 2 : 0);
        Track now = app.playing;
        Player.State pst = app.player.state();

        for (int row = 0; row < h; row++) {
            int i = st.scroll + row;
            if (i >= n) break;
            Item it = items.get(i);
            int ry = y + row;
            boolean sel = i == st.sel;
            boolean isPlaying = playingIndex >= 0 ? i == playingIndex
                    : it instanceof Track tk && now != null && tk.id().equals(now.id());
            boolean faded = dimBefore >= 0 && i < dimBefore;
            int rowBg = sel && focused ? th.selection() : bg;
            if (sel && focused) s.fill(x, ry, cw, 1, ' ', th.fg(), rowBg);
            if (sel) s.set(x, ry, '▌', focused ? th.accent() : th.border(), rowBg, 0);

            if (isPlaying) {
                if (app.resolving || pst == Player.State.LOADING) {
                    s.text(x + numW - 1, ry, spinner(), th.accent(), rowBg, 0, x + numW + 1);
                } else if (pst != Player.State.PLAYING) {
                    s.text(x + numW - 2, ry, "❚❚", th.accent(), rowBg, 0, x + numW + 1);
                } else {
                    Spectrum.Bands b = app.spectrum.bands(3);
                    for (int k = 0; k < 3; k++)
                        s.set(x + numW - 2 + k, ry, EIGHTHS.charAt(1 + Math.round(b.v[k] * 7)), th.accent(), rowBg, 0);
                }
            } else if (it instanceof Playlist) {
                s.text(x + numW - 1, ry, "◫", th.accent2(), rowBg, 0, x + numW + 1);
            } else {
                String num = String.valueOf(i + 1);
                s.text(x + numW - num.length(), ry, num, th.dim(), rowBg, 0, x + numW);
            }
            int titleColor = isPlaying ? th.accent() : faded ? th.dim() : th.fg();
            s.text(tx, ry, WcWidth.fit(it.title(), titleW), titleColor, rowBg, sel || isPlaying ? Screen.BOLD : 0, tx + titleW);
            s.text(ax, ry, WcWidth.fit(it.subtitle(), artistW), faded ? th.dim() : th.muted(), rowBg, 0, ax + artistW);
            if (it instanceof Track tk && app.library.isLiked(tk.id())) s.set(x + cw - durW, ry, '♥', th.accent(), rowBg, 0);
            String meta = it.meta();
            s.text(x + cw - WcWidth.width(meta) - 1, ry, meta, th.dim(), rowBg, 0, x + cw);
            int idx = i;
            hits.add(new Hit(x, ry, cw, 1, (mx, my, d) -> {
                if (!d) onClick.accept(idx);
            }));
        }
        if (bar) {
            int thumb = Math.max(1, h * h / n);
            int pos = n - h <= 0 ? 0 : (int) Math.round((double) st.scroll * (h - thumb) / (n - h));
            for (int r = 0; r < h; r++) {
                boolean on = r >= pos && r < pos + thumb;
                s.set(x + w - 1, y + r, on ? '┃' : '│', on ? th.accent() : th.border(), bg, 0);
            }
        }
    }

    private void empty(int x, int y, int w, int h) {
        if (w >= Logo.WIDTH + 4 && h >= Logo.HEIGHT + 8) {
            int ly = y + (h - Logo.HEIGHT - 7) / 2;
            Logo.draw(s, x + (w - Logo.WIDTH) / 2, ly, th, 1);
            Cat.draw(s, x + (w - 9) / 2, ly + Logo.HEIGHT + 1, Cat.Mood.WAVE, t, 0, th, bg);
            center(x, ly + Logo.HEIGHT + 5, w, "type to search  ·  ⏎ to play  ·  ? for keys", th.dim(), 0);
        } else {
            int cy = y + Math.max(0, (h - 5) / 2);
            Cat.draw(s, x + (w - 9) / 2, cy, Cat.Mood.WAVE, t, 0, th, bg);
            center(x, cy + 4, w, "hi! press / and type to search ♪", th.muted(), 0);
        }
    }

    private void loading(int x, int y, int w, int h, String what) {
        int cy = y + Math.max(0, (h - 5) / 2);
        Cat.draw(s, x + (w - 9) / 2, cy, Cat.Mood.THINK, t, 0, th, bg);
        center(x, cy + 4, w, spinner() + " " + what + "…", th.muted(), 0);
    }

    // ───────────────────────────── overlays & special screens ─────────────────────────────

    private void toast(int W, int playerY) {
        if (app.toast == null) return;
        String m = (app.toastError ? "(×_×)  " : "♪  ") + app.toast;
        m = WcWidth.fit(m, W - 10);
        int bw = WcWidth.width(m) + 4, bx = W - bw - 2, by = playerY - 3;
        int border = app.toastError ? ERROR : th.accent();
        s.fill(bx, by, bw, 3, ' ', th.fg(), th.panel());
        frame(bx, by, bw, 3, border, th.panel());
        s.text(bx + 2, by + 1, m, th.fg(), th.panel(), 0, bx + bw - 1);
    }

    private void help(int W, int H) {
        String[][] keys = {
                {"/", "search · tab switches songs/videos/albums"},
                {"⏎", "play · open an album or playlist"},
                {"a  A", "add to queue · play next"},
                {"r", "start a radio from a song"},
                {"f", "like / unlike ♥"},
                {"space", "play / pause"},
                {"n  p", "next · previous"},
                {"← →  h l", "seek 5s  (shift or , . for 30s)"},
                {"+ -  m", "volume · mute"},
                {"s  R  x", "shuffle · repeat · autoplay"},
                {"1 2 3 4", "search · queue · library · now"},
                {"j k ↑ ↓", "move · g G top/bottom"},
                {"d  J K  C", "queue: remove · move · clear"},
                {"o", "jump to the current song"},
                {"[ ]", "switch filter / library section"},
                {"v  V", "visualizer style"},
                {"i", "cover art: sharp / ascii / pixels"},
                {"t  T", "theme"},
                {"z", "zen mode"},
                {"esc", "back from an album"},
                {"q", "quit"}};
        int bw = Math.min(W - 4, 62), bh = Math.min(H - 2, keys.length + 4);
        int bx = (W - bw) / 2, by = (H - bh) / 2;
        s.fill(bx, by, bw, bh, ' ', th.fg(), th.panel());
        frame(bx, by, bw, bh, th.accent(), th.panel());
        s.text(bx + 2, by, " keys ", th.accent(), th.panel(), Screen.BOLD, bx + bw);
        if (bw >= 58 && bh >= 8) Cat.draw(s, bx + bw - 13, by + 1, Cat.Mood.WAVE, t, 0, th, th.panel());
        for (int i = 0; i < keys.length && i < bh - 3; i++) {
            int ry = by + 1 + i;
            s.text(bx + 3, ry, keys[i][0], th.accent(), th.panel(), Screen.BOLD, bx + 14);
            s.text(bx + 14, ry, keys[i][1], th.muted(), th.panel(), 0, bx + bw - (i < 3 ? 14 : 2));
        }
        center(bx, by + bh - 1, bw, " any key to close ", th.dim(), 0);
    }

    private void splash(int W, int H) {
        int stars = W * H / 50;
        for (int k = 0; k < stars; k++) {
            int hsh = hash(k);
            int sx = Math.floorMod(hsh, W), sy = Math.floorMod(hsh >>> 11, H);
            boolean on = ((int) (t * 1.5) + (hsh & 7)) % 5 != 0;
            if (!on) continue;
            char c = (hsh & 7) == 0 ? '✦' : (hsh & 3) == 1 ? '+' : '·';
            s.set(sx, sy, c, (hsh & 7) == 0 ? th.accent2() : th.border(), bg, 0);
        }
        double e = (System.nanoTime() - app.startNs) / 1e9;
        int lx = (W - Logo.WIDTH) / 2, ly = (H - Logo.HEIGHT) / 2 - 4;
        s.fill(lx - 2, ly - 1, Logo.WIDTH + 4, Logo.HEIGHT + 11, ' ', th.fg(), bg);
        Logo.draw(s, lx, ly, th, Math.min(1, e / 0.7));
        Cat.draw(s, (W - 9) / 2, ly + Logo.HEIGHT + 1, Cat.Mood.WAVE, t, 0, th, bg);
        center(0, ly + Logo.HEIGHT + 5, W, "music in your terminal", th.muted(), 0);
        String status = app.source != null ? "ready ♪" : app.sourceFailed ? "offline (×_×)" : spinner() + " tuning in";
        center(0, ly + Logo.HEIGHT + 7, W, status, th.dim(), 0);
    }

    private void zen(int W, int H) {
        app.visualizer.draw(s, 1, 2, W - 2, H - 4, app.vizMode(), app.spectrum, th, t, app.dt, bg);
        Track tr = app.playing;
        int x = s.text(2, 0, tr == null ? "♫ melo" : tr.title(), th.fg(), bg, Screen.BOLD, W - 24);
        if (tr != null) s.text(x + 2, 0, tr.artist(), th.accent(), bg, 0, W - 24);
        String mode = app.vizMode().label + " · z to exit";
        s.text(W - 2 - WcWidth.width(mode), 0, mode, th.dim(), bg, 0, W);
        double dur = app.player.duration();
        double f = dur > 0 ? Math.min(1, app.player.position() / dur) : 0;
        int filled = (int) (f * (W - 4));
        for (int i = 0; i < W - 4; i++) s.set(2 + i, H - 1, i < filled ? '━' : '─', i < filled ? th.accent() : th.border(), bg, 0);
        hits.add(new Hit(2, H - 1, W - 4, 1, (mx, my, d) -> app.seekFraction((mx - 2 + 0.5) / (W - 4))));
    }

    private void tooSmall(int W, int H) {
        int cy = Math.max(0, H / 2 - 3);
        Cat.draw(s, (W - 9) / 2, cy, Cat.Mood.SAD, t, 0, th, bg);
        center(0, cy + 4, W, "too cozy in here — melo needs 60×18", th.muted(), 0);
        center(0, cy + 5, W, "(this terminal is " + W + "×" + H + ")", th.dim(), 0);
    }

    // ───────────────────────────── primitives ─────────────────────────────

    /** Rounded rectangle in a single flat color, filled with {@code fill}. */
    private void frame(int x, int y, int w, int h, int color, int fill) {
        if (w < 2 || h < 2) return;
        s.fill(x, y, w, h, ' ', th.fg(), fill);
        for (int i = 0; i < w; i++) {
            s.set(x + i, y, i == 0 ? '╭' : i == w - 1 ? '╮' : '─', color, fill, 0);
            s.set(x + i, y + h - 1, i == 0 ? '╰' : i == w - 1 ? '╯' : '─', color, fill, 0);
        }
        for (int j = 1; j < h - 1; j++) {
            s.set(x, y + j, '│', color, fill, 0);
            s.set(x + w - 1, y + j, '│', color, fill, 0);
        }
    }

    private void center(int x, int y, int w, String m, int color, int attr) {
        int mw = WcWidth.width(m);
        s.text(x + Math.max(0, (w - mw) / 2), y, m, color, Screen.KEEP, attr, x + w);
    }

    private String spinner() {
        return String.valueOf(SPIN.charAt((int) (t * 12) % SPIN.length()));
    }

    private static String dropColumns(String str, int cols) {
        int w = 0, k = 0;
        while (k < str.length() && w < cols) {
            int cp = str.codePointAt(k);
            w += WcWidth.of(cp);
            k += Character.charCount(cp);
        }
        return str.substring(k);
    }

    private static int hash(int k) {
        int h = k * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA77;
        h ^= h >>> 13;
        return h & 0x7fffffff;
    }
}
