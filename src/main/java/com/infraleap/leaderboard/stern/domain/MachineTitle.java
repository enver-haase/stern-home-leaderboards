package com.infraleap.leaderboard.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MachineTitle(
        String name,
        @JsonProperty("primary_background") String primaryBackground,
        @JsonProperty("variable_width_logo") String variableWidthLogo,
        @JsonProperty("square_logo") String squareLogo,
        @JsonProperty("gradient_start") String gradientStart,
        @JsonProperty("gradient_stop") String gradientStop
) {}
