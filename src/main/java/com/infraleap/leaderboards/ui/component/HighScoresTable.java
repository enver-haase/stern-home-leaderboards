package com.infraleap.leaderboards.ui.component;

import com.infraleap.leaderboards.stern.domain.AvatarInfo;
import com.infraleap.leaderboards.stern.domain.HighScoreEntry;
import com.infraleap.leaderboards.stern.domain.HighScoreResponse;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.Element;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HighScoresTable extends Div {

    public HighScoresTable(HighScoreResponse scores, Map<String, AvatarInfo> avatars, Set<String> newScoreIds) {
        addClassName("high-scores-section");

        if (scores == null || scores.highScores() == null || scores.highScores().isEmpty()) {
            Span noScores = new Span("No high scores yet");
            noScores.addClassName("no-scores");
            add(noScores);
            return;
        }

        Element table = new Element("table");
        table.getClassList().add("high-scores-table");

        // Header
        Element thead = new Element("thead");
        Element headerRow = new Element("tr");
        headerRow.getClassList().add("table-header");
        headerRow.appendChild(createTh("Rank"), createTh("Player"), createTh("Score"));
        thead.appendChild(headerRow);
        table.appendChild(thead);

        // Body
        Element tbody = new Element("tbody");
        List<HighScoreEntry> entries = scores.highScores();
        for (int i = 0; i < entries.size(); i++) {
            HighScoreEntry entry = entries.get(i);
            String username = resolveUsername(entry);
            String scoreId = entry.id() != null && !entry.id().isBlank()
                    ? entry.id() : username + "-" + entry.score();
            boolean isNew = newScoreIds != null && newScoreIds.contains(scoreId);

            Element row = new Element("tr");
            row.getClassList().add("table-row");
            if (isNew) row.getClassList().add("new-score");

            // Rank
            Element rankCell = createTd(i == 0 ? "GC" : String.valueOf(i + 1));
            rankCell.getClassList().add("rank-cell");
            row.appendChild(rankCell);

            // Player — embed a Vaadin component in the table cell
            Element playerCell = new Element("td");
            playerCell.getClassList().add("table-cell");
            AvatarInfo avatar = avatars != null ? avatars.get(username.toLowerCase()) : null;
            PlayerInfoComponent playerInfo = new PlayerInfoComponent(username, avatar, isNew);
            playerCell.appendChild(playerInfo.getElement());
            row.appendChild(playerCell);

            // Score
            Element scoreCell = createTd(formatScore(entry.score()));
            scoreCell.getClassList().add("table-cell");
            scoreCell.getClassList().add("score-cell");
            row.appendChild(scoreCell);

            tbody.appendChild(row);
        }
        table.appendChild(tbody);
        getElement().appendChild(table);
    }

    private static String resolveUsername(HighScoreEntry entry) {
        if (entry.user() == null) return "Unknown";
        if (entry.user().username() != null && !entry.user().username().isBlank())
            return entry.user().username();
        if (entry.user().name() != null && !entry.user().name().isBlank())
            return entry.user().name();
        if (entry.user().initials() != null && !entry.user().initials().isBlank())
            return entry.user().initials();
        return "Unknown";
    }

    private static String formatScore(String score) {
        if (score == null) return "N/A";
        try {
            return NumberFormat.getNumberInstance(Locale.US).format(Long.parseLong(score));
        } catch (NumberFormatException e) {
            return score;
        }
    }

    private static Element createTh(String text) {
        Element th = new Element("th");
        th.getClassList().add("table-cell");
        th.setText(text);
        return th;
    }

    private static Element createTd(String text) {
        Element td = new Element("td");
        td.getClassList().add("table-cell");
        td.setText(text);
        return td;
    }
}
