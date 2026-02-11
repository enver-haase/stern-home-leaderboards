package com.infraleap.leaderboards.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TechAlert(
        String message,
        @JsonProperty("date_of_event") String dateOfEvent
) {}
