package com.infraleap.leaderboard.ui.component;

import com.infraleap.leaderboard.stern.domain.Badge;
import com.infraleap.leaderboard.stern.domain.PlayerProfileData;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PlayerProfileDialog extends Dialog {

    public PlayerProfileDialog(String username, PlayerProfileData profile) {
        addClassName("player-profile-dialog");
        setWidth("800px");
        setMaxWidth("95vw");

        setHeaderTitle(username);
        Button closeButton = new Button(new Icon("lumo", "cross"), e -> close());
        closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        getHeader().add(closeButton);

        Div content = new Div();
        content.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "center")
                .set("gap", "12px")
                .set("padding", "16px");

        // Large avatar
        content.add(buildAvatar(username, profile));

        // Username + initials
        Span nameSpan = new Span(username);
        nameSpan.getStyle().set("font-size", "1.2rem").set("font-weight", "bold").set("color", "#fff");
        content.add(nameSpan);

        if (profile != null && profile.initials() != null
                && !profile.initials().equalsIgnoreCase(username)) {
            Span initialsSpan = new Span("(" + profile.initials().toUpperCase() + ")");
            initialsSpan.getStyle().set("font-size", "0.9rem").set("color", "#a0aec0");
            content.add(initialsSpan);
        }

        // Tier-specific info
        if (profile != null) {
            if (profile.tier() == PlayerProfileData.Tier.FRIEND
                    && profile.locationInfo() != null && !profile.locationInfo().isBlank()) {
                Span location = new Span(profile.locationInfo().trim());
                location.getStyle().set("font-size", "0.85rem").set("color", "#a0aec0");
                content.add(location);
            }

            // Badges
            if (profile.badges() != null && !profile.badges().isEmpty()) {
                content.add(buildBadgesSection(profile.badges()));
            }
        }

        add(content);
    }

    private Div buildAvatar(String username, PlayerProfileData profile) {
        Div avatarDiv = new Div();
        avatarDiv.getStyle()
                .set("width", "80px")
                .set("height", "80px")
                .set("border-radius", "50%")
                .set("overflow", "hidden")
                .set("display", "flex")
                .set("align-items", "center")
                .set("justify-content", "center")
                .set("border", "3px solid #e53935");

        if (profile != null && profile.backgroundColor() != null) {
            avatarDiv.getStyle().set("background-color", profile.backgroundColor());
        }

        String imgSrc = (profile != null && profile.avatarUrl() != null
                && !profile.avatarUrl().isBlank()) ? profile.avatarUrl() : "pinball.svg";
        Image img = new Image(imgSrc, username);
        img.getStyle()
                .set("width", "70px")
                .set("height", "70px")
                .set("border-radius", "50%")
                .set("object-fit", "cover");
        avatarDiv.add(img);
        return avatarDiv;
    }

    private Div buildBadgesSection(List<Badge> badges) {
        Div section = new Div();
        section.getStyle().set("width", "100%").set("margin-top", "8px");

        // Deduplicate badges by name
        Set<String> seen = new LinkedHashSet<>();
        List<Badge> unique = badges.stream()
                .filter(b -> b.name() != null && seen.add(b.name()))
                .toList();

        Span header = new Span("Badges (" + unique.size() + ")");
        header.getStyle()
                .set("font-size", "1rem")
                .set("font-weight", "600")
                .set("color", "#e2e8f0")
                .set("display", "block")
                .set("padding-bottom", "8px")
                .set("border-bottom", "1px solid #4a5568")
                .set("margin-bottom", "8px");
        section.add(header);

        Div grid = new Div();
        grid.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(4, 1fr)")
                .set("gap", "16px")
                .set("max-height", "500px")
                .set("overflow-y", "auto")
                .set("padding", "4px");

        for (Badge badge : unique) {
            Div item = new Div();
            item.getStyle()
                    .set("display", "flex")
                    .set("flex-direction", "column")
                    .set("align-items", "center")
                    .set("gap", "6px");
            if (badge.badgeUrl() != null) {
                Image img = new Image(badge.badgeUrl(), badge.name());
                img.setWidth("64px");
                img.setHeight("64px");
                img.getStyle().set("object-fit", "contain").set("border-radius", "4px");
                item.add(img);
            }
            Span name = new Span(badge.name());
            name.getStyle()
                    .set("font-size", "24px")
                    .set("color", "#a0aec0")
                    .set("text-align", "center")
                    .set("word-break", "break-word");
            item.add(name);
            grid.add(item);
        }

        section.add(grid);
        return section;
    }
}
