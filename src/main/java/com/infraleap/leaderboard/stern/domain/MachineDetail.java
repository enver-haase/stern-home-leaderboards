package com.infraleap.leaderboard.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MachineDetail(
        Long pk,
        Boolean online,
        @JsonProperty("last_played") String lastPlayed,
        @JsonProperty("last_seven_day_tech_alerts") List<TechAlert> techAlerts
) {}
