package com.infraleap.leaderboard.ui.component;

import com.infraleap.leaderboard.stern.domain.TechAlert;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public class TechAlertsPopup extends Div {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

    public TechAlertsPopup(List<TechAlert> techAlerts) {
        addClassName("tech-alerts-container");

        List<TechAlert> realAlerts = techAlerts.stream()
                .filter(a -> a.message() != null && !"No Alerts".equals(a.message()))
                .toList();

        if (realAlerts.isEmpty()) return;

        Button alertButton = new Button();
        alertButton.addClassName("tech-alerts-button");
        alertButton.getElement().setProperty("innerHTML",
                "<svg class='alert-icon' viewBox='0 0 24 24' width='16' height='16' fill='currentColor'>"
                        + "<path d='M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z'/></svg>"
                        + "<span class='alert-badge'>" + realAlerts.size() + "</span>");
        alertButton.getElement().setAttribute("aria-label",
                realAlerts.size() + " tech alert" + (realAlerts.size() != 1 ? "s" : ""));

        Popover popover = new Popover();
        popover.setTarget(alertButton);
        popover.setPosition(PopoverPosition.BOTTOM_END);
        popover.addClassName("tech-alerts-popover");

        Div content = new Div();
        content.addClassName("tech-alerts-content");

        Div header = new Div();
        header.addClassName("popup-header");
        header.add(new Span("Tech Alerts (" + realAlerts.size() + ")"));
        content.add(header);

        Div alertsList = new Div();
        alertsList.addClassName("alerts-list");
        for (TechAlert alert : realAlerts) {
            Div item = new Div();
            item.addClassName("alert-item");
            Div msg = new Div(alert.message());
            msg.addClassName("alert-message");
            Div date = new Div(formatDateTime(alert.dateOfEvent()));
            date.addClassName("alert-date");
            item.add(msg, date);
            alertsList.add(item);
        }
        content.add(alertsList);

        popover.add(content);
        add(alertButton, popover);
    }

    private static String formatDateTime(String isoDate) {
        if (isoDate == null) return "";
        try {
            LocalDateTime dt = LocalDateTime.parse(isoDate.replace("Z", "").split("\\+")[0]);
            return dt.format(DISPLAY_FORMAT) + " UTC";
        } catch (DateTimeParseException e) {
            return isoDate;
        }
    }
}
