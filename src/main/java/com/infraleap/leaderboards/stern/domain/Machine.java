package com.infraleap.leaderboards.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Machine(
        Long id,
        Boolean archived,
        Boolean online,
        @JsonProperty("last_played") String lastPlayed,
        MachineModel model,
        MachineAddress address,
        @JsonProperty("last_seven_day_tech_alerts") List<TechAlert> techAlerts,
        String codeVersion
) {
    public long safeId() { return id != null ? id : 0; }
    public boolean isArchived() { return archived != null && archived; }
    public boolean isOnline() { return online != null && online; }
}
