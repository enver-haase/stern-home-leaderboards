package com.infraleap.leaderboard.stern.service;

import com.infraleap.leaderboard.stern.domain.AvatarInfo;
import com.infraleap.leaderboard.stern.domain.HighScoreEntry;
import com.infraleap.leaderboard.stern.domain.HighScoreResponse;
import com.infraleap.leaderboard.stern.domain.Machine;
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
    private final ConcurrentHashMap<Long, List<HighScoreEntry>> previousScores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> newScoreIds = new ConcurrentHashMap<>();

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

            // Fetch avatars from user profile (v2 API)
            try {
                this.avatars = apiClient.fetchAvatars();
            } catch (Exception e) {
                log.warn("Failed to fetch avatars: {}", e.getMessage());
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

    public Set<String> getNewScoreIds(long machineId) {
        return newScoreIds.getOrDefault(machineId, Set.of());
    }
}
