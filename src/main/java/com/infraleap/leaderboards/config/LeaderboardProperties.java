package com.infraleap.leaderboards.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leaderboards")
public record LeaderboardProperties(
        String sternUsername,
        String sternPassword,
        String defaultCountry,
        String defaultState,
        String defaultStateName,
        String defaultContinent,
        int dataRefreshMinutes,
        int gridColumns,
        boolean disableAutoscroll,
        int fireworksDurationSeconds,
        int notificationAutoCloseSeconds
) {
    public LeaderboardProperties {
        if (defaultCountry == null || defaultCountry.isBlank()) defaultCountry = "US";
        if (defaultContinent == null || defaultContinent.isBlank()) defaultContinent = "NA";
        if (dataRefreshMinutes <= 0) dataRefreshMinutes = 5;
        if (gridColumns <= 0) gridColumns = 1;
        if (fireworksDurationSeconds <= 0) fireworksDurationSeconds = 30;
        if (notificationAutoCloseSeconds <= 0) notificationAutoCloseSeconds = 180;
    }
}
