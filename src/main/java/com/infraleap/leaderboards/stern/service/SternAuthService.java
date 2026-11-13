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
    private static final Pattern SCRIPT_SRC_PATTERN = Pattern.compile("src=\"(/_next/static/chunks/[^\"]+\\.js)\"");

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
    // A browser login costs ~15 s and ~550 MB; don't retry it on every API call when it fails.
    private static final Duration BROWSER_LOGIN_COOLDOWN = Duration.ofMinutes(15);

    private final LeaderboardProperties props;
    private final HttpClient httpClient;
    private final SternBrowserLogin browserLogin;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private volatile String token;
    private volatile String cookies;
    private volatile Instant lastAuthTime;
    private volatile Duration authExpiry = AUTH_EXPIRY;
    private volatile String refreshToken;
    private volatile Instant lastBrowserLogin;
    private volatile String oidcClientId;
    private volatile String oidcTokenEndpoint;

    public SternAuthService(LeaderboardProperties props, SternBrowserLogin browserLogin) {
        this.props = props;
        this.browserLogin = browserLogin;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public synchronized boolean login() {
        if (oidcRefreshLogin()) {
            return true;
        }
        return browserLogin() && oidcRefreshLogin();
    }

    /** Gets a fresh refresh token by signing in with username and password in a headless browser. */
    private boolean browserLogin() {
        String username = props.sternUsername();
        String password = props.sternPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.error("STERN_USERNAME and STERN_PASSWORD must be configured");
            return false;
        }
        Instant last = lastBrowserLogin;
        if (last != null && Instant.now().isBefore(last.plus(BROWSER_LOGIN_COOLDOWN))) {
            log.warn("Stern browser login failed at {}; next attempt after {}", last, last.plus(BROWSER_LOGIN_COOLDOWN));
            return false;
        }
        lastBrowserLogin = Instant.now();
        log.info("Signing in to Stern Insider in a headless browser");
        return browserLogin.obtainRefreshToken(username, password)
                .map(t -> {
                    storeRefreshToken(t);
                    lastBrowserLogin = null;
                    return true;
                })
                .orElse(false);
    }

    /**
     * Trades the stored refresh token for a new access token at Stern's OIDC token endpoint.
     * The rotated refresh token, if the server sends one, replaces the stored one.
     */
    private boolean oidcRefreshLogin() {
        String refreshToken = loadRefreshToken();
        if (refreshToken == null) {
            log.info("No Stern refresh token yet ({}); signing in with username and password",
                    props.sternTokenStore());
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
                log.error("Stern token refresh failed - status: {}, error: {}; signing in again",
                        response.statusCode(), error);
                // The token is spent; a changed client id or endpoint is re-read from the login page next time.
                this.refreshToken = null;
                this.oidcClientId = null;
                this.oidcTokenEndpoint = null;
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

    /** The current refresh token: the last one seen, else the token store, else the configured one. */
    private String loadRefreshToken() {
        String current = refreshToken;
        if (current != null) return current;
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

    /**
     * Keeps a new refresh token in memory and, across restarts, in the token store (written atomically,
     * readable by the owner only). If the file can't be written the app keeps working until it restarts.
     */
    private void storeRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
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
}
