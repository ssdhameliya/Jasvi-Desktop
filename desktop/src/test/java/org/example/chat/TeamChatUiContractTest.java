package org.example.chat;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TeamChatUiContractTest {

    @Test
    void multiWindowMessengerDockPopoutAndPresenceAreWiredWithoutHexLeaks() throws Exception {
        String dashboardFxml = Files.readString(Path.of("src/main/resources/fxml/pages/Dashboard.fxml"));
        assertFalse(dashboardFxml.contains("fx:id=\"btnTeamChat\""), "Top bar must not host fixed chat icon");
        assertTrue(dashboardFxml.contains("fx:id=\"chatOverlayLayer\""));

        String dashboardCtrl = Files.readString(Path.of("src/main/java/org/example/controller/DashboardController.java"));
        assertTrue(dashboardCtrl.contains("new TeamChatDockOverlay("));
        assertTrue(dashboardCtrl.contains("TeamChatManager.getInstance().stop()"));

        String dockSrc = Files.readString(Path.of("src/main/java/org/example/chat/TeamChatDockOverlay.java"));
        assertTrue(dockSrc.contains("chat-fab-btn"));
        assertTrue(dockSrc.contains("BrandingService.applicationMarkImage()"));
        assertTrue(dockSrc.contains("MouseEvent.MOUSE_DRAGGED"));

        String rosterSrc = Files.readString(Path.of("src/main/java/org/example/chat/TeamChatRosterPopup.java"));
        assertTrue(rosterSrc.contains("popup.hide()"), "Create group dialog must close auto-hiding popup first");
        assertTrue(rosterSrc.contains("openAbove"), "Roster popover must open cleanly above bottom-right launcher without overlap");

        String resolverSrc = Files.readString(Path.of("src/main/java/org/example/util/DialogOwnerResolver.java"));
        assertTrue(resolverSrc.contains("PopupWindow"), "DialogOwnerResolver must unwrap PopupWindow to parent Stage");

        for (String name : new String[]{"TeamChatWindowPane.java", "TeamChatRosterPopup.java", "TeamChatDockOverlay.java", "TeamChatPopoutWindow.java"}) {
            String src = Files.readString(Path.of("src/main/java/org/example/chat/" + name));
            assertFalse(src.matches("(?s).*#[0-9A-Fa-f]{6}.*"), name + " must not contain inline hex colors");
        }
    }
}
