package melo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import melo.audio.NullSink;
import melo.audio.Player;
import melo.audio.Sinks;
import melo.audio.Spectrum;
import melo.source.Item;
import melo.source.LocalFiles;
import melo.source.MusicSource;
import melo.source.Playlist;
import melo.source.Resolved;
import melo.source.Track;
import melo.source.YtDlp;
import melo.term.Input;
import melo.term.Key;
import melo.term.Screen;
import melo.term.Terminal;
import melo.ui.AlbumArt;
import melo.ui.Theme;
import melo.ui.Visualizer;

/**
 * Application state and controller. All state is owned by the UI thread: background work (search,
 * stream resolution, artwork, player callbacks) reports back through {@link #post}.
 */
final class App {
    enum Tab {
        SEARCH("Search"), QUEUE("Queue"), LIBRARY("Library"), NOW("Now Playing");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    enum Repeat { OFF, ALL, ONE }

    enum ArtMode { SHARP, ASCII, PIXELS }

    static final class ListState {
        int sel, scroll;

        void clamp(int size) {
            if (sel >= size) sel = Math.max(0, size - 1);
            if (sel < 0) sel = 0;
        }
    }

    private record Saved(List<Item> items, int sel, int scroll, String title, MusicSource.More more) {
    }

    private record Prefetch(Resolved resolved, long at) {
    }

    private static final long FRAME_NS = 33_000_000L, IDLE_NS = 250_000_000L;

    final Main.Options opts;
    final Terminal term;
    final Screen screen;
    final Player player;
    final Spectrum spectrum = new Spectrum(Player.RATE);
    final Visualizer visualizer = new Visualizer();
    final AlbumArt art = new AlbumArt();
    final Library library;
    final Settings settings;
    final Ui ui;
    final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    final long startNs = System.nanoTime();
    volatile MusicSource source;

    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean running = true;
    private volatile boolean resized = true;
    private long lastSizeCheck;
    private double lastFrame;
    double dt = 1 / 30.0;

    // search
    Tab tab = Tab.SEARCH;
    boolean typing = true;
    final StringBuilder query = new StringBuilder();
    int cursor;
    MusicSource.Filter filter = MusicSource.Filter.SONGS;
    List<Item> results = new ArrayList<>();
    final ListState resultsState = new ListState();
    String resultsTitle;
    String lastQuery = "";
    MusicSource.More more;
    boolean searching, loadingMore, sourceFailed, pendingSearch;
    private int searchToken;
    private final Deque<Saved> back = new ArrayDeque<>();

    // queue & playback
    final List<Track> queue = new ArrayList<>();
    int current = -1;
    final ListState queueState = new ListState();
    Track playing;
    String format;
    boolean resolving, radioLoading;
    private int playToken, failures, pendingPlay = -1;
    private boolean triedFallback;
    boolean shuffle, autoplay = true;
    Repeat repeat = Repeat.OFF;
    private double mutedVolume = -1;
    private final Map<String, Prefetch> prefetched = new HashMap<>();
    private String prefetching;

    // library
    int libSection;
    final ListState libState = new ListState();

    // presentation
    int themeIdx, vizIdx;
    boolean transparent, help, zen, splash = true;
    private long splashUntil;
    double vinylAngle;
    ArtMode artMode = ArtMode.ASCII;
    final boolean graphics;
    long likedAt;
    String toast;
    boolean toastError;
    long toastUntil;
    private long lastClickNs;
    private int lastClickRow = -1;

    App(Main.Options o, int[] headlessSize) {
        opts = o;
        boolean headless = headlessSize != null;
        term = headless ? null : new Terminal();
        int[] size = headless ? headlessSize : term.size();
        screen = new Screen(size[0], size[1]);
        Path data = headless ? tempDir() : Sys.dataDir();
        library = new Library(data);
        library.load();
        settings = new Settings(headless ? data : Sys.configDir());

        int ti = Theme.indexOf(o.theme() != null ? o.theme() : settings.get("theme", "synthwave"));
        themeIdx = Math.max(0, ti);
        vizIdx = Math.floorMod(settings.getInt("visualizer", 0), Visualizer.Mode.values().length);
        autoplay = settings.getBool("autoplay", true);
        transparent = o.transparent() || settings.getBool("transparent", false);
        graphics = !headless && Terminal.supportsGraphics();
        try {
            artMode = ArtMode.valueOf(settings.get("art", graphics ? "SHARP" : "ASCII"));
        } catch (IllegalArgumentException e) {
            artMode = graphics ? ArtMode.SHARP : ArtMode.ASCII;
        }
        if (artMode == ArtMode.SHARP && !graphics) artMode = ArtMode.ASCII;

        player = new Player(headless ? NullSink::new : Sinks.factory(o.audio()), new Player.Listener() {
            @Override
            public void started() {
                post(() -> failures = 0);
            }

            @Override
            public void ended() {
                post(App.this::onEnded);
            }

            @Override
            public void failed(String message, boolean beforeAudio) {
                post(() -> onFailed(message, beforeAudio));
            }
        });
        player.setVolume(settings.getDouble("volume", 0.8));
        ui = new Ui(this);
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("melo-snapshot");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    void run() throws Exception {
        term.enter();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        installResizeHandler();
        Thread.ofPlatform().daemon().name("melo-input").start(new Input(term.input(), k -> post(() -> onKey(k))));
        splashUntil = System.nanoTime() + 1_700_000_000L;
        io.execute(this::initSource);
        if (!opts.files().isEmpty()) {
            List<Track> local = LocalFiles.expand(opts.files());
            post(() -> {
                if (local.isEmpty()) {
                    toastError("No playable audio files found");
                    return;
                }
                queue.addAll(local);
                tab = Tab.QUEUE;
                typing = false;
                playIndex(0);
            });
        }
        loop();
        shutdown();
        System.exit(0);
    }

    private void initSource() {
        try {
            MusicSource s = new MusicSource();
            post(() -> {
                source = s;
                if (pendingSearch) runSearch();
                if (pendingPlay >= 0) {
                    int i = pendingPlay;
                    pendingPlay = -1;
                    playIndex(i);
                }
            });
        } catch (Throwable t) {
            Log.error("initializing NewPipe", t);
            post(() -> {
                sourceFailed = true;
                toastError("Couldn't initialize YouTube Music: " + msg(t));
            });
        }
    }

    @SuppressWarnings("restriction")
    private void installResizeHandler() {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("WINCH"), sig -> resized = true);
        } catch (Throwable t) {
            Log.info("SIGWINCH unavailable, polling terminal size");
        }
    }

