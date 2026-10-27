package melo.source;

public record Playlist(String url, String title, String author, long count, String thumb, boolean album) implements Item {
    @Override
    public String subtitle() {
        return (album ? "Album" : "Playlist") + (author == null || author.isBlank() ? "" : " · " + author);
    }

    @Override
    public String meta() {
        return count > 0 ? count + " songs" : "";
    }
}
