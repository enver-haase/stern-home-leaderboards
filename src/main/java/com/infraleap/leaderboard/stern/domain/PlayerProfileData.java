package com.infraleap.leaderboard.stern.domain;

import java.util.List;

public record PlayerProfileData(
        String username,
        String initials,
        String avatarUrl,
        String backgroundColor,
        String locationInfo,
        List<Badge> badges,
        Tier tier,
        Integer pk
) {
    public enum Tier { FULL, FRIEND, UNKNOWN }
}
