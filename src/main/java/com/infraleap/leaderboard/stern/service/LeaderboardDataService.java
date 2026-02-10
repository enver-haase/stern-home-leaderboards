package com.infraleap.leaderboard.stern.service;

import com.infraleap.leaderboard.stern.domain.*;
import com.infraleap.leaderboard.ui.broadcast.LeaderboardBroadcaster;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
public class LeaderboardDataService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardDataService.class);

    private final SternApiClient apiClient;
    private final LeaderboardBroadcaster broadcaster;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private volatile List<Machine> machines = List.of();
    private final ConcurrentHashMap<Long, HighScoreResponse> highScores = new ConcurrentHashMap<>();
    private volatile Map<String, AvatarInfo> avatars = Map.of();
    private volatile Map<String, PlayerProfileData> playerProfiles = Map.of();
    private final ConcurrentHashMap<Long, List<HighScoreEntry>> previousScores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> newScoreIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> userPkCache = new ConcurrentHashMap<>();

    public LeaderboardDataService(SternApiClient apiClient, LeaderboardBroadcaster broadcaster) {
        this.apiClient = apiClient;
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    public void init() {
        Thread.startVirtualThread(this::refreshAll);
    }

    @Scheduled(fixedDelayString = "${leaderboard.data-refresh-minutes:60}", timeUnit = TimeUnit.MINUTES)
    public void scheduledRefresh() {
        refreshAll();
    }

    public void refreshAll() {
        try {
            log.info("Refreshing leaderboard data from Stern API...");

            List<Machine> fetchedMachines = apiClient.fetchMachines();
            if (fetchedMachines.isEmpty()) {
                log.warn("No machines fetched from Stern API");
                return;
            }
            this.machines = fetchedMachines;

            // Fetch user profile (avatars + badges + friends) from v2 API
            try {
                UserProfile profile = apiClient.fetchUserProfile();
                if (profile != null) {
                    buildProfileMaps(profile);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch user profile: {}", e.getMessage());
            }

            // Fetch high scores for each machine and detect new scores
            String newScoreMessage = null;
            for (Machine machine : fetchedMachines) {
                try {
                    HighScoreResponse scores = apiClient.fetchHighScores(machine.safeId());
                    if (scores != null) {
                        String detected = detectNewScores(machine, scores);
                        if (detected != null) {
                            newScoreMessage = detected;
                        }
                        highScores.put(machine.safeId(), scores);
                        // Store current scores as previous for next comparison
                        if (scores.highScores() != null) {
                            previousScores.put(machine.safeId(), new ArrayList<>(scores.highScores()));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch high scores for machine {}: {}", machine.safeId(), e.getMessage());
                }
            }

            log.info("Leaderboard data refreshed: {} machines", fetchedMachines.size());

            // Broadcast update to all connected UIs
            if (newScoreMessage != null) {
                broadcaster.broadcast(newScoreMessage);
            } else {
                broadcaster.broadcast("REFRESH");
            }
        } catch (Exception e) {
            log.error("Failed to refresh leaderboard data", e);
        }
    }

    private String detectNewScores(Machine machine, HighScoreResponse current) {
        if (current.highScores() == null) return null;

        List<HighScoreEntry> prev = previousScores.get(machine.safeId());
        if (prev == null) return null; // First fetch, nothing to compare

        Set<String> prevIds = new HashSet<>();
        for (HighScoreEntry e : prev) {
            prevIds.add(scoreEntryId(e));
        }

        Set<String> newIds = new HashSet<>();
        String newScoreMessage = null;
        for (HighScoreEntry entry : current.highScores()) {
            String entryId = scoreEntryId(entry);
            if (!prevIds.contains(entryId)) {
                newIds.add(entryId);
                String playerName = resolveUsername(entry);
                String scoreFmt = entry.score() != null ? entry.score() : "?";
                String machineName = machine.model() != null && machine.model().title() != null
                        ? machine.model().title().name() : "Unknown";
                newScoreMessage = "NEW_SCORE:" + machineName + ":" + playerName + ":" + scoreFmt;
            }
        }

        if (!newIds.isEmpty()) {
            newScoreIds.put(machine.safeId(), newIds);
            // Clear highlights after 10 seconds
            scheduler.schedule(() -> {
                newScoreIds.remove(machine.safeId());
                broadcaster.broadcast("REFRESH");
            }, 10, TimeUnit.SECONDS);
        }

        return newScoreMessage;
    }

    private String scoreEntryId(HighScoreEntry e) {
        if (e.id() != null && !e.id().isBlank()) return e.id();
        return resolveUsername(e) + "-" + e.score();
    }

    private String resolveUsername(HighScoreEntry entry) {
        if (entry.user() == null) return "Unknown";
        if (entry.user().username() != null) return entry.user().username();
        if (entry.user().name() != null) return entry.user().name();
        if (entry.user().initials() != null) return entry.user().initials();
        return "Unknown";
    }

    public List<Machine> getMachines() {
        return machines;
    }

    public HighScoreResponse getHighScores(long machineId) {
        return highScores.get(machineId);
    }

    public Map<String, AvatarInfo> getAvatars() {
        return avatars;
    }

    public PlayerProfileData getPlayerProfile(String username) {
        if (username == null) return null;
        return playerProfiles.get(username.toLowerCase());
    }

    public PlayerProfileData fetchEnrichedProfile(String username) {
        if (username == null) return null;

        PlayerProfileData existing = playerProfiles.get(username.toLowerCase());

        // FULL tier (logged-in user) already has badges from user_detail
        if (existing != null && existing.badges() != null && !existing.badges().isEmpty()) {
            return existing;
        }

        // FRIEND tier: we have a pk, fetch badges on demand
        if (existing != null && existing.pk() != null) {
            List<Badge> badges = apiClient.fetchUserBadges(existing.pk());
            return new PlayerProfileData(
                    existing.username(), existing.initials(),
                    existing.avatarUrl(), existing.backgroundColor(),
                    existing.locationInfo(), badges,
                    existing.tier(), existing.pk());
        }

        // UNKNOWN: search for pk, then fetch badges
        Integer pk = userPkCache.get(username.toLowerCase());
        if (pk == null) {
            pk = apiClient.searchUserPk(username);
            if (pk != null) {
                userPkCache.put(username.toLowerCase(), pk);
            }
        }

        if (pk != null) {
            List<Badge> badges = apiClient.fetchUserBadges(pk);
            if (existing != null) {
                return new PlayerProfileData(
                        existing.username(), existing.initials(),
                        existing.avatarUrl(), existing.backgroundColor(),
                        existing.locationInfo(), badges,
                        existing.tier(), pk);
            }
            return new PlayerProfileData(
                    username, username,
                    null, null, null, badges,
                    PlayerProfileData.Tier.UNKNOWN, pk);
        }

        return existing;
    }

    public Set<String> getNewScoreIds(long machineId) {
        return newScoreIds.getOrDefault(machineId, Set.of());
    }

    private void buildProfileMaps(UserProfile profile) {
        Map<String, AvatarInfo> avatarMap = new HashMap<>();
        Map<String, PlayerProfileData> profileMap = new HashMap<>();

        // Logged-in user (FULL tier with badges)
        if (profile.initials() != null) {
            String key = profile.initials().toLowerCase();
            if (profile.avatarUrl() != null && !profile.avatarUrl().isBlank()) {
                avatarMap.put(key, new AvatarInfo(profile.avatarUrl(), profile.backgroundColorHex()));
            }
            profileMap.put(key, new PlayerProfileData(
                    profile.initials(), profile.initials(),
                    profile.avatarUrl(), profile.backgroundColorHex(),
                    null,
                    profile.badges() != null ? profile.badges() : List.of(),
                    PlayerProfileData.Tier.FULL,
                    null));
        }

        // Friends (FRIEND tier, no badges)
        if (profile.following() != null) {
            for (FollowedUser followed : profile.following()) {
                if (followed.initials() == null) continue;
                String key = followed.initials().toLowerCase();
                if (followed.avatarUrl() != null && !followed.avatarUrl().isBlank()) {
                    avatarMap.put(key, new AvatarInfo(followed.avatarUrl(), followed.backgroundColorHex()));
                }
                PlayerProfileData friendProfile = new PlayerProfileData(
                        followed.username(), followed.initials(),
                        followed.avatarUrl(), followed.backgroundColorHex(),
                        followed.locationInfo(),
                        null,
                        PlayerProfileData.Tier.FRIEND,
                        followed.pk());
                profileMap.put(key, friendProfile);
                if (followed.username() != null) {
                    profileMap.put(followed.username().toLowerCase(), friendProfile);
                }
            }
        }

        log.info("Fetched {} avatar entries from user profile", avatarMap.size());
        this.avatars = avatarMap;
        this.playerProfiles = profileMap;
    }
}
