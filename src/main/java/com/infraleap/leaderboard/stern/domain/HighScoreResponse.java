package com.infraleap.leaderboard.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HighScoreResponse(
        @JsonProperty("high_score") List<HighScoreEntry> highScores
) {}