    void post(Runnable r) {
        tasks.add(r);
    }

    private void loop() {
        long lastRender = 0, nextFrame = System.nanoTime();
        while (running) {
            try {
                long wait = nextFrame - System.nanoTime();
                Runnable r = wait > 0 ? tasks.poll(wait, TimeUnit.NANOSECONDS) : tasks.poll();
                boolean handled = false;
                while (r != null) {
                    safely(r);
                    handled = true;
                    r = tasks.poll();
                }
                if (!running) break;
                long now = System.nanoTime();
                if (now >= nextFrame || (handled && now - lastRender > 12_000_000L)) {
                    render();
                    lastRender = System.nanoTime();
                    nextFrame = lastRender + (animating() ? FRAME_NS : IDLE_NS);
                } else if (handled) {
                    nextFrame = Math.min(nextFrame, lastRender + 12_000_000L);
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void safely(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.error("unhandled", t);
            toastError("Oops: " + msg(t));
        }
    }

    private void render() {
        long now = System.nanoTime();
        if (now - lastSizeCheck > 3_000_000_000L) {
            lastSizeCheck = now;
            resized = true;
        }
        if (resized) {
            resized = false;
            int[] sz = term.size();
            if (sz[0] != screen.width() || sz[1] != screen.height()) screen.resize(sz[0], sz[1]);
        }
        tick();
        ui.draw(screen);
        try {
            screen.flush(term.output());
        } catch (IOException e) {
            running = false;
        }
    }

    private boolean animating() {
        Player.State st = player.state();
        return splash || toast != null || searching || resolving || radioLoading || loadingMore
                || st == Player.State.PLAYING || st == Player.State.LOADING || spectrum.active();
    }

    void tick() {
        double now = time();
        dt = Math.max(0.001, Math.min(0.1, now - lastFrame));
        lastFrame = now;
        spectrum.update(player, dt);
        if (player.state() == Player.State.PLAYING) vinylAngle += dt * 1.6;
        if (splash && System.nanoTime() > splashUntil && (source != null || sourceFailed)) splash = false;
        if (toast != null && System.nanoTime() > toastUntil) toast = null;
        prefetchNext();
        maybeLoadMore();
    }

    double time() {
        return (System.nanoTime() - startNs) / 1e9;
    }

    Theme theme() {
        return Theme.ALL.get(themeIdx);
    }

    Visualizer.Mode vizMode() {
        return Visualizer.Mode.values()[vizIdx];
    }

    void quit() {
        running = false;
    }

    void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            player.stop();
        } catch (Throwable ignored) {
        }
        settings.set("theme", theme().name());
        settings.set("visualizer", vizIdx);
        settings.set("volume", String.format(java.util.Locale.ROOT, "%.2f", mutedVolume >= 0 ? mutedVolume : player.volume()));
        settings.set("autoplay", autoplay);
        settings.set("art", artMode.name());
        settings.save();
        if (term != null) term.exit();
    }

    // ───────────────────────────── notifications ─────────────────────────────

    void toast(String m) {
        toast = m;
        toastError = false;
        toastUntil = System.nanoTime() + 2_600_000_000L;
    }

    void toastError(String m) {
        toast = m;
        toastError = true;
        toastUntil = System.nanoTime() + 4_500_000_000L;
    }

    static String msg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && (c.getMessage() == null || c.getMessage().isBlank())) c = c.getCause();
        String m = c.getMessage();
        if (m == null || m.isBlank()) m = c.getClass().getSimpleName();
        m = m.replace('\n', ' ');
        return m.length() > 120 ? m.substring(0, 117) + "…" : m;
    }

    // ───────────────────────────── search & browse ─────────────────────────────

    void runSearch() {
        String q = query.toString().strip();
        if (q.isEmpty()) q = lastQuery;
        if (q.isEmpty()) return;
        if (source == null) {
            pendingSearch = true;
            if (sourceFailed) toastError("YouTube Music is unavailable — see ~/.cache/melo/melo.log");
            else toast("Connecting to YouTube Music…");
            searching = true;
            lastQuery = q;
            return;
        }
        pendingSearch = false;
        int token = ++searchToken;
        searching = true;
        lastQuery = q;
        back.clear();
        resultsTitle = null;
        more = null;
        MusicSource src = source;
        MusicSource.Filter f = filter;
        String fq = q;
        io.execute(() -> {
            try {
                MusicSource.Results r = src.search(fq, f);
                post(() -> {
                    if (token != searchToken) return;
                    searching = false;
                    results = new ArrayList<>(r.items());
                    resultsTitle = r.title();
                    more = r.more();
                    resultsState.sel = 0;
                    resultsState.scroll = 0;
                    if (results.isEmpty()) toast("no results for “" + fq + "” (._.)");
                });
            } catch (Exception e) {
                Log.error("search " + fq, e);
                post(() -> {
                    if (token != searchToken) return;
                    searching = false;
                    toastError("Search failed: " + msg(e));
                });
            }
        });
    }

    private void maybeLoadMore() {
        if (tab != Tab.SEARCH || more == null || loadingMore || searching) return;
        if (results.size() - resultsState.sel > 6) return;
        loadingMore = true;
        int token = searchToken;
        MusicSource.More m = more;
        io.execute(() -> {
            try {
                MusicSource.Results r = m.fetch();
                post(() -> {
                    loadingMore = false;
                    if (token != searchToken) return;
                    results.addAll(r.items());
                    more = r.more();
                });
            } catch (Exception e) {
                Log.error("loading more", e);
                post(() -> {
                    loadingMore = false;
                    if (token == searchToken) more = null;
                });
            }
        });
    }

    void openPlaylist(Playlist p) {
        if (source == null) return;
        back.push(new Saved(results, resultsState.sel, resultsState.scroll, resultsTitle, more));
        int token = ++searchToken;
        searching = true;
        results = new ArrayList<>();
        resultsTitle = p.title();
        more = null;
        MusicSource src = source;
        io.execute(() -> {
            try {
                MusicSource.Results r = src.playlist(p.url());
                post(() -> {
                    if (token != searchToken) return;
                    searching = false;
                    results = new ArrayList<>(r.items());
                    resultsTitle = r.title();
                    more = r.more();
                    resultsState.sel = 0;
                    resultsState.scroll = 0;
                });
            } catch (Exception e) {
                Log.error("playlist " + p.url(), e);
                post(() -> {
                    if (token != searchToken) return;
                    searching = false;
                    toastError("Couldn't open “" + p.title() + "”: " + msg(e));
                    goBack();
                });
            }
        });
    }

    boolean canGoBack() {
        return !back.isEmpty();
    }

    void goBack() {
        Saved s = back.poll();
        if (s == null) return;
        searchToken++;
        searching = false;
        results = new ArrayList<>(s.items());
        resultsState.sel = s.sel();
        resultsState.scroll = s.scroll();
        resultsTitle = s.title();
        more = s.more();
    }

    void setFilter(MusicSource.Filter f) {
        if (f == filter) return;
        filter = f;
        if (!lastQuery.isEmpty() || !query.toString().isBlank()) runSearch();
    }

    void cycleFilter(int d) {
        MusicSource.Filter[] all = MusicSource.Filter.values();
        setFilter(all[Math.floorMod(filter.ordinal() + d, all.length)]);
    }

    void focusSearch() {
        tab = Tab.SEARCH;
        typing = true;
        cursor = query.length();
    }

    // ───────────────────────────── playback ─────────────────────────────

    void playIndex(int i) {
        if (i < 0 || i >= queue.size()) return;
        current = i;
        queueState.sel = i;
        Track t = queue.get(i);
        playing = t;
        format = null;
        triedFallback = false;
        int token = ++playToken;
        player.prepare();
        resolving = true;
        art.request(t.thumb(), io, () -> post(() -> {
        }));

        Prefetch pre = prefetched.remove(t.id());
        if (pre != null && System.nanoTime() - pre.at() < 2 * 3600 * 1_000_000_000L) {
            startResolved(token, pre.resolved());
            return;
        }
        MusicSource src = source;
        if (src == null && !t.local()) {
            pendingPlay = i;
            if (sourceFailed) toastError("YouTube Music is unavailable");
            return;
        }
        io.execute(() -> {
            try {
                long t0 = System.nanoTime();
                Resolved r = t.local() ? LocalFiles.resolve(t) : src.resolve(t);
                Log.info("resolved " + t.id() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms · " + r.format());
                post(() -> startResolved(token, r));
            } catch (Exception e) {
                Log.error("resolving " + t.url(), e);
                post(() -> resolveFailed(token, t, e));
            }
        });
    }

    private void startResolved(int token, Resolved r) {
        if (token != playToken) return;
        resolving = false;
        Track rich = r.track();
        if (current >= 0 && current < queue.size() && queue.get(current).id().equals(rich.id())) queue.set(current, rich);
        playing = rich;
        format = r.format();
        player.play(r.media(), 0);
        library.addHistory(rich);
        art.request(rich.thumb(), io, () -> post(() -> {
        }));
    }

    private void resolveFailed(int token, Track t, Exception e) {
        if (token != playToken) return;
        if (!t.local() && tryFallback(token, t)) return;
        resolving = false;
        skipAfterFailure(t, msg(e));
    }

    /** Re-resolves through yt-dlp, if installed. */
    private boolean tryFallback(int token, Track t) {
        if (triedFallback || !YtDlp.available()) return false;
        triedFallback = true;
        resolving = true;
        player.prepare();
        io.execute(() -> {
            try {
                Resolved r = YtDlp.resolve(t);
                post(() -> {
                    if (token != playToken) return;
                    resolving = false;
                    format = r.format();
                    player.play(r.media(), 0);
                });
            } catch (Exception e) {
                Log.error("yt-dlp fallback", e);
                post(() -> {
                    if (token != playToken) return;
                    resolving = false;
                    skipAfterFailure(t, msg(e));
                });
            }
        });
        return true;
    }

    private void onFailed(String message, boolean beforeAudio) {
        Track t = playing;
        if (t == null) return;
        Log.info("playback failed (" + (beforeAudio ? "before audio" : "mid-stream") + "): " + message);
        if (beforeAudio && !t.local() && tryFallback(playToken, t)) return;
        skipAfterFailure(t, message);
    }

    private void skipAfterFailure(Track t, String why) {
        toastError("Can't play “" + t.title() + "”: " + why);
        if (++failures < 3 && current + 1 < queue.size()) playIndex(current + 1);
        else player.stop();
    }

    private void onEnded() {
        if (repeat == Repeat.ONE) playIndex(current);
        else if (current + 1 < queue.size()) playIndex(current + 1);
        else if (repeat == Repeat.ALL && !queue.isEmpty()) playIndex(0);
        else if (autoplay && playing != null && !playing.local() && source != null) startRadio(playing, true);
    }

    void togglePause() {
        Player.State st = player.state();
        if (st == Player.State.IDLE || st == Player.State.ERROR) {
            if (current >= 0 && current < queue.size()) playIndex(current);
            else if (!queue.isEmpty()) playIndex(0);
            return;
        }
        player.togglePause();
    }

    void next() {
        if (current + 1 < queue.size()) playIndex(current + 1);
        else if (repeat == Repeat.ALL && !queue.isEmpty()) playIndex(0);
        else if (autoplay && playing != null && !playing.local() && source != null) startRadio(playing, true);
        else toast("that's the end of the queue ♪");
    }

    void prev() {
        if (player.position() > 3 || current <= 0) player.seek(0);
        else playIndex(current - 1);
    }

    void seekBy(double d) {
        if (player.media() != null) player.seek(player.position() + d);
    }

    void seekFraction(double f) {
        double dur = player.duration();
        if (dur > 0) player.seek(Math.max(0, Math.min(1, f)) * dur);
    }

    void volumeBy(double d) {
        if (mutedVolume >= 0) {
            player.setVolume(mutedVolume);
            mutedVolume = -1;
        }
        player.setVolume(player.volume() + d);
    }

    void setVolume(double v) {
        mutedVolume = -1;
        player.setVolume(v);
    }

    void toggleMute() {
        if (mutedVolume >= 0) {
            player.setVolume(mutedVolume);
            mutedVolume = -1;
            toast("Unmuted");
        } else {
            mutedVolume = player.volume();
            player.setVolume(0);
            toast("Muted");
        }
    }

    boolean muted() {
        return mutedVolume >= 0;
    }

    private void prefetchNext() {
        if (playing == null || source == null || player.state() != Player.State.PLAYING) return;
        double dur = player.duration();
        if (dur <= 0 || dur - player.position() > 40) return;
        int ni = current + 1;
        if (ni >= queue.size()) return;
        Track nx = queue.get(ni);
        if (nx.local() || prefetched.containsKey(nx.id()) || nx.id().equals(prefetching)) return;
        prefetching = nx.id();
        MusicSource src = source;
        art.request(nx.thumb(), io, () -> post(() -> {
        }));
        io.execute(() -> {
            try {
                Resolved r = src.resolve(nx);
                post(() -> prefetched.put(nx.id(), new Prefetch(r, System.nanoTime())));
            } catch (Exception e) {
                Log.info("prefetch failed for " + nx.id() + ": " + e.getMessage());
            }
        });
    }

    // ───────────────────────────── queue ─────────────────────────────

    void playNow(Track t) {
        int at = Math.max(0, current + 1);
        queue.add(Math.min(at, queue.size()), t);
        playIndex(Math.min(at, queue.size() - 1));
    }

    /** Replaces everything after the current track with list[from..] and starts playing it. */
    void playFrom(List<Track> list, int from) {
        if (from < 0 || from >= list.size()) return;
        if (current + 1 < queue.size()) queue.subList(current + 1, queue.size()).clear();
        queue.addAll(list.subList(from, list.size()));
        playIndex(current + 1);
    }

    void enqueue(Track t, boolean next) {
        if (next && current >= 0) {
            queue.add(current + 1, t);
            toast("Playing next · " + t.title());
        } else {
            queue.add(t);
            toast("Added to queue · " + t.title());
        }
        Player.State st = player.state();
        if (playing == null && (st == Player.State.IDLE || st == Player.State.ERROR)) playIndex(queue.indexOf(t));
    }

    void enqueueAll(List<Track> tracks, String name) {
        if (tracks.isEmpty()) return;
        boolean idle = playing == null && player.state() == Player.State.IDLE;
        int start = queue.size();
        queue.addAll(tracks);
        toast("Added " + tracks.size() + " songs · " + name);
        if (idle) playIndex(start);
    }

    void startRadio(Track seed, boolean playWhenReady) {
        if (seed == null || seed.local()) return;
        if (source == null) {
            toast("Still connecting to YouTube Music…");
            return;
        }
        radioLoading = true;
        MusicSource src = source;
        io.execute(() -> {
            try {
                List<Track> list = src.radio(seed);
                post(() -> {
                    radioLoading = false;
                    Set<String> have = new HashSet<>();
                    for (int i = Math.max(0, current); i < queue.size(); i++) have.add(queue.get(i).id());
                    int added = 0;
                    for (Track t : list) {
                        if (have.add(t.id())) {
                            queue.add(t);
                            added++;
                        }
                    }
                    if (added == 0) {
                        toast("No similar songs found");
                        return;
                    }
                    toast("Radio · " + added + " songs like “" + seed.title() + "”");
                    if (playWhenReady && current + 1 < queue.size()) playIndex(current + 1);
                });
            } catch (Exception e) {
                Log.error("radio", e);
                post(() -> {
                    radioLoading = false;
                    toastError("Couldn't start radio: " + msg(e));
                });
            }
        });
    }

    void radioFromSelected() {
        Track t = tab == Tab.NOW ? playing : selectedTrack();
        if (t == null) t = playing;
        if (t == null || t.local()) return;
        if (current + 1 < queue.size()) queue.subList(current + 1, queue.size()).clear();
        if (playing == null || !t.id().equals(playing.id())) playNow(t);
        startRadio(t, false);
    }

    void removeSelected() {
        if (tab != Tab.QUEUE || queue.isEmpty()) return;
        int i = queueState.sel;
        if (i < 0 || i >= queue.size()) return;
        Track t = queue.remove(i);
        if (i < current) {
            current--;
        } else if (i == current) {
            if (current < queue.size()) playIndex(current);
            else {
                current = -1;
                playing = null;
                player.stop();
            }
        }
        queueState.clamp(queue.size());
        toast("Removed · " + t.title());
    }

    void moveQueueItem(int d) {
        if (tab != Tab.QUEUE) return;
        int i = queueState.sel, j = i + d;
        if (i < 0 || j < 0 || i >= queue.size() || j >= queue.size()) return;
        Collections.swap(queue, i, j);
        if (current == i) current = j;
        else if (current == j) current = i;
        queueState.sel = j;
    }

    void clearQueue() {
        if (current >= 0 && current < queue.size()) {
            Track keep = queue.get(current);
            queue.clear();
            queue.add(keep);
            current = 0;
        } else {
            queue.clear();
            current = -1;
        }
        queueState.sel = Math.max(0, current);
        toast("Queue cleared");
    }

    void toggleShuffle() {
        shuffle = !shuffle;
        if (shuffle && current + 2 < queue.size()) Collections.shuffle(queue.subList(current + 1, queue.size()));
        toast(shuffle ? "Shuffle on · upcoming songs shuffled" : "Shuffle off");
    }

    void cycleRepeat() {
        repeat = Repeat.values()[(repeat.ordinal() + 1) % Repeat.values().length];
        toast(switch (repeat) {
            case OFF -> "Repeat off";
            case ALL -> "Repeat queue";
            case ONE -> "Repeat this song";
        });
    }

    void toggleAutoplay() {
        autoplay = !autoplay;
        toast(autoplay ? "Autoplay on · similar songs keep coming" : "Autoplay off");
    }

    void toggleLike() {
        Track t = tab == Tab.NOW ? playing : selectedTrack();
        if (t == null) t = playing;
        if (t == null) return;
        if (library.toggleLike(t)) {
            likedAt = System.nanoTime();
            toast("added to liked songs ♥");
        } else {
            toast("removed from liked songs");
        }
    }

    // ───────────────────────────── selection ─────────────────────────────

    List<Track> libraryList() {
        return libSection == 0 ? library.liked() : library.history();
    }

    Track selectedTrack() {
        return switch (tab) {
            case SEARCH -> resultsState.sel < results.size() && results.get(resultsState.sel) instanceof Track t ? t : null;
            case QUEUE -> queueState.sel < queue.size() ? queue.get(queueState.sel) : null;
            case LIBRARY -> {
                List<Track> l = libraryList();
                yield libState.sel < l.size() ? l.get(libState.sel) : null;
            }
            case NOW -> playing;
        };
    }

    void activate() {
        switch (tab) {
            case SEARCH -> {
                if (resultsState.sel >= results.size()) return;
                Item it = results.get(resultsState.sel);
                if (it instanceof Playlist p) {
                    openPlaylist(p);
                } else if (it instanceof Track t) {
                    if (resultsTitle != null) {
                        List<Track> tracks = new ArrayList<>();
                        for (Item x : results) if (x instanceof Track tt) tracks.add(tt);
                        playFrom(tracks, tracks.indexOf(t));
                    } else {
                        playNow(t);
                    }
                }
            }
            case QUEUE -> playIndex(queueState.sel);
            case LIBRARY -> {
                List<Track> l = libraryList();
                if (libState.sel < l.size()) {
                    if (libSection == 0) playFrom(new ArrayList<>(l), libState.sel);
                    else playNow(l.get(libState.sel));
                }
            }
            case NOW -> togglePause();
        }
    }

    void enqueueSelected(boolean next) {
        if (tab == Tab.SEARCH && resultsState.sel < results.size() && results.get(resultsState.sel) instanceof Playlist p) {
            if (source == null) return;
            MusicSource src = source;
            toast("Loading “" + p.title() + "”…");
            io.execute(() -> {
                try {
                    MusicSource.Results r = src.playlist(p.url());
                    List<Track> tracks = new ArrayList<>();
                    for (Item x : r.items()) if (x instanceof Track t) tracks.add(t);
                    post(() -> enqueueAll(tracks, p.title()));
                } catch (Exception e) {
                    Log.error("enqueue playlist", e);
                    post(() -> toastError("Couldn't load “" + p.title() + "”: " + msg(e)));
                }
            });
            return;
        }
        Track t = selectedTrack();
        if (t != null && tab != Tab.QUEUE) enqueue(t, next);
    }

    private ListState listState() {
        return switch (tab) {
            case SEARCH -> resultsState;
            case QUEUE -> queueState;
            case LIBRARY -> libState;
            case NOW -> null;
        };
    }

    private int listSize() {
        return switch (tab) {
            case SEARCH -> results.size();
            case QUEUE -> queue.size();
            case LIBRARY -> libraryList().size();
            case NOW -> 0;
        };
    }

    void move(int d) {
        ListState st = listState();
        if (st == null) {
            volumeBy(-d * 0.05);
            return;
        }
        int n = listSize();
        st.sel = n == 0 ? 0 : Math.max(0, Math.min(n - 1, st.sel + d));
    }

    void moveTo(int i) {
        ListState st = listState();
        if (st == null) return;
        int n = listSize();
        st.sel = n == 0 ? 0 : Math.max(0, Math.min(n - 1, i));
    }

    /** Single click selects, double click plays. */
    void click(Tab t, int index) {
        ListState st = t == Tab.SEARCH ? resultsState : t == Tab.QUEUE ? queueState : libState;
        long now = System.nanoTime();
        boolean dbl = index == lastClickRow && now - lastClickNs < 400_000_000L;
        st.sel = index;
        typing = false;
        lastClickRow = index;
        lastClickNs = now;
        if (dbl) {
            lastClickRow = -1;
            activate();
        }
    }

    void selectTab(Tab t) {
        tab = t;
        typing = false;
        if (t == Tab.QUEUE && current >= 0) queueState.sel = current;
    }

    void cycleTheme(int d) {
        themeIdx = Math.floorMod(themeIdx + d, Theme.ALL.size());
        screen.invalidate();
        toast("theme · " + theme().name());
    }

    void toggleArtStyle() {
        ArtMode[] all = ArtMode.values();
        do artMode = all[(artMode.ordinal() + 1) % all.length];
        while (artMode == ArtMode.SHARP && !graphics);
        toast("cover art · " + artMode.name().toLowerCase());
    }

    void cycleViz(int d) {
        vizIdx = Math.floorMod(vizIdx + d, Visualizer.Mode.values().length);
        toast("visualizer · " + vizMode().label);
    }

    // ───────────────────────────── input ─────────────────────────────

    void onKey(Key k) {
        if (k.isCtrl('c') || k.isCtrl('q')) {
            quit();
            return;
        }
        if (splash) {
            if (!k.isMouse()) splash = false;
            return;
        }
        if (help) {
            if (!k.isMouse() || k.type() == Key.Type.MOUSE_DOWN) help = false;
            return;
        }
        if (k.isMouse()) {
            onMouse(k);
            return;
        }
        if (zen && (k.type() == Key.Type.ESC || k.is('z'))) {
            zen = false;
            return;
        }
        if (typing && typingKey(k)) return;

        switch (k.type()) {
            case ENTER -> activate();
            case ESC, BACKSPACE -> {
                if (tab == Tab.SEARCH && canGoBack()) goBack();
            }
            case TAB -> selectTab(Tab.values()[(tab.ordinal() + 1) % Tab.values().length]);
            case BACKTAB -> selectTab(Tab.values()[Math.floorMod(tab.ordinal() - 1, Tab.values().length)]);
            case UP -> move(-1);
            case DOWN -> move(1);
            case PAGE_UP -> move(-pageSize());
            case PAGE_DOWN -> move(pageSize());
            case HOME -> moveTo(0);
            case END -> moveTo(Integer.MAX_VALUE);
            case LEFT -> seekBy(k.shift() ? -30 : -5);
            case RIGHT -> seekBy(k.shift() ? 30 : 5);
            case DELETE -> removeSelected();
            case CHAR -> charKey(k);
            default -> {
            }
        }
    }

    private int pageSize() {
        return Math.max(1, screen.height() - 16);
    }

    private void charKey(Key k) {
        if (k.ctrl()) {
            switch (k.ch()) {
                case ' ' -> togglePause();
                case 'n' -> next();
                case 'p' -> prev();
                case 'l' -> screen.invalidate();
                default -> {
                }
            }
            return;
        }
        if (k.alt()) return;
        switch (k.ch()) {
            case 'q' -> quit();
            case ' ' -> togglePause();
            case 'n' -> next();
            case 'p' -> prev();
            case '+', '=' -> volumeBy(0.05);
            case '-', '_' -> volumeBy(-0.05);
            case 'm' -> toggleMute();
            case 'j' -> move(1);
            case 'k' -> move(-1);
            case 'g' -> moveTo(0);
            case 'G' -> moveTo(Integer.MAX_VALUE);
            case 'h' -> seekBy(-5);
            case 'l' -> seekBy(5);
            case ',', '<' -> seekBy(-30);
            case '.', '>' -> seekBy(30);
            case '/' -> focusSearch();
            case '1' -> selectTab(Tab.SEARCH);
            case '2' -> selectTab(Tab.QUEUE);
            case '3' -> selectTab(Tab.LIBRARY);
            case '4' -> selectTab(Tab.NOW);
            case 'i' -> toggleArtStyle();
            case 'v' -> cycleViz(1);
            case 'V' -> cycleViz(-1);
            case 't' -> cycleTheme(1);
            case 'T' -> cycleTheme(-1);
            case 'z' -> zen = !zen;
            case '?' -> help = true;
            case 's' -> toggleShuffle();
            case 'R' -> cycleRepeat();
            case 'x' -> toggleAutoplay();
            case 'f' -> toggleLike();
            case 'a' -> enqueueSelected(false);
            case 'A' -> enqueueSelected(true);
            case 'r' -> radioFromSelected();
            case 'd' -> removeSelected();
            case 'J' -> moveQueueItem(1);
            case 'K' -> moveQueueItem(-1);
            case 'C' -> {
                if (tab == Tab.QUEUE) clearQueue();
            }
            case 'o' -> {
                if (current >= 0) {
                    selectTab(Tab.QUEUE);
                    queueState.sel = current;
                }
            }
            case '[' -> {
                if (tab == Tab.SEARCH) cycleFilter(-1);
                else if (tab == Tab.LIBRARY) switchLibrary();
            }
            case ']' -> {
                if (tab == Tab.SEARCH) cycleFilter(1);
                else if (tab == Tab.LIBRARY) switchLibrary();
            }
            default -> {
            }
        }
    }

    void switchLibrary() {
        libSection = 1 - libSection;
        libState.sel = 0;
        libState.scroll = 0;
    }

    /** Line editing for the search box. Returns false to let the key fall through to normal handling. */
    private boolean typingKey(Key k) {
        switch (k.type()) {
            case CHAR -> {
                if (k.ctrl()) {
                    switch (k.ch()) {
                        case 'u' -> {
                            query.setLength(0);
                            cursor = 0;
                        }
                        case 'w' -> deleteWord();
                        case 'a' -> cursor = 0;
                        case 'e' -> cursor = query.length();
                        default -> {
                            return false;
                        }
                    }
                    return true;
                }
                if (k.alt()) return false;
                char[] chars = Character.toChars(k.ch());
                query.insert(cursor, chars);
                cursor += chars.length;
                return true;
            }
            case BACKSPACE -> {
                if (cursor > 0) {
                    int n = cursor > 1 && Character.isLowSurrogate(query.charAt(cursor - 1)) ? 2 : 1;
                    query.delete(cursor - n, cursor);
                    cursor -= n;
                } else if (query.isEmpty() && canGoBack()) {
                    return false;
                }
                return true;
            }
            case DELETE -> {
                if (cursor < query.length()) query.deleteCharAt(cursor);
                return true;
            }
            case LEFT -> {
                if (cursor > 0) cursor--;
                return true;
            }
            case RIGHT -> {
                if (cursor < query.length()) cursor++;
                return true;
            }
            case HOME -> {
                cursor = 0;
                return true;
            }
            case END -> {
                cursor = query.length();
                return true;
            }
            case ENTER -> {
                typing = false;
                if (!query.toString().isBlank()) runSearch();
                return true;
            }
            case ESC -> {
                typing = false;
                return true;
            }
            case TAB -> {
                cycleFilter(1);
                return true;
            }
            case BACKTAB -> {
                cycleFilter(-1);
                return true;
            }
            case UP, DOWN, PAGE_UP, PAGE_DOWN -> {
                typing = false;
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private void deleteWord() {
        int i = cursor;
        while (i > 0 && query.charAt(i - 1) == ' ') i--;
        while (i > 0 && query.charAt(i - 1) != ' ') i--;
        query.delete(i, cursor);
        cursor = i;
    }

    private void onMouse(Key k) {
        switch (k.type()) {
            case WHEEL_UP -> move(-3);
            case WHEEL_DOWN -> move(3);
            case MOUSE_DOWN, MOUSE_DRAG -> {
                boolean drag = k.type() == Key.Type.MOUSE_DRAG;
                for (int i = ui.hits.size() - 1; i >= 0; i--) {
                    Ui.Hit h = ui.hits.get(i);
                    if (h.contains(k.x(), k.y())) {
                        h.action().on(k.x(), k.y(), drag);
                        break;
                    }
                }
            }
            default -> {
            }
        }
    }

    // ───────────────────────────── headless snapshot ─────────────────────────────

    /** Renders each screen with fake data as plain text; for checking layout without a TTY. */
    static void snapshot(Main.Options o) {
        String[] wh = o.snapshot().split("x");
        App app = demo(o, Integer.parseInt(wh[0]), Integer.parseInt(wh[1]), null);
        for (String scene : SCENES) {
            app.scene(scene);
            System.out.println("── " + scene + " " + "─".repeat(Math.max(0, app.screen.width() - scene.length() - 4)));
            System.out.print(app.screen.dump());
        }
    }

    static final String[] SCENES = {"search-typing", "search", "queue", "library", "now", "radial", "zen", "help", "splash", "empty"};

    /** A headless app filled with sample data. {@code art} is an optional cover image for the playing track. */
    static App demo(Main.Options o, int w, int h, String art) {
        App app = new App(o, new int[]{w, h});
        app.splash = false;
        app.spectrum.demo(true);
        String[][] songs = {
                {"Get Lucky (feat. Pharrell Williams)", "Daft Punk", "369"},
                {"Instant Crush (feat. Julian Casablancas)", "Daft Punk", "337"},
                {"One More Time", "Daft Punk", "320"},
                {"Harder, Better, Faster, Stronger", "Daft Punk", "224"},
                {"Around the World", "Daft Punk", "429"},
                {"夜に駆ける", "YOASOBI", "261"},
                {"Digital Love", "Daft Punk", "301"},
                {"Lose Yourself to Dance", "Daft Punk", "353"},
                {"Something About Us", "Daft Punk", "231"},
                {"Veridis Quo", "Daft Punk", "344"},
                {"Starboy", "The Weeknd, Daft Punk", "230"},
                {"I Feel It Coming", "The Weeknd, Daft Punk", "269"}};
        for (int i = 0; i < 30; i++) {
            String[] s = songs[i % songs.length];
            app.results.add(new Track("id" + i, "https://www.youtube.com/watch?v=id" + i, s[0], s[1], null,
                    Long.parseLong(s[2]), i == 2 ? art : null));
        }
        app.results.add(3, new Playlist("u", "Random Access Memories", "Daft Punk", 13, null, true));
        for (int i = 0; i < 8; i++) app.queue.add((Track) app.results.get(i == 3 ? 12 : i));
        app.current = 2;
        app.playing = app.queue.get(2);
        if (art != null) app.art.request(art, Runnable::run, () -> {
        });
        app.format = "OPUS · 160 kbps";
        app.lastQuery = "daft punk";
        app.query.append("daft punk");
        app.cursor = app.query.length();
        app.library.toggleLike(app.queue.get(0));
        app.library.addHistory(app.queue.get(1));
        for (int f = 0; f < 40; f++) app.snapshotTick();
        return app;
    }

    /** Sets up one named scene and draws it into {@link #screen}. */
    void scene(String scene) {
        typing = scene.equals("search-typing");
        help = scene.equals("help");
        zen = scene.equals("zen");
        splash = scene.equals("splash");
        tab = switch (scene) {
            case "queue" -> Tab.QUEUE;
            case "library" -> Tab.LIBRARY;
            case "now", "radial" -> Tab.NOW;
            default -> Tab.SEARCH;
        };
        vizIdx = scene.equals("radial") ? Visualizer.Mode.RADIAL.ordinal() : scene.equals("zen") ? Visualizer.Mode.LED.ordinal() : 0;
        List<Item> saved = results;
        if (scene.equals("empty")) results = new ArrayList<>();
        if (scene.equals("queue")) toast("added to queue · Digital Love");
        else toast = null;
        for (int f = 0; f < 8; f++) snapshotTick();
        ui.draw(screen);
        results = saved;
    }

    private void snapshotTick() {
        try {
            Thread.sleep(30);
        } catch (InterruptedException ignored) {
        }
        tick();
    }
}
