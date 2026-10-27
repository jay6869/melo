package melo.source;

/** A row in a list: either a playable track or a collection (album / playlist) you can open. */
public sealed interface Item permits Track, Playlist {
    String title();

    String subtitle();

    /** Right-aligned detail: duration or track count. */
    String meta();

    String thumb();
}
