package com.infraleap.leaderboards.stern.service;

import com.infraleap.leaderboards.config.LeaderboardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SternAuthService {

    private static final Logger log = LoggerFactory.getLogger(SternAuthService.class);
    private static final String INSIDER_BASE = "https://insider.sternpinball.com";
    private static final String LOGIN_URL = INSIDER_BASE + "/login";
    private static final Duration AUTH_EXPIRY = Duration.ofMinutes(30);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("spb-insider-token=([^;]+)");
    private static final Pattern SCRIPT_SRC_PATTERN = Pattern.compile("src=\"(/_next/static/chunks/[^\"]+\\.js)\"");
    private static final Pattern LOGIN_ACTION_PATTERN =
            Pattern.compile("createServerReference\\)?\\(\"([a-f0-9]{40,64})\"[^)]+\"performLogin\"");

    // Since November 2026 Insider signs in through OIDC (Descope, authorization code + PKCE) and stores
    // the access token in the spb-insider-token cookie. We keep a refresh token and trade it for access
    // tokens. The values below are the ones the login page shipped with; they are re-read from the page
    // so a Stern redeploy that changes them doesn't break us.
    private static final String DEFAULT_DISCOVERY_URL =
            "https://auth.sternpinball.com/P3HEU9eRz5zrjI8c28n88adIDzyI/.well-known/openid-configuration";
    private static final String DEFAULT_CLIENT_ID = "P3HEU9eRz5zrjI8c28n88adIDzyI";
    private static final Pattern DISCOVERY_URL_PATTERN =
            Pattern.compile("discoveryUrl:\"(https://[^\"]+/\\.well-known/openid-configuration)\"");
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("clientId:\"([A-Za-z0-9_-]{8,})\"");
    private static final Duration ACCESS_TOKEN_MARGIN = Duration.ofMinutes(2);

    private final LeaderboardProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private volatile String token;
    private volatile String cookies;
    private volatile Instant lastAuthTime;
    private volatile Duration authExpiry = AUTH_EXPIRY;
    private volatile String loginActionHash;
    private volatile String oidcClientId;
    private volatile String oidcTokenEndpoint;

    public SternAuthService(LeaderboardProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public synchronized boolean login() {
        if (oidcRefreshLogin()) {
            return true;
        }
        return passwordActionLogin();
    }

    /**
     * Trades the stored refresh token for a new access token at Stern's OIDC token endpoint.
     * The rotated refresh token, if the server sends one, replaces the stored one.
     */
    private boolean oidcRefreshLogin() {
        String refreshToken = loadRefreshToken();
        if (refreshToken == null) {
            log.warn("No Stern refresh token configured (leaderboards.stern-refresh-token or {}); "
                    + "falling back to the old password login", props.sternTokenStore());
            return false;
        }

        try {
            if (!resolveOidcEndpoints()) {
                return false;
            }
            String form = "grant_type=refresh_token"
                    + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                    + "&client_id=" + URLEncoder.encode(oidcClientId, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(oidcTokenEndpoint))
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Accept", "application/json")
                            .timeout(Duration.ofSeconds(30))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // Don't log the body verbatim, it may echo the token; the error code is enough.
                String error = "";
                try {
                    error = objectMapper.readTree(response.body()).path("error").asString("");
                } catch (JacksonException ignored) {
                    // not JSON
                }
                log.error("Stern token refresh failed - status: {}, error: {}. A new refresh token is needed "
                        + "(sign in at insider.sternpinball.com and copy it, see README)", response.statusCode(), error);
                return false;
            }

            JsonNode json = objectMapper.readTree(response.body());
            String accessToken = json.path("access_token").asString(null);
            if (accessToken == null || accessToken.isBlank()) {
                log.error("Stern token refresh returned no access_token");
                return false;
            }
            String rotated = json.path("refresh_token").asString(null);
            if (rotated != null && !rotated.isBlank() && !rotated.equals(refreshToken)) {
                storeRefreshToken(rotated);
            }
            long expiresIn = json.path("expires_in").asLong(0);

            this.token = accessToken;
            this.cookies = "spb-insider-token=" + accessToken;
            this.lastAuthTime = Instant.now();
            this.authExpiry = expiresIn > 0
                    ? Duration.ofSeconds(expiresIn).minus(ACCESS_TOKEN_MARGIN)
                    : AUTH_EXPIRY;
            if (authExpiry.isNegative() || authExpiry.isZero()) authExpiry = Duration.ofSeconds(expiresIn / 2);
            if (authExpiry.compareTo(AUTH_EXPIRY) > 0) authExpiry = AUTH_EXPIRY;
            log.info("Stern authentication successful via OIDC refresh token (access token valid {} s)", expiresIn);
            return true;
        } catch (Exception e) {
            log.error("Stern OIDC refresh error", e);
            return false;
        }
    }

    /** Reads client id and discovery URL from the login page (falling back to known values), then the token endpoint. */
    private boolean resolveOidcEndpoints() throws IOException, InterruptedException {
        if (oidcTokenEndpoint != null && oidcClientId != null) {
            return true;
        }
        String discoveryUrl = DEFAULT_DISCOVERY_URL;
        String clientId = DEFAULT_CLIENT_ID;
        for (String chunk : loginPageChunks()) {
            Matcher d = DISCOVERY_URL_PATTERN.matcher(chunk);
            Matcher c = CLIENT_ID_PATTERN.matcher(chunk);
            if (d.find() && c.find()) {
                discoveryUrl = d.group(1);
                clientId = c.group(1);
                break;
            }
        }

        HttpResponse<String> discovery = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(discoveryUrl))
                        .GET()
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (discovery.statusCode() != 200) {
            log.error("Stern OIDC discovery {} returned status {}", discoveryUrl, discovery.statusCode());
            return false;
        }
        String tokenEndpoint = objectMapper.readTree(discovery.body()).path("token_endpoint").asString(null);
        if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
            log.error("Stern OIDC discovery {} has no token_endpoint", discoveryUrl);
            return false;
        }
        this.oidcClientId = clientId;
        this.oidcTokenEndpoint = tokenEndpoint;
        log.info("Stern OIDC client {} with token endpoint {}", clientId, tokenEndpoint);
        return true;
    }

    /** The refresh token from the token store, or the configured one on first use. */
    private String loadRefreshToken() {
        Path store = Path.of(props.sternTokenStore());
        try {
            if (Files.isReadable(store)) {
                String stored = Files.readString(store).trim();
                if (!stored.isEmpty()) return stored;
            }
        } catch (IOException e) {
            log.warn("Cannot read Stern refresh token store {}: {}", store, e.getMessage());
        }
        String configured = props.sternRefreshToken();
        return configured == null || configured.isBlank() ? null : configured.trim();
    }

    /** Keeps a rotated refresh token across restarts; written atomically and readable by the owner only. */
    private void storeRefreshToken(String refreshToken) {
        Path store = Path.of(props.sternTokenStore());
        try {
            Path dir = store.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "refresh-token", ".tmp");
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
            Files.writeString(tmp, refreshToken);
            Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Cannot store rotated Stern refresh token in {}: {}", store, e.getMessage());
        }
    }

    /** The bodies of all JavaScript chunks the login page loads. */
    private List<String> loginPageChunks() throws IOException, InterruptedException {
        HttpResponse<String> page = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(LOGIN_URL))
                        .GET()
                        .header("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                        .header("Accept", "text/html")
                        .timeout(Duration.ofSeconds(15))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (page.statusCode() != 200) {
            log.error("Login page returned status {}", page.statusCode());
            return List.of();
        }
        Set<String> chunkPaths = new LinkedHashSet<>();
        Matcher m = SCRIPT_SRC_PATTERN.matcher(page.body());
        while (m.find()) chunkPaths.add(m.group(1));

        List<String> bodies = new ArrayList<>();
        for (String path : chunkPaths) {
            HttpResponse<String> chunk = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(INSIDER_BASE + path))
                            .GET()
                            .timeout(Duration.ofSeconds(15))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (chunk.statusCode() == 200) bodies.add(chunk.body());
        }
        return bodies;
    }

    /** The pre-November-2026 login through the performLogin Next.js server action. */
    private boolean passwordActionLogin() {
        this.authExpiry = AUTH_EXPIRY;
        String username = props.sternUsername();
        String password = props.sternPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.error("STERN_USERNAME and STERN_PASSWORD must be configured");
            return false;
        }

        try {
            String actionHash = getLoginActionHash();
            if (actionHash == null) {
                log.error("Could not resolve performLogin Next-Action hash from Stern login page");
                return false;
            }

            String body = "[\"" + escapeJson(username) + "\",\"" + escapeJson(password) + "\"]";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LOGIN_URL))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                    .header("Accept", "text/x-component")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Referer", "https://insider.sternpinball.com/login")
                    .header("Next-Action", actionHash)
                    .header("Next-Router-State-Tree", "%5B%22%22%2C%7B%22children%22%3A%5B%22login%22%2C%7B%22children%22%3A%5B%22__PAGE__%22%2C%7B%7D%2C%22%2Flogin%22%2C%22refresh%22%5D%7D%5D%7D%2Cnull%2Cnull%2Ctrue%5D")
                    .header("Content-Type", "text/plain;charset=UTF-8")
                    .header("Origin", "https://insider.sternpinball.com")
                    .header("DNT", "1")
                    .header("Sec-GPC", "1")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Extract token from Set-Cookie headers
            String extractedToken = null;
            StringBuilder cookieBuilder = new StringBuilder();
            for (String setCookie : response.headers().allValues("set-cookie")) {
                if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                cookieBuilder.append(setCookie.split(";")[0]);
                Matcher m = TOKEN_PATTERN.matcher(setCookie);
                if (m.find()) {
                    extractedToken = m.group(1);
                }
            }

            // Check for authentication success in response body
            boolean authenticated = false;
            String responseBody = response.body();
            if (responseBody != null) {
                for (String line : responseBody.split("\n")) {
                    if (line.contains("\"authenticated\"")) {
                        if (line.contains("\"authenticated\":true") || line.contains("\"authenticated\": true")) {
                            authenticated = true;
                            break;
                        }
                    }
                }
            }

            if (response.statusCode() == 200 && (authenticated || extractedToken != null)) {
                this.token = extractedToken;
                this.cookies = cookieBuilder.toString();
                this.lastAuthTime = Instant.now();
                log.info("Stern authentication successful");
                return true;
            } else if (response.statusCode() == 303 || response.statusCode() == 302) {
                // Stern may redirect on success — check for token in cookies regardless
                if (extractedToken != null) {
                    this.token = extractedToken;
                    this.cookies = cookieBuilder.toString();
                    this.lastAuthTime = Instant.now();
                    log.info("Stern authentication successful via redirect (status {})", response.statusCode());
                    return true;
                }
                log.error("Stern authentication redirect but no token - status: {}", response.statusCode());
                return false;
            } else {
                // 200 with no token typically means Stern redeployed and the action hash is stale —
                // drop the cache so the next attempt re-resolves it.
                this.loginActionHash = null;
                log.error("Stern authentication failed - status: {}, authenticated: {}, hasToken: {}",
                        response.statusCode(), authenticated, extractedToken != null);
                return false;
            }
        } catch (Exception e) {
            log.error("Stern login error", e);
            return false;
        }
    }

    private String getLoginActionHash() {
        String cached = this.loginActionHash;
        if (cached != null) return cached;
        String resolved = resolveLoginActionHash();
        if (resolved != null) this.loginActionHash = resolved;
        return resolved;
    }

    private String resolveLoginActionHash() {
        try {
            HttpResponse<String> page = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(LOGIN_URL))
                            .GET()
                            .header("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                            .header("Accept", "text/html")
                            .timeout(Duration.ofSeconds(15))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (page.statusCode() != 200) {
                log.error("Login page returned status {} while resolving action hash", page.statusCode());
                return null;
            }
            Set<String> chunkPaths = new LinkedHashSet<>();
            Matcher m = SCRIPT_SRC_PATTERN.matcher(page.body());
            while (m.find()) chunkPaths.add(m.group(1));

            for (String path : chunkPaths) {
                HttpResponse<String> chunk = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(INSIDER_BASE + path))
                                .GET()
                                .timeout(Duration.ofSeconds(15))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (chunk.statusCode() != 200) continue;
                Matcher hit = LOGIN_ACTION_PATTERN.matcher(chunk.body());
                if (hit.find()) {
                    String hash = hit.group(1);
                    log.info("Resolved performLogin Next-Action hash {} from {}", hash, path);
                    return hash;
                }
            }
            log.error("performLogin Server Action not found in any login-page chunk ({} scanned)", chunkPaths.size());
            return null;
        } catch (Exception e) {
            log.error("Failed to resolve performLogin action hash", e);
            return null;
        }
    }

    public boolean isExpired() {
        return lastAuthTime == null || Instant.now().isAfter(lastAuthTime.plus(authExpiry));
    }

    public synchronized void refreshIfNeeded() {
        if (isExpired()) {
            login();
        }
    }

    public String getToken() {
        refreshIfNeeded();
        return token;
    }

    public String getCookies() {
        refreshIfNeeded();
        return cookies;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
