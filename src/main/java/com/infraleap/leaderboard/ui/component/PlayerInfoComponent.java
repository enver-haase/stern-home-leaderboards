package com.infraleap.leaderboard.ui.component;

import com.infraleap.leaderboard.stern.domain.AvatarInfo;
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
        if (!imgSrc.isEmpty()) {
            Image avatar = new Image(imgSrc, username);
            avatar.addClassName("avatar-img");
            avatarDiv.add(avatar);
        } else {
            Span placeholder = new Span(username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase());
            placeholder.addClassName("avatar-placeholder");
            avatarDiv.add(placeholder);
        }
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
