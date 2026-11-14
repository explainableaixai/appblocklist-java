package com.alphaquantum.appblocklist;

import com.alphaquantum.appblocklist.AppBlockListException.InvalidIdException;
import com.alphaquantum.appblocklist.AppBlockListException.QuotaExceededException;
import com.alphaquantum.appblocklist.AppBlockListException.ServerException;
import com.alphaquantum.appblocklist.AppBlockListException.UnauthorizedException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client for the AppBlockList App Lookup API
 * ({@code GET https://www.appblocklist.com/api/v1/app?id=...}).
 *
 * <pre>{@code
 * AppBlockListClient client = AppBlockListClient.fromEnv();
 * AppInfo app = client.lookup("com.zhiliaoapp.musically");
 * if (app.isFound() && app.inCategory("Social Networking")) { ... }
 * }</pre>
 *
 * Instances are immutable and thread-safe. Share one per application.
 */
public final class AppBlockListClient {
    public static final String VERSION = "1.0.0";
    public static final String DEFAULT_BASE_URL = "https://www.appblocklist.com/api/v1";
    public static final String ENV_API_KEY = "APPBLOCKLIST_API_KEY";
    public static final String USER_AGENT = "appblocklist-java/" + VERSION + " (+https://www.appblocklist.com)";
    public static final int MAX_ID_LENGTH = 200;

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryWait;

    private AppBlockListClient(Builder b) {
        this.apiKey = b.apiKey == null ? "" : b.apiKey.trim();
        String base = b.baseUrl == null ? DEFAULT_BASE_URL : b.baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.timeout = b.timeout;
        this.maxRetries = b.maxRetries;
        this.retryWait = b.retryWait;
        this.http = b.httpClient != null ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Client with an explicit key and default settings. */
    public static AppBlockListClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /** Client with the key from the APPBLOCKLIST_API_KEY environment variable. */
    public static AppBlockListClient fromEnv() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Look up one app by iOS bundle ID, App Store ID (with or without the "id"
     * prefix) or Android package name. A 404 is returned as an AppInfo with
     * {@code isFound() == false}. Each 200 answer counts as one lookup.
     *
     * @throws InvalidIdException     400, or rejected locally before sending
     * @throws UnauthorizedException  401
     * @throws QuotaExceededException 403
     * @throws ServerException        500 after retries
     * @throws AppBlockListException  network error (status 0) or undocumented status
     */
    public AppInfo lookup(String id) {
        String clean = validateId(id);
        if (apiKey.isEmpty()) {
            throw new AppBlockListException(0, "No API key: pass one to the builder or set " + ENV_API_KEY);
        }
        long wait = retryWait.toMillis();
        for (int attempt = 0; ; attempt++) {
            try {
                return once(clean);
            } catch (AppBlockListException e) {
                if (!e.isTemporary() || attempt >= maxRetries) {
                    throw e;
                }
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AppBlockListException(0, "Interrupted while waiting to retry", ie);
            }
            wait *= 2;
        }
    }

    private AppInfo once(String id) {
        URI uri = URI.create(baseUrl + "/app?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("X-API-Key", apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AppBlockListException(0, "Request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppBlockListException(0, "Interrupted", e);
        }
        return parseResponse(resp.statusCode(), resp.body());
    }

    /**
     * Trim an ID and check it locally: non-empty, at most 200 characters, no
     * whitespace inside. The API does the full format check.
     */
    public static String validateId(String id) {
        String s = id == null ? "" : id.trim();
        if (s.isEmpty()) {
            throw new InvalidIdException("Empty id");
        }
        if (s.length() > MAX_ID_LENGTH) {
            throw new InvalidIdException("Id longer than " + MAX_ID_LENGTH + " characters");
        }
        for (int k = 0; k < s.length(); k++) {
            if (Character.isWhitespace(s.charAt(k))) {
                throw new InvalidIdException("Id contains whitespace");
            }
        }
        return s;
    }

    /**
     * Turn a status code and body into an AppInfo or an exception, exactly as
     * {@link #lookup(String)} does. Public so recorded answers can be replayed
     * in tests without network access.
     */
    @SuppressWarnings("unchecked")
    public static AppInfo parseResponse(int status, String body) {
        Map<String, Object> m = null;
        try {
            Object parsed = Json.parse(body == null ? "" : body);
            if (parsed instanceof Map) {
                m = (Map<String, Object>) parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // handled below
        }
        if (status == 200) {
            if (m == null) {
                throw new AppBlockListException(200, "Response was not a JSON object");
            }
            return AppInfo.fromMap(m, false);
        }
        if (status == 404) {
            if (m == null) {
                m = Collections.singletonMap("error", body == null ? "" : body.trim());
            }
            return AppInfo.fromMap(m, true);
        }
        String msg = m != null ? Json.str(m, "error") : "";
        if (msg.isEmpty()) {
            msg = body == null || body.trim().isEmpty() ? "HTTP " + status : body.trim();
        }
        switch (status) {
            case 400:
                throw new InvalidIdException(msg);
            case 401:
                throw new UnauthorizedException(msg);
            case 403:
                throw new QuotaExceededException(msg, m == null ? null : Json.longOrNull(m, "monthly_lookups"));
            default:
                if (status >= 500) {
                    throw new ServerException(status, msg);
                }
                throw new AppBlockListException(status, msg);
        }
    }

    /**
     * Look up many IDs one after another, as the service asks. Blank and
     * repeated IDs are skipped. A 400 or 500 for one ID is recorded in its
     * outcome; a 401 or 403 stops the loop and is thrown.
     *
     * @param pause pause between requests, may be {@link Duration#ZERO}
     */
    public List<LookupOutcome> lookupMany(Iterable<String> ids, Duration pause) {
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : ids) {
            if (raw != null && !raw.trim().isEmpty()) {
                unique.add(raw.trim());
            }
        }
        List<LookupOutcome> out = new ArrayList<>(unique.size());
        for (String id : unique) {
            if (!out.isEmpty() && !pause.isZero()) {
                try {
                    Thread.sleep(pause.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return out;
                }
            }
            try {
                out.add(new LookupOutcome(id, lookup(id), null));
            } catch (UnauthorizedException | QuotaExceededException e) {
                throw e;
            } catch (AppBlockListException e) {
                if (apiKey.isEmpty()) {
                    throw e;
                }
                out.add(new LookupOutcome(id, null, e));
            }
        }
        return out;
    }

    /** One entry in the result of {@link #lookupMany(Iterable, Duration)}. */
    public static final class LookupOutcome {
        private final String id;
        private final AppInfo app;
        private final AppBlockListException error;

        LookupOutcome(String id, AppInfo app, AppBlockListException error) {
            this.id = id;
            this.app = app;
            this.error = error;
        }

        public String getId() { return id; }

        /** The answer, or null when {@link #getError()} is set. */
        public AppInfo getApp() { return app; }

        /** The error for this ID, or null. */
        public AppBlockListException getError() { return error; }

        public boolean isOk() { return error == null; }
    }

    /** Builder for {@link AppBlockListClient}. */
    public static final class Builder {
        private String apiKey = System.getenv(ENV_API_KEY);
        private String baseUrl = DEFAULT_BASE_URL;
        private HttpClient httpClient;
        private Duration timeout = Duration.ofSeconds(30);
        private int maxRetries = 2;
        private Duration retryWait = Duration.ofSeconds(1);

        private Builder() {
        }

        /** API key; defaults to the APPBLOCKLIST_API_KEY environment variable. */
        public Builder apiKey(String apiKey) {
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                this.apiKey = apiKey;
            }
            return this;
        }

        /** API root, e.g. a local mock server in tests. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Your own java.net.http.HttpClient (proxy, executor, SSL context). */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** Per-request timeout. Default 30 seconds. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Retries for 5xx and network errors, and the first pause (doubled each time). */
        public Builder retries(int maxRetries, Duration firstWait) {
            this.maxRetries = Math.max(0, maxRetries);
            this.retryWait = firstWait;
            return this;
        }

        public AppBlockListClient build() {
            return new AppBlockListClient(this);
        }
    }
}
