package melo.source;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import melo.Log;
import melo.audio.Media;

/** YouTube Music, through NewPipeExtractor: search, albums/playlists, stream resolution and radio. */
public final class MusicSource {
    public enum Filter {
        SONGS("Songs", "music_songs"),
        VIDEOS("Videos", "music_videos"),
        ALBUMS("Albums", "music_albums"),
        PLAYLISTS("Playlists", "music_playlists");

        public final String label, id;

        Filter(String label, String id) {
            this.label = label;
            this.id = id;
        }
    }

    /** Fetches the next page of a list. */
    public interface More {
        Results fetch() throws Exception;
    }

    /** @param title set when the results are the contents of an album/playlist */
    public record Results(String title, List<Item> items, More more) {
    }

    private static final Pattern VIDEO_ID = Pattern.compile("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/)([\\w-]{11})");
    private static final Pattern LIST_ID = Pattern.compile("[?&]list=([\\w-]+)");

    private final StreamingService yt;

    public MusicSource() {
        NewPipe.init(new HttpDownloader(), new Localization("en", "US"), new ContentCountry("US"));
        yt = ServiceList.YouTube;
    }

    public Results search(String query, Filter filter) throws Exception {
        String q = query.strip();
        if (q.startsWith("http://") || q.startsWith("https://") || q.startsWith("music.youtube") || q.startsWith("youtu")) {
            return openUrl(q.startsWith("http") ? q : "https://" + q);
        }
        SearchExtractor ex = yt.getSearchExtractor(q, List.of(filter.id), "");
        ex.fetchPage();
        ListExtractor.InfoItemsPage<InfoItem> page = ex.getInitialPage();
        return new Results(null, convert(page.getItems(), filter == Filter.ALBUMS), nextSearch(ex, page, filter));
    }

    private More nextSearch(SearchExtractor ex, ListExtractor.InfoItemsPage<InfoItem> page, Filter filter) {
        if (!page.hasNextPage()) return null;
        Page next = page.getNextPage();
        return () -> {
            ListExtractor.InfoItemsPage<InfoItem> p = ex.getPage(next);
            return new Results(null, convert(p.getItems(), filter == Filter.ALBUMS), nextSearch(ex, p, filter));
        };
    }

    /** Pasted links: a watch URL becomes a single result, a playlist URL opens the playlist. */
    private Results openUrl(String url) throws Exception {
        boolean hasVideo = VIDEO_ID.matcher(url).find();
        Matcher list = LIST_ID.matcher(url);
        if (list.find() && !hasVideo) return playlist(url);
        StreamInfo info = StreamInfo.getInfo(yt, url);
        String id = videoId(info.getUrl());
        if (id == null) id = videoId(url);
        Track t = new Track(id, watchUrl(id), info.getName(), cleanArtist(info.getUploaderName()), null,
                info.getDuration(), bestThumb(info.getThumbnails(), id));
        return new Results(null, List.of(t), null);
    }

    public Results playlist(String url) throws Exception {
        PlaylistInfo info = PlaylistInfo.getInfo(yt, url);
        String name = info.getName().replaceFirst("^(Album|Single|EP) [–-] ", "");
        String title = name + (info.getUploaderName() == null || info.getUploaderName().isBlank()
                ? "" : " · " + cleanArtist(info.getUploaderName()));
        return new Results(title, new ArrayList<>(convert(info.getRelatedItems(), false)),
                info.hasNextPage() ? morePlaylist(url, info.getNextPage(), title) : null);
    }

    private More morePlaylist(String url, Page page, String title) {
        return () -> {
            ListExtractor.InfoItemsPage<StreamInfoItem> p = PlaylistInfo.getMoreItems(yt, url, page);
            return new Results(title, convert(p.getItems(), false),
                    p.hasNextPage() ? morePlaylist(url, p.getNextPage(), title) : null);
        };
    }

    public Resolved resolve(Track t) throws Exception {
        StreamInfo info = StreamInfo.getInfo(yt, t.url());
        AudioStream best = null;
        long bestScore = Long.MIN_VALUE;
        for (AudioStream a : info.getAudioStreams()) {
            if (!a.isUrl()) continue;
            long score = a.getAverageBitrate();
            if (a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) score -= 10_000;
            if (score > bestScore) {
                best = a;
                bestScore = score;
            }
        }
        String url, format;
        if (best != null) {
            url = best.getContent();
            format = describe(best);
        } else if (info.getHlsUrl() != null && !info.getHlsUrl().isEmpty()) {
            url = info.getHlsUrl();
            format = "HLS";
        } else {
            throw new IOException("no playable audio stream");
        }
        Track rich = t.withDetails(info.getName(), cleanArtist(info.getUploaderName()), info.getDuration(),
                bestThumb(info.getThumbnails(), t.id()));
        double dur = info.getDuration() > 0 ? info.getDuration() : Math.max(0, t.duration());
        return new Resolved(new Media(url, userAgentFor(url), dur, true), rich, format);
    }

