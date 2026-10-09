package inventoryreader.ir;

import java.net.http.HttpClient;
import java.time.Duration;

/** The one HTTP client the mod uses (recipe download, mayor check). */
public final class Http {
    public static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private Http() {}
}
