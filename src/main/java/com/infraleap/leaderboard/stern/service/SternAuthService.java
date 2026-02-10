package com.infraleap.leaderboard.stern.service;

import com.infraleap.leaderboard.config.LeaderboardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SternAuthService {

    private static final Logger log = LoggerFactory.getLogger(SternAuthService.class);
    private static final String LOGIN_URL = "https://insider.sternpinball.com/login";
    private static final Duration AUTH_EXPIRY = Duration.ofMinutes(30);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("spb-insider-token=([^;]+)");

    private final LeaderboardProperties props;
    private final HttpClient httpClient;

    private volatile String token;
    private volatile String cookies;
    private volatile Instant lastAuthTime;

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
            String body = "[\"" + escapeJson(username) + "\",\"" + escapeJson(password) + "\"]";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LOGIN_URL))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                    .header("Accept", "text/x-component")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Referer", "https://insider.sternpinball.com/login")
                    .header("Next-Action", "9d2cf818afff9e2c69368771b521d93585a10433")
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
                log.error("Stern authentication failed - status: {}, authenticated: {}, hasToken: {}",
                        response.statusCode(), authenticated, extractedToken != null);
                return false;
            }
        } catch (Exception e) {
            log.error("Stern login error", e);
            return false;
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
