package com.infraleap.leaderboards.ui.component;

import com.infraleap.leaderboards.stern.domain.*;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MachineCard extends Div {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    public MachineCard(Machine machine, HighScoreResponse scores,
                       Map<String, AvatarInfo> avatars, Set<String> newScoreIds) {
        addClassName("machine-card");

        MachineTitle title = machine.model() != null ? machine.model().title() : null;

        // Background image
        if (title != null && title.primaryBackground() != null && !title.primaryBackground().isBlank()) {
            addClassName("with-background");
            getStyle().set("background-image",
                    "linear-gradient(rgba(0,0,0,0.7), rgba(0,0,0,0.7)), url(" + title.primaryBackground() + ")");
            getStyle().set("background-size", "cover");
            getStyle().set("background-position", "center");
        }

        // Gradient border
        if (title != null && title.gradientStart() != null && title.gradientStop() != null) {
            getStyle().set("border-image",
                    "linear-gradient(to bottom, " + title.gradientStart() + ", " + title.gradientStop() + ") 1");
            getStyle().set("border-width", "2px");
            getStyle().set("border-style", "solid");
        }

        // Game logo (clickable → fullscreen)
        Div header = new Div();
        header.addClassName("machine-header");
        String logoUrl = title != null ? title.variableWidthLogo() : null;
        if (logoUrl == null || logoUrl.isBlank()) {
            logoUrl = title != null ? title.squareLogo() : null;
        }
        if (logoUrl != null && !logoUrl.isBlank()) {
            Image logo = new Image(logoUrl, title != null ? title.name() : "Machine");
            logo.addClassName("game-logo");
            logo.getElement().setAttribute("title", "Click to view fullscreen");
            logo.getElement().setAttribute("tabindex", "0");
            logo.getElement().setAttribute("role", "button");
            long machineId = machine.safeId();
            logo.addClickListener(e ->
                    logo.getUI().ifPresent(ui -> ui.navigate("fullscreen/" + machineId)));
            header.add(logo);
        } else if (title != null) {
            Span nameLabel = new Span(title.name());
            nameLabel.addClassName("machine-name-text");
            header.add(nameLabel);
        }

        // Model type + version (left-aligned, right of logo)
        String modelType = machine.model() != null ? machine.model().modelTypeName() : null;
        String version = machine.codeVersion();
        if (modelType != null || version != null) {
            Div machineInfo = new Div();
            machineInfo.addClassName("machine-info");
            if (modelType != null) {
                Span typeSpan = new Span(modelType);
                typeSpan.addClassName("machine-type");
                machineInfo.add(typeSpan);
            }
            if (version != null) {
                Span versionSpan = new Span(version);
                versionSpan.addClassName("machine-version");
                machineInfo.add(versionSpan);
            }
            header.add(machineInfo);
        }

        // Status indicator (far right: tech alerts then dot)
        Div statusIndicator = new Div();
        statusIndicator.addClassName("status-indicator");
        List<TechAlert> alerts = machine.techAlerts();
        if (alerts != null && !alerts.isEmpty()) {
            statusIndicator.add(new TechAlertsPopup(alerts));
        }
        statusIndicator.add(new StatusDot(machine.isOnline()));
        header.add(statusIndicator);

        add(header);

        // High scores
        add(new HighScoresTable(scores, avatars, newScoreIds));

        // Last played
        Div lastPlayed = new Div();
        lastPlayed.addClassName("last-played");
        lastPlayed.setText("Last Played: " + formatDate(machine.lastPlayed()));
        add(lastPlayed);
    }

    private static String formatDate(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return "Never";
        try {
            LocalDateTime dt = LocalDateTime.parse(isoDate.replace("Z", "").split("\\+")[0]);
            return dt.format(DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return isoDate;
        }
    }
}
