package org.example.chat;

import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.example.api.chat.TeamChatApiClient.ChannelSummaryDto;
import org.example.theme.ThemeManager;
import org.example.util.SceneManager;

import java.util.function.Consumer;

/**
 * Standalone detached chat Stage (Pop-Out window) so a user can monitor multiple
 * simultaneous team conversations across screens or monitors independently of tabs.
 */
public final class TeamChatPopoutWindow {
    private TeamChatPopoutWindow() {}

    public static void open(
            ChannelSummaryDto channel,
            Consumer<String> onOpenErpRecord,
            Consumer<ChannelSummaryDto> onDockBack
    ) {
        if (channel == null) return;
        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle(("GROUP".equals(channel.channelType()) ? "#" + channel.name() : channel.name()) + " — Jasvi Team Chat");
        stage.setMinWidth(440);
        stage.setMinHeight(520);
        stage.setWidth(520);
        stage.setHeight(660);
        SceneManager.applyStageIcon(stage);

        final TeamChatWindowPane[] holder = new TeamChatWindowPane[1];
        TeamChatWindowPane pane = new TeamChatWindowPane(
                channel,
                true,
                stage::close,
                null,
                () -> {
                    stage.close();
                    if (onDockBack != null) onDockBack.accept(channel);
                },
                onOpenErpRecord
        );
        holder[0] = pane;

        Scene scene = new Scene(pane);
        ThemeManager.applyTheme(scene, SceneManager.primaryStage);
        stage.setScene(scene);

        TeamChatManager.getInstance().registerPopout(channel.id(), () -> {
            stage.show();
            stage.toFront();
            pane.focusInput();
        });
        stage.setOnHidden(e -> {
            TeamChatManager.getInstance().unregisterPopout(channel.id());
            pane.dispose();
        });

        stage.show();
        pane.focusInput();
    }
}
