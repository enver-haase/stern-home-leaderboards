package com.infraleap.leaderboard.stern.service;

import com.infraleap.leaderboard.config.LeaderboardProperties;
import com.infraleap.leaderboard.stern.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SternApiClient {

    private static final Logger log = LoggerFactory.getLogger(SternApiClient.class);
    private static final String CMS_BASE = "https://cms.prd.sternpinball.io/api/v1/portal";
    private static final String API_V2_BASE = "https://api.prd.sternpinball.io/api/v2/portal";
    private static final int MAX_RETRIES = 2;

    private final SternAuthService authService;
    private final WebClient webClient;
    private final String locationHeader;

    public SternApiClient(SternAuthService authService, LeaderboardProperties props) {
        this.authService = authService;
        this.locationHeader = buildLocationHeader(props);
        this.webClient = WebClient.builder()
                .defaultHeader("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Accept-Language", "en-US,en;q=0.5")
                .defaultHeader("Referer", "https://insider.sternpinball.com/")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Origin", "https://insider.sternpinball.com")
                .defaultHeader("DNT", "1")
                .defaultHeader("Sec-GPC", "1")
                .defaultHeader("Sec-Fetch-Dest", "empty")
                .defaultHeader("Sec-Fetch-Mode", "cors")
                .defaultHeader("Sec-Fetch-Site", "cross-site")
                .defaultHeader("Pragma", "no-cache")
                .defaultHeader("Cache-Control", "max-age=604800, no-cache, no-store")
                .build();
    }

    public List<Machine> fetchMachines() {
        MachinesResponse response = fetchWithRetry(
                CMS_BASE + "/user_registered_machines/?group_type=home",
                MachinesResponse.class, 0);

        if (response == null || response.user() == null || response.user().machines() == null) {
            return List.of();
        }

        List<Machine> basics = response.user().machines();

        // Enrich each machine with tech alerts from detail endpoint
        return basics.stream().map(machine -> {
            try {
                MachineDetail details = fetchWithRetry(
                        CMS_BASE + "/game_machines/" + machine.id(),
                        MachineDetail.class, 0);
                if (details != null) {
                    return new Machine(
                            machine.id(),
                            machine.archived(),
                            details.online() != null ? details.online() : machine.online(),
                            details.lastPlayed() != null ? details.lastPlayed() : machine.lastPlayed(),
                            machine.model(),
                            machine.address(),
                            details.techAlerts()
                    );
                }
            } catch (Exception e) {
                log.warn("Failed to fetch details for machine {}: {}", machine.id(), e.getMessage());
            }
            return machine;
        }).filter(m -> !m.isArchived()).toList();
    }

    public HighScoreResponse fetchHighScores(long machineId) {
        return fetchWithRetry(
                CMS_BASE + "/game_machine_high_scores/?machine_id=" + machineId,
                HighScoreResponse.class, 0);
    }

    public Map<String, AvatarInfo> fetchAvatars() {
        UserDetailResponse response = fetchWithRetry(
                API_V2_BASE + "/user_detail/",
                UserDetailResponse.class, 0);

        Map<String, AvatarInfo> avatarMap = new HashMap<>();
        if (response == null || response.user() == null || response.user().profile() == null) {
            log.warn("user_detail response has no profile data");
            return avatarMap;
        }

        UserProfile profile = response.user().profile();

        // Add the logged-in user's own avatar
        if (profile.initials() != null && profile.avatarUrl() != null && !profile.avatarUrl().isBlank()) {
            avatarMap.put(profile.initials().toLowerCase(),
                    new AvatarInfo(profile.avatarUrl(), profile.backgroundColorHex()));
        }

        // Add avatars from connected players (following list)
        if (profile.following() != null) {
            for (FollowedUser followed : profile.following()) {
                if (followed.initials() != null && followed.avatarUrl() != null && !followed.avatarUrl().isBlank()) {
                    avatarMap.put(followed.initials().toLowerCase(),
                            new AvatarInfo(followed.avatarUrl(), followed.backgroundColorHex()));
                }
            }
        }

        log.info("Fetched {} avatar entries from user profile", avatarMap.size());
        return avatarMap;
    }

    private <T> T fetchWithRetry(String url, Class<T> responseType, int retryCount) {
        try {
            String token = authService.getToken();
            String cookies = authService.getCookies();
            if (token == null || token.isBlank()) {
                log.warn("No auth token available, attempting login...");
                if (!authService.login()) {
                    log.error("Authentication failed, cannot fetch {}", url);
                    return null;
                }
                token = authService.getToken();
                cookies = authService.getCookies();
            }
            return webClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Cookie", cookies != null ? cookies : "")
                    .header("Location", locationHeader)
                    .retrieve()
                    .bodyToMono(responseType)
                    .block();
        } catch (WebClientResponseException e) {
            if ((e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)
                    && retryCount < MAX_RETRIES) {
                log.info("Received {} from Stern API, refreshing auth (retry {}/{})",
                        e.getStatusCode().value(), retryCount + 1, MAX_RETRIES);
                authService.login();
                return fetchWithRetry(url, responseType, retryCount + 1);
            }
            log.error("Stern API error for {}: {} {}", url, e.getStatusCode(), e.getMessage());
            throw e;
        }
    }

    private static String buildLocationHeader(LeaderboardProperties props) {
        return "{\"country\":\"" + props.defaultCountry() + "\""
                + (props.defaultState() != null && !props.defaultState().isBlank()
                ? ",\"state\":\"" + props.defaultState() + "\"" : "")
                + (props.defaultStateName() != null && !props.defaultStateName().isBlank()
                ? ",\"stateName\":\"" + props.defaultStateName() + "\"" : "")
                + ",\"continent\":\"" + props.defaultContinent() + "\"}";
    }
}
