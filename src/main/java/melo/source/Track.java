package melo.source;

/**
 * @param id       YouTube video id, or "file:" + absolute path for local files
 * @param url      canonical watch URL or local path
 * @param duration seconds, or a value <= 0 when unknown
 */
public record Track(String id, String url, String title, String artist, String album, long duration, String thumb)
        implements Item {

    public boolean local() {
        return id.startsWith("file:");
    }

    @Override
    public String subtitle() {
        if (album != null && !album.isBlank()) return artist + " · " + album;
        return artist == null ? "" : artist;
    }

    @Override
    public String meta() {
        return duration > 0 ? Fmt.time(duration) : "";
    }

    public Track withDetails(String title, String artist, long duration, String thumb) {
        return new Track(id, url,
                blank(this.title) ? title : this.title,
                blank(this.artist) ? artist : this.artist,
                album,
                this.duration > 0 ? this.duration : duration,
                this.thumb == null ? thumb : this.thumb);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
