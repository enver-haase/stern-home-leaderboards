package com.infraleap.leaderboards.ui.component;

import com.vaadin.flow.component.html.Span;

public class StatusDot extends Span {

    public StatusDot(boolean online) {
        addClassName(online ? "status-dot-online" : "status-dot-offline");
        getElement().setAttribute("title", online ? "Online" : "Offline");
    }
}
