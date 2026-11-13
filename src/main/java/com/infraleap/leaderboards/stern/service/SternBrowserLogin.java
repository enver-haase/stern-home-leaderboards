package com.infraleap.leaderboards.stern.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.impl.driver.Driver;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Signs in to Stern Insider with username and password in a headless Chromium and returns the OIDC
 * refresh token the login page leaves in local storage.
 *
 * <p>Stern's login runs through a Descope flow (authorization code + PKCE); Descope's password API is
 * disabled for the project, so there is no plain HTTP login. The browser is started only for the login
 * and closed right after.
 */
@Component
public class SternBrowserLogin {

    private static final Logger log = LoggerFactory.getLogger(SternBrowserLogin.class);
    private static final String LOGIN_URL = "https://insider.sternpinball.com/login";
    private static final String INSIDER_HOST = "insider.sternpinball.com";
    private static final String STORAGE_PREFIX = "user:https://auth.sternpinball.com/";
    private static final List<String> EMAIL_FIELDS = List.of(
            "input[type=email]", "input[name*=email i]", "input[autocomplete=username]", "input[name=loginId]");
    private static final List<String> PASSWORD_FIELDS = List.of("input[type=password]");
    private static final List<String> SUBMIT_BUTTONS = List.of(
            "button[type=submit]", "button:has-text('Continue')", "button:has-text('Sign in')",
            "button:has-text('Log in')", "button:has-text('Next')");

    // Playwright would otherwise download Chromium, Firefox and WebKit on first use (~1.3 GB);
    // we install only Chromium's headless shell ourselves.
    private static final Map<String, String> PLAYWRIGHT_ENV = Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private volatile boolean browserInstalled;

    /** The refresh token, or empty if the login failed (the reason is logged). */
    public Optional<String> obtainRefreshToken(String username, String password) {
        long started = System.currentTimeMillis();
        if (!installBrowser()) return Optional.empty();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(PLAYWRIGHT_ENV));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setViewportSize(1280, 900).setLocale("en-US"))) {
            Page page = context.newPage();
            page.navigate(LOGIN_URL, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(45_000));

            Locator email = firstVisible(page, EMAIL_FIELDS, 45_000);
            if (email == null) return failed(page, "email field");
            email.fill(username);

            Locator pwd = firstVisible(page, PASSWORD_FIELDS, 2_000);
            if (pwd == null) {
                // two-step form: submit the email first
                submit(page, email);
                pwd = firstVisible(page, PASSWORD_FIELDS, 20_000);
                if (pwd == null) return failed(page, "password field");
            }
            pwd.fill(password);
            submit(page, pwd);

            try {
                page.waitForURL(url -> {
                    URI uri = URI.create(url);
                    return INSIDER_HOST.equals(uri.getHost()) && !uri.getPath().startsWith("/login")
                            && !uri.getPath().startsWith("/auth/callback");
                }, new Page.WaitForURLOptions().setTimeout(60_000));
            } catch (PlaywrightException e) {
                return failed(page, "redirect back to Insider");
            }
            page.waitForTimeout(2_000);

            Optional<String> token = readRefreshToken(page);
            if (token.isEmpty()) return failed(page, "refresh token in local storage");
            log.info("Stern browser login successful ({} ms)", System.currentTimeMillis() - started);
            return token;
        } catch (PlaywrightException e) {
            log.error("Stern browser login failed: {}", e.getMessage().lines().findFirst().orElse(""));
            return Optional.empty();
        }
    }

    /** Runs {@code playwright install --only-shell chromium} once per JVM; a no-op if it is already there. */
    private synchronized boolean installBrowser() {
        if (browserInstalled) return true;
        try {
            ProcessBuilder pb = Driver.ensureDriverInstalled(PLAYWRIGHT_ENV, false).createProcessBuilder();
            pb.command().addAll(List.of("install", "--only-shell", "chromium"));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                out.lines().filter(l -> !l.isBlank() && !l.contains("|")).forEach(l -> log.info("playwright install: {}", l));
            }
            if (!process.waitFor(10, TimeUnit.MINUTES) || process.exitValue() != 0) {
                log.error("Installing Chromium for the Stern browser login failed");
                return false;
            }
            browserInstalled = true;
            return true;
        } catch (IOException | RuntimeException e) {
            log.error("Installing Chromium for the Stern browser login failed: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Optional<String> readRefreshToken(Page page) {
        Object entries = page.evaluate(
                "p => Object.keys(localStorage).filter(k => k.startsWith(p)).map(k => localStorage.getItem(k))",
                STORAGE_PREFIX);
        if (!(entries instanceof List<?> list)) return Optional.empty();
        for (Object raw : list) {
            if (!(raw instanceof String json)) continue;
            try {
                String token = objectMapper.readTree(json).path("refresh_token").asString(null);
                if (token != null && !token.isBlank()) return Optional.of(token);
            } catch (JacksonException ignored) {
                // not JSON
            }
        }
        return Optional.empty();
    }

    private static void submit(Page page, Locator field) {
        Locator button = firstVisible(page, SUBMIT_BUTTONS, 3_000);
        if (button != null) button.click();
        else field.press("Enter");
    }

    /** Waits until one of the selectors is visible; Playwright's CSS locators pierce the Descope shadow DOM. */
    private static Locator firstVisible(Page page, List<String> selectors, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String selector : selectors) {
                Locator locator = page.locator(selector).first();
                try {
                    if (locator.isVisible()) return locator;
                } catch (PlaywrightException ignored) {
                    // page is navigating
                }
            }
            page.waitForTimeout(250);
        }
        return null;
    }

    private static Optional<String> failed(Page page, String step) {
        String host = URI.create(page.url()).getHost();
        boolean captcha = page.frames().stream().map(f -> f.url().toLowerCase())
                .anyMatch(u -> u.contains("captcha") || u.contains("turnstile") || u.contains("challenges.cloudflare"));
        log.error("Stern browser login stopped at '{}' on {}{}", step, host, captcha ? " (CAPTCHA on the page)" : "");
        return Optional.empty();
    }
}
