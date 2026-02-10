package com.infraleap.leaderboard.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicUser(Integer pk, String username, PublicUserProfile profile) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublicUserProfile(
            String initials,
            @JsonProperty("location_info") String locationInfo,
            @JsonProperty("avatar_url") String avatarUrl,
            @JsonProperty("background_color_hex") String backgroundColorHex
    ) {}
}
