package melo.source;

import melo.audio.Media;

/**
 * A track resolved to something playable.
 *
 * @param track  the track, enriched with whatever the stream page told us (duration, artwork…)
 * @param format human-readable stream description, e.g. "OPUS · 160 kbps"
 */
public record Resolved(Media media, Track track, String format) {
}
