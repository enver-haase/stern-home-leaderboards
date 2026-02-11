package com.infraleap.leaderboards.ui.broadcast;

import com.vaadin.flow.shared.Registration;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class LeaderboardBroadcaster {

    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public Registration register(Consumer<String> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void broadcast(String message) {
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                listeners.remove(listener);
            }
        }
    }
}
