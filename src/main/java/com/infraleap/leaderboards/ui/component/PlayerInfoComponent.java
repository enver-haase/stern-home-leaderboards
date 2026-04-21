package com.infraleap.leaderboards.ui.component;

import com.infraleap.leaderboards.stern.domain.AvatarInfo;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;

public class PlayerInfoComponent extends Div {

    public PlayerInfoComponent(String username, AvatarInfo avatarInfo, boolean isNewScore) {
        addClassName("player-cell");

        // Avatar
        Div avatarDiv = new Div();
        avatarDiv.addClassName("player-avatar");
        if (avatarInfo != null && avatarInfo.backgroundColor() != null) {
            avatarDiv.getStyle().set("background-color", avatarInfo.backgroundColor());
        }

        String imgSrc = (avatarInfo != null && avatarInfo.avatarUrl() != null
                && !avatarInfo.avatarUrl().isBlank())
                ? avatarInfo.avatarUrl() : "";
        Image avatar = new Image(imgSrc.isEmpty() ? "pinball.svg" : imgSrc, username);
        avatar.addClassName("avatar-img");
        // If a signed S3 URL expires, fall back to the unsigned base URL
        if (!imgSrc.isEmpty() && imgSrc.contains("?")) {
            String baseUrl = imgSrc.substring(0, imgSrc.indexOf('?'));
            avatar.getElement().setAttribute("onerror",
                    "if(!this.dataset.retried){this.dataset.retried='1';this.src='" + baseUrl + "';}");
        }
        avatarDiv.add(avatar);
        add(avatarDiv);

        // Name + optional trophy
        Span nameSpan = new Span(username);
        nameSpan.addClassName("player-name");
        add(nameSpan);

        if (isNewScore) {
            Span trophy = new Span("\uD83C\uDFC6");
            trophy.addClassName("trophy-icon");
            trophy.getElement().setAttribute("title", "New Score!");
            add(trophy);
        }
    }
}