    /** A YouTube Music-style radio: the song's mix, falling back to related videos. */
    public List<Track> radio(Track seed) throws Exception {
        Map<String, Track> out = new LinkedHashMap<>();
        for (String list : List.of("RDAMVM" + seed.id(), "RD" + seed.id())) {
            try {
                PlaylistInfo mix = PlaylistInfo.getInfo(yt, watchUrl(seed.id()) + "&list=" + list);
                for (StreamInfoItem it : mix.getRelatedItems()) add(out, track(it));
                if (out.size() > 5) break;
            } catch (Exception e) {
                Log.info("mix " + list + " unavailable: " + e.getMessage());
            }
        }
        if (out.size() < 5) {
            StreamInfo info = StreamInfo.getInfo(yt, seed.url());
            for (InfoItem it : info.getRelatedItems())
                if (it instanceof StreamInfoItem s) add(out, track(s));
        }
        out.remove(seed.id());
        out.values().removeIf(t -> t.duration() <= 0 || t.duration() > 15 * 60);
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, Track> out, Track t) {
        if (t != null) out.putIfAbsent(t.id(), t);
    }

    private List<Item> convert(List<? extends InfoItem> items, boolean albums) {
        List<Item> out = new ArrayList<>();
        for (InfoItem it : items) {
            if (it instanceof StreamInfoItem s) {
                Track t = track(s);
                if (t != null) out.add(t);
            } else if (it instanceof PlaylistInfoItem p) {
                out.add(new Playlist(p.getUrl(), p.getName(), cleanArtist(p.getUploaderName()), p.getStreamCount(),
                        bestThumb(p.getThumbnails(), null), albums));
            }
        }
        return out;
    }

    private static Track track(StreamInfoItem s) {
        String id = videoId(s.getUrl());
        if (id == null) return null;
        return new Track(id, watchUrl(id), s.getName(), cleanArtist(s.getUploaderName()), null, s.getDuration(),
                bestThumb(s.getThumbnails(), id));
    }

    static String videoId(String url) {
        if (url == null) return null;
        Matcher m = VIDEO_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    static String watchUrl(String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }

    static String cleanArtist(String s) {
        if (s == null) return "";
        s = s.strip();
        if (s.endsWith(" - Topic")) s = s.substring(0, s.length() - 8);
        return s;
    }

    /** Largest thumbnail; YouTube Music artwork URLs are rewritten to a big JPEG. */
    static String bestThumb(List<Image> images, String videoId) {
        Image best = null;
        long bestArea = -1;
        if (images != null) {
            for (Image img : images) {
                long area = (long) Math.max(0, img.getWidth()) * Math.max(0, img.getHeight());
                if (best == null || area >= bestArea) {
                    best = img;
                    bestArea = area;
                }
            }
        }
        if (best != null) {
            String url = best.getUrl();
            if (url.contains("googleusercontent.com") && url.lastIndexOf('=') > url.lastIndexOf('/')) {
                url = url.substring(0, url.lastIndexOf('=')) + "=w544-h544-l90-rj";
            }
            return url;
        }
        return videoId == null ? null : "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    private static String describe(AudioStream a) {
        String f = a.getFormat() == null ? "" : a.getFormat().name();
        String codec = f.contains("OPUS") ? "OPUS" : f.contains("M4A") || f.contains("AAC") ? "AAC"
                : f.isEmpty() ? "AUDIO" : f;
        int kbps = a.getAverageBitrate();
        return kbps > 0 ? codec + " · " + kbps + " kbps" : codec;
    }

    /**
     * Stream URLs carry the client that requested them ({@code c=ANDROID}, {@code c=IOS}, …) and
     * googlevideo checks the user agent against it.
     */
    static String userAgentFor(String url) {
        Matcher m = Pattern.compile("[?&]c=([A-Z_]+)").matcher(url);
        String client = m.find() ? m.group(1) : "";
        String ua = null;
        if (client.startsWith("ANDROID")) ua = helperAgent("getAndroidUserAgent");
        else if (client.startsWith("IOS")) ua = helperAgent("getIosUserAgent");
        else if (client.startsWith("VISIONOS")) ua = helperAgent("getVisionOsUserAgent");
        return ua != null ? ua : HttpDownloader.USER_AGENT;
    }

    private static String helperAgent(String method) {
        try {
            Class<?> c = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper");
            for (Method m : c.getMethods()) {
                if (!m.getName().equals(method) || m.getParameterCount() > 1) continue;
                Object r = m.getParameterCount() == 0 ? m.invoke(null) : m.invoke(null, (Object) null);
                if (r instanceof String s) return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
