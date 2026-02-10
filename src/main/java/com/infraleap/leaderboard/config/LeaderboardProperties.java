package com.infraleap.leaderboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leaderboard")
public record LeaderboardProperties(
        String sternUsername,
        String sternPassword,
        String defaultCountry,
        String defaultState,
        String defaultStateName,
        String defaultContinent,
        int dataRefreshMinutes,
        int gridColumns,
        boolean disableAutoscroll
) {
    public LeaderboardProperties {
        if (defaultCountry == null || defaultCountry.isBlank()) defaultCountry = "US";
        if (defaultContinent == null || defaultContinent.isBlank()) defaultContinent = "NA";
        if (dataRefreshMinutes <= 0) dataRefreshMinutes = 60;
        if (gridColumns <= 0) gridColumns = 1;
    }
}
