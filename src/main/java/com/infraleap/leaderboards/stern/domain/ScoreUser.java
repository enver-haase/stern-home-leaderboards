package com.infraleap.leaderboards.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoreUser(
        String username,
        String name,
        String initials
) {}
