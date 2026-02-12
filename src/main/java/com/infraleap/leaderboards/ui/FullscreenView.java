package com.infraleap.leaderboards.ui;

import com.infraleap.leaderboards.stern.domain.Machine;
import com.infraleap.leaderboards.stern.domain.MachineTitle;
import com.infraleap.leaderboards.stern.service.LeaderboardDataService;
import com.infraleap.leaderboards.ui.broadcast.LeaderboardBroadcaster;
import com.infraleap.leaderboards.ui.component.HighScoresTable;
import com.infraleap.leaderboards.ui.component.TechAlertsPopup;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Route("fullscreen")
public class FullscreenView extends Div implements HasUrlParameter<Long>, HasDynamicTitle {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

    private final LeaderboardDataService dataService;
    private final LeaderboardBroadcaster broadcaster;
    private Registration broadcasterRegistration;
    private long machineId;
    private String machineName = "Fullscreen";

    public FullscreenView(LeaderboardDataService dataService, LeaderboardBroadcaster broadcaster) {
        this.dataService = dataService;
        this.broadcaster = broadcaster;
        addClassName("fullscreen-machine");
    }

    @Override
    public void setParameter(BeforeEvent event, Long parameter) {
        this.machineId = parameter != null ? parameter : 0;
        buildLayout();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        UI ui = attachEvent.getUI();
        // ESC key to go back
        ui.getPage().executeJs(
                "window.__fsEscHandler = function(e) { if (e.key === 'Escape') window.location.href = '/'; };" +
                "document.addEventListener('keydown', window.__fsEscHandler);"
        );
        broadcasterRegistration = broadcaster.register(msg ->
                ui.access(this::buildLayout));
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (broadcasterRegistration != null) broadcasterRegistration.remove();
        detachEvent.getUI().getPage().executeJs(
                "if (window.__fsEscHandler) document.removeEventListener('keydown', window.__fsEscHandler);"
        );
    }

    private void buildLayout() {
        removeAll();

        Machine machine = dataService.getMachines().stream()
                .filter(m -> m.safeId() == machineId)
                .findFirst()
                .orElse(null);

        if (machine == null) {
            add(new Span("Machine not found"));
            return;
        }

        MachineTitle title = machine.model() != null ? machine.model().title() : null;
        machineName = title != null ? title.name() : "Unknown";

        // Background
        if (title != null && title.primaryBackground() != null && !title.primaryBackground().isBlank()) {
            getStyle().set("background-image",
                    "linear-gradient(rgba(0,0,0,0.8), rgba(0,0,0,0.8)), url(" + title.primaryBackground() + ")");
            getStyle().set("background-size", "cover");
            getStyle().set("background-position", "center");
        }

        Div content = new Div();
        content.addClassName("fullscreen-content");

        // Header
        Div header = new Div();
        header.addClassName("fullscreen-header");

        String logoUrl = title != null ? title.variableWidthLogo() : null;
        if (logoUrl == null || logoUrl.isBlank()) logoUrl = title != null ? title.squareLogo() : null;
        if (logoUrl != null && !logoUrl.isBlank()) {
            Image logo = new Image(logoUrl, machineName);
            logo.addClassName("fullscreen-game-logo");
            logo.getElement().setAttribute("title", "Click to exit fullscreen");
            logo.addClickListener(e -> logo.getUI().ifPresent(ui -> ui.navigate("")));
            header.add(logo);
        }

        if (machine.techAlerts() != null && !machine.techAlerts().isEmpty()) {
            Div statusContainer = new Div();
            statusContainer.addClassName("fullscreen-status-container");
            statusContainer.add(new TechAlertsPopup(machine.techAlerts()));
            header.add(statusContainer);
        }
        content.add(header);

        // Scores
        Div scoresSection = new Div();
        scoresSection.addClassName("fullscreen-scores");
        Div tableContainer = new Div();
        tableContainer.addClassName("scores-table-container");
        if (title != null && title.gradientStart() != null && title.gradientStop() != null) {
            tableContainer.getStyle().set("border",
                    "1px solid " + title.gradientStart());
        }
        tableContainer.add(new HighScoresTable(
                dataService.getHighScores(machineId),
                dataService.getAvatars(),
                dataService.getNewScoreIds(machineId)
        ));
        scoresSection.add(tableContainer);
        content.add(scoresSection);

        // Footer
        Div footer = new Div();
        footer.addClassName("fullscreen-footer");
        Span lastPlayed = new Span("Last Played: " + formatDateTime(machine.lastPlayed()));
        lastPlayed.addClassName("last-played-large");
        footer.add(lastPlayed);
        content.add(footer);

        add(content);
    }

    private static String formatDateTime(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return "Never";
        try {
            LocalDateTime dt = LocalDateTime.parse(isoDate.replace("Z", "").split("\\+")[0]);
            return dt.format(DISPLAY_FORMAT) + " UTC";
        } catch (DateTimeParseException e) {
            return isoDate;
        }
    }

    @Override
    public String getPageTitle() {
        return machineName + " - Stern Home Leaderboards";
    }
}
