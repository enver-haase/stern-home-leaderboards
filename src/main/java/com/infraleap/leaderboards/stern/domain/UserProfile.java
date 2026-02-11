package com.infraleap.leaderboards.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserProfile(
        String initials,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("background_color_hex") String backgroundColorHex,
        List<FollowedUser> following
) {}
