package com.infraleap.leaderboard.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MachineDetail(
        Long pk,
        Boolean online,
        @JsonProperty("last_played") String lastPlayed,
        @JsonProperty("code_version") String codeVersion,
        @JsonProperty("game_model") GameModelInfo gameModel,
        @JsonProperty("last_seven_day_tech_alerts") List<TechAlert> techAlerts
) {
    public String modelTypeName() {
        return gameModel != null ? gameModel.modelTypeName() : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameModelInfo(
            @JsonProperty("model_type_name") String modelTypeName
    ) {}
}
