package com.infraleap.leaderboard.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TeamMember(
        String username,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("background_color") String backgroundColor
) {}
