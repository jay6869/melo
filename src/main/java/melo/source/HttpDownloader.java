package melo.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import static java.nio.charset.StandardCharsets.UTF_8;

/** NewPipeExtractor's HTTP transport, on top of java.net.http (no OkHttp needed). */
public final class HttpDownloader extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        String url = request.url();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        byte[] data = request.dataToSend();
        b.method(request.httpMethod(), data == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(data));

        boolean hasAgent = false;
        for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
            String name = e.getKey();
            if (RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) continue;
            if (name.equalsIgnoreCase("User-Agent")) hasAgent = true;
            for (String v : e.getValue()) b.header(name, v);
        }
        if (!hasAgent) b.header("User-Agent", USER_AGENT);

        HttpResponse<byte[]> r;
        try {
            r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (r.statusCode() == 429) throw new ReCaptchaException("reCaptcha challenge requested", url);

        byte[] body = r.body();
        if (r.headers().firstValue("content-encoding").orElse("").equalsIgnoreCase("gzip")) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = in.readAllBytes();
            }
        }
        return new Response(r.statusCode(), "", r.headers().map(), new String(body, UTF_8), r.uri().toString());
    }
}
