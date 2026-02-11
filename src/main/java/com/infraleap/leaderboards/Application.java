package com.infraleap.leaderboards;

import com.infraleap.leaderboards.config.LeaderboardProperties;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.theme.lumo.Lumo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(LeaderboardProperties.class)
@Push
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("styles.css")
@NpmPackage(value = "canvas-confetti", version = "1.9.3")
@JsModule("./confetti-trigger.js")
public class Application implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Override
    public void configurePage(AppShellSettings settings) {
        settings.setPageTitle("Stern Home Leaderboards");
        settings.addMetaTag("viewport", "width=device-width, initial-scale=1");
    }
}
