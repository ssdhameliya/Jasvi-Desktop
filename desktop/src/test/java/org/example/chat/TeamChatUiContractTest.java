package org.example.chat;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TeamChatUiContractTest {

    @Test
    void multiWindowMessengerDockPopoutAndPresenceAreWiredWithoutHexLeaks() throws Exception {
        String dashboardFxml = Files.readString(Path.of("src/main/resources/fxml/pages/Dashboard.fxml"));
        assertTrue(dashboardFxml.contains("fx:id=\"btnTeamChat\""));
        assertTrue(dashboardFxml.contains("fx:id=\"chatOverlayLayer\""));
        assertTrue(dashboardFxml.contains("fx:id=\"chatSelfPresenceDot\""));

        String dashboardCtrl = Files.readString(Path.of("src/main/java/org/example/controller/DashboardController.java"));
        assertTrue(dashboardCtrl.contains("UiActionIcons.apply(btnTeamChat, \"chat\""));
        assertTrue(dashboardCtrl.contains("new TeamChatDockOverlay("));
        assertTrue(dashboardCtrl.contains("TeamChatManager.getInstance().stop()"));

        for (String name : new String[]{"TeamChatWindowPane.java", "TeamChatRosterPopup.java", "TeamChatDockOverlay.java", "TeamChatPopoutWindow.java"}) {
            String src = Files.readString(Path.of("src/main/java/org/example/chat/" + name));
            assertFalse(src.matches("(?s).*#[0-9A-Fa-f]{6}.*"), name + " must not contain inline hex colors");
        }
    }
}
