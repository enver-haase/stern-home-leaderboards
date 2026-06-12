package com.infraleap.leaderboards.stern.service;

import com.infraleap.leaderboards.config.LeaderboardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
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

    private final LeaderboardProperties props;
    private final HttpClient httpClient;

    private volatile String token;
    private volatile String cookies;
    private volatile Instant lastAuthTime;
    private volatile String loginActionHash;

    public SternAuthService(LeaderboardProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public synchronized boolean login() {
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
        return lastAuthTime == null || Instant.now().isAfter(lastAuthTime.plus(AUTH_EXPIRY));
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
