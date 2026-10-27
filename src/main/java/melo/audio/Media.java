package melo.audio;

/**
 * Something ffmpeg can open.
 *
 * @param url       http(s) stream URL or local file path
 * @param userAgent user agent to send (some YouTube stream URLs are bound to the client that produced them)
 * @param duration  seconds, or 0 if unknown
 * @param remote    whether to enable ffmpeg's reconnect options
 */
public record Media(String url, String userAgent, double duration, boolean remote) {
}
