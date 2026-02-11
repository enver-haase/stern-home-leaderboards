package com.infraleap.leaderboards.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FollowedUser(
        String initials,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("background_color_hex") String backgroundColorHex
) {}
