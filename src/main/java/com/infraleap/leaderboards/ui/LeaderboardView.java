package com.infraleap.leaderboards.ui;

import com.infraleap.leaderboards.config.LeaderboardProperties;
import com.infraleap.leaderboards.stern.domain.Machine;
import com.infraleap.leaderboards.stern.service.LeaderboardDataService;
import com.infraleap.leaderboards.ui.broadcast.LeaderboardBroadcaster;
import com.infraleap.leaderboards.ui.component.MachineCard;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Route("")
public class LeaderboardView extends Div implements HasDynamicTitle {

    private final LeaderboardDataService dataService;
    private final LeaderboardBroadcaster broadcaster;
    private final LeaderboardProperties props;
    private final Div machinesContainer = new Div();
    private Registration broadcasterRegistration;
    private final List<Notification> activeNewScoreNotifications = new ArrayList<>();

    public LeaderboardView(LeaderboardDataService dataService,
                           LeaderboardBroadcaster broadcaster,
                           LeaderboardProperties props) {
        this.dataService = dataService;
        this.broadcaster = broadcaster;
        this.props = props;

        addClassName("machines-page");
        machinesContainer.addClassName("machines-container");
        machinesContainer.getStyle().set("--grid-columns", String.valueOf(props.gridColumns()));
        add(machinesContainer);

        buildCards();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        UI ui = attachEvent.getUI();
        broadcasterRegistration = broadcaster.register(message -> {
            ui.access(() -> {
                dismissNewScoreNotifications();
                buildCards();
                if (message.contains("NEW_SCORE:")) {
                    for (String line : message.split("\n")) {
                        if (line.startsWith("NEW_SCORE:")) {
                            showNewScoreNotification(line);
                        }
                    }
                    triggerConfetti();
                }
            });
        });

        if (!props.disableAutoscroll()) {
            startAutoScroll(ui);
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (broadcasterRegistration != null) {
            broadcasterRegistration.remove();
        }
        stopAutoScroll(detachEvent.getUI());
    }

    private void buildCards() {
        machinesContainer.removeAll();
        List<Machine> machines = dataService.getMachines();

        if (machines.isEmpty()) {
            Div loading = new Div();
            loading.addClassName("loading-message");
            loading.add(new Span("Loading machines from Stern..."));
            machinesContainer.add(loading);
            return;
        }

        for (Machine machine : machines) {
            MachineCard card = new MachineCard(
                    machine,
                    dataService.getHighScores(machine.safeId()),
                    dataService.getAvatars(),
                    dataService.getNewScoreIds(machine.safeId())
            );
            machinesContainer.add(card);
        }
    }

    private void showNewScoreNotification(String message) {
        // Parse "NEW_SCORE:MachineName:PlayerName:Score"
        String[] parts = message.split(":", 4);
        String text;
        if (parts.length >= 4) {
            text = "New high score on " + parts[1] + "! " + parts[2] + " scored " + formatScore(parts[3]) + "!";
        } else {
            text = "New high score detected!";
        }

        Notification notification = new Notification();
        notification.addThemeVariants(NotificationVariant.LUMO_PRIMARY);
        notification.setDuration(props.notificationAutoCloseSeconds() * 1000);
        notification.setPosition(Notification.Position.TOP_END);

        Span icon = new Span("\uD83C\uDFC6");
        icon.addClassName("toast-icon");
        Span textSpan = new Span(text);
        textSpan.addClassName("toast-message");
        Span closeBtn = new Span("\u2715");
        closeBtn.addClassName("toast-close");
        closeBtn.getElement().addEventListener("click", e -> notification.close());

        HorizontalLayout layout = new HorizontalLayout(icon, textSpan, closeBtn);
        layout.addClassName("toast-content");
        notification.add(layout);
        activeNewScoreNotifications.add(notification);
        notification.open();
    }

    private void dismissNewScoreNotifications() {
        for (Notification n : activeNewScoreNotifications) {
            n.close();
        }
        activeNewScoreNotifications.clear();
    }

    private static String formatScore(String score) {
        try {
            return NumberFormat.getNumberInstance(Locale.US).format(Long.parseLong(score));
        } catch (NumberFormatException e) {
            return score;
        }
    }

    private void triggerConfetti() {
        int durationMs = props.fireworksDurationSeconds() * 1000;
        getUI().ifPresent(ui -> ui.getPage().executeJs(
                "if (window.triggerCelebration) { window.triggerCelebration($0); }", durationMs
        ));
    }

    private void startAutoScroll(UI ui) {
        ui.getPage().executeJs(
                "(() => {" +
                "  if (window.__autoScrollInterval) { clearInterval(window.__autoScrollInterval); }" +
                "  let scrollingDown = true, paused = false, pausedAtEnd = false;" +
                "  let activityTimeout = null;" +
                "  let indicator = document.getElementById('auto-scroll-pause-indicator');" +
                "  if (!indicator) {" +
                "    indicator = document.createElement('div');" +
                "    indicator.className = 'scroll-pause-indicator';" +
                "    indicator.id = 'auto-scroll-pause-indicator';" +
                "    document.body.appendChild(indicator);" +
                "  }" +
                "  function pauseScrolling() {" +
                "    paused = true;" +
                "    let countdown = 3;" +
                "    indicator.textContent = 'Auto-scroll paused - resuming in ' + countdown + 's';" +
                "    indicator.classList.add('visible');" +
                "    if (activityTimeout) clearTimeout(activityTimeout);" +
                "    const ci = setInterval(() => {" +
                "      countdown--;" +
                "      if (countdown <= 0) { clearInterval(ci); paused = false; indicator.classList.remove('visible'); }" +
                "      else indicator.textContent = 'Auto-scroll paused - resuming in ' + countdown + 's';" +
                "    }, 1000);" +
                "    activityTimeout = setTimeout(() => { paused = false; indicator.classList.remove('visible'); }, 3000);" +
                "  }" +
                "  let lastAct = 0;" +
                "  function handleAct() { const n = Date.now(); if (n - lastAct > 500) { lastAct = n; pauseScrolling(); } }" +
                "  window.addEventListener('mousedown', handleAct);" +
                "  window.addEventListener('keydown', handleAct);" +
                "  window.addEventListener('wheel', handleAct);" +
                "  window.addEventListener('touchstart', handleAct);" +
                "  window.__autoScrollInterval = setInterval(() => {" +
                "    if (paused || pausedAtEnd) return;" +
                "    const y = window.scrollY;" +
                "    const max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);" +
                "    if (max <= 0) return;" +
                "    if (y >= max - 5 && scrollingDown) {" +
                "      pausedAtEnd = true; setTimeout(() => { scrollingDown = false; pausedAtEnd = false; }, 2000); return;" +
                "    }" +
                "    if (y <= 5 && !scrollingDown) {" +
                "      pausedAtEnd = true; setTimeout(() => { scrollingDown = true; pausedAtEnd = false; }, 2000); return;" +
                "    }" +
                "    window.scrollTo({ top: scrollingDown ? Math.min(max, y + 1) : Math.max(0, y - 1) });" +
                "  }, 50);" +
                "})();"
        );
    }

    private void stopAutoScroll(UI ui) {
        ui.getPage().executeJs(
                "if (window.__autoScrollInterval) { clearInterval(window.__autoScrollInterval); }" +
                "var ind = document.getElementById('auto-scroll-pause-indicator');" +
                "if (ind) ind.remove();"
        );
    }

    @Override
    public String getPageTitle() {
        return "Stern Home Leaderboards";
    }
}
