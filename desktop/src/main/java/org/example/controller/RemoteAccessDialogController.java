package org.example.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.example.api.licensing.RemoteAccessApiClient;
import org.example.theme.ThemeManager;
import org.example.util.AppDialogService;
import org.example.util.IconFactory;
import org.example.util.PlatformUiSupport;
import org.example.util.ToastManager;
import org.example.util.UiTaskExecutor;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;

public class RemoteAccessDialogController {

    @FXML private StackPane headerIconHolder;
    @FXML private Label lblTunnelStatusBadge;
    @FXML private Button btnToggleTunnel;
    @FXML private TextField txtLiveUrl;
    @FXML private Button btnCopyUrl;
    @FXML private Button btnOpenBrowser;
    @FXML private Label lblTunnelUptime;
    @FXML private StackPane qrHolder;
    @FXML private ImageView imgQrCode;
    @FXML private Label lblQrPlaceholder;
    @FXML private Button btnClose;

    private final RemoteAccessApiClient api = new RemoteAccessApiClient();
    private Timeline pollingTimeline;
    private long currentUptimeSeconds = 0;

    public static void show(Window owner) {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(RemoteAccessDialogController.class.getResource("/fxml/pages/RemoteAccessDialog.fxml"));
            javafx.scene.Parent root = loader.load();
            Stage stage = new Stage();
            PlatformUiSupport.configureDialogStage(stage, null, "Remote & Multi-Branch Access", false);
            if (owner != null) {
                stage.initOwner(owner);
            }
            javafx.scene.Scene scene = new javafx.scene.Scene(root);
            ThemeManager.applyTheme(scene);
            stage.setScene(scene);
            stage.show();
        } catch (Exception e) {
            AppDialogService.error(null, "Remote Access", "Could not open Remote Access dialog", e.getMessage());
        }
    }

    @FXML
    private void initialize() {
        if (headerIconHolder != null) {
            headerIconHolder.getChildren().setAll(IconFactory.compactIcon("server", 18));
        }
        if (btnCopyUrl != null) btnCopyUrl.setGraphic(IconFactory.compactIcon("copy", 14));
        if (btnOpenBrowser != null) btnOpenBrowser.setGraphic(IconFactory.compactIcon("external", 14));

        refreshStatus();

        pollingTimeline = new Timeline(new KeyFrame(Duration.seconds(3), e -> refreshStatus()));
        pollingTimeline.setCycleCount(Timeline.INDEFINITE);
        pollingTimeline.play();
    }

    private void refreshStatus() {
        UiTaskExecutor.submitAction(
                "get-tunnel-status",
                api::getStatus,
                this::applyStatus,
                err -> {
                    if (lblTunnelStatusBadge != null) {
                        lblTunnelStatusBadge.setText("OFFLINE");
                        lblTunnelStatusBadge.getStyleClass().setAll("status-badge", "badge-inactive");
                    }
                }
        );
    }

    private void applyStatus(Map<String, Object> status) {
        String st = String.valueOf(status.getOrDefault("status", "OFFLINE"));
        String url = String.valueOf(status.getOrDefault("liveUrl", ""));
        Object upObj = status.get("uptimeSeconds");
        currentUptimeSeconds = (upObj instanceof Number n) ? n.longValue() : 0L;

        boolean isOnline = "ONLINE".equalsIgnoreCase(st);
        boolean isStarting = "STARTING".equalsIgnoreCase(st);

        if (lblTunnelStatusBadge != null) {
            lblTunnelStatusBadge.getStyleClass().setAll("status-badge");
            if (isOnline) {
                lblTunnelStatusBadge.setText("• ONLINE — SECURE TUNNEL ACTIVE");
                lblTunnelStatusBadge.getStyleClass().add("badge-active");
            } else if (isStarting) {
                lblTunnelStatusBadge.setText("CONNECTING TUNNEL...");
                lblTunnelStatusBadge.getStyleClass().addAll("status-warning", "badge-warning");
            } else {
                lblTunnelStatusBadge.setText("• OFFLINE");
                lblTunnelStatusBadge.getStyleClass().addAll("status-neutral", "badge-inactive");
            }
        }

        if (btnToggleTunnel != null) {
            btnToggleTunnel.getStyleClass().removeAll("btn-primary", "btn-danger", "btn-warning");
            if (isOnline) {
                btnToggleTunnel.setText("Stop Remote Sharing");
                btnToggleTunnel.getStyleClass().add("btn-danger");
            } else if (isStarting) {
                btnToggleTunnel.setText("Connecting...");
                btnToggleTunnel.getStyleClass().add("btn-warning");
            } else {
                btnToggleTunnel.setText("Start Remote Sharing");
                btnToggleTunnel.getStyleClass().add("btn-primary");
            }
        }

        if (txtLiveUrl != null) {
            txtLiveUrl.setText(isOnline ? url : "");
        }

        if (lblTunnelUptime != null) {
            long hours = currentUptimeSeconds / 3600;
            long minutes = (currentUptimeSeconds % 3600) / 60;
            long seconds = currentUptimeSeconds % 60;
            lblTunnelUptime.setText(String.format("Uptime: %02d:%02d:%02d", hours, minutes, seconds));
        }

        if (isOnline && !url.isBlank()) {
            loadQrImage();
        } else {
            if (imgQrCode != null) imgQrCode.setImage(null);
            if (lblQrPlaceholder != null) lblQrPlaceholder.setVisible(true);
        }
    }

    private void loadQrImage() {
        UiTaskExecutor.submitAction(
                "load-tunnel-qr",
                api::getQrImageBytes,
                bytes -> {
                    if (bytes != null && bytes.length > 0) {
                        imgQrCode.setImage(new Image(new ByteArrayInputStream(bytes)));
                        if (lblQrPlaceholder != null) lblQrPlaceholder.setVisible(false);
                    }
                },
                err -> {}
        );
    }

    @FXML
    private void handleToggleTunnel() {
        String current = lblTunnelStatusBadge != null ? lblTunnelStatusBadge.getText() : "";
        boolean isOnline = current.contains("ONLINE");

        if (btnToggleTunnel != null) btnToggleTunnel.setDisable(true);

        if (isOnline) {
            UiTaskExecutor.submitAction(
                    "stop-tunnel",
                    api::stopTunnel,
                    status -> {
                        if (btnToggleTunnel != null) btnToggleTunnel.setDisable(false);
                        applyStatus(status);
                        ToastManager.info(btnClose, "Remote Access Stopped", "Cloudflare secure tunnel closed.");
                    },
                    err -> {
                        if (btnToggleTunnel != null) btnToggleTunnel.setDisable(false);
                        AppDialogService.error(btnClose, "Error", "Could not stop tunnel", err.getMessage());
                    }
            );
        } else {
            UiTaskExecutor.submitAction(
                    "start-tunnel",
                    () -> api.startTunnel(8080),
                    status -> {
                        if (btnToggleTunnel != null) btnToggleTunnel.setDisable(false);
                        applyStatus(status);
                        ToastManager.success(btnClose, "Remote Access Live", "Secure HTTPS tunnel established!");
                    },
                    err -> {
                        if (btnToggleTunnel != null) btnToggleTunnel.setDisable(false);
                        AppDialogService.error(btnClose, "Error", "Could not start tunnel", err.getMessage());
                    }
            );
        }
    }

    @FXML
    private void handleCopyUrl() {
        if (txtLiveUrl != null && !txtLiveUrl.getText().isBlank()) {
            ClipboardContent cb = new ClipboardContent();
            cb.putString(txtLiveUrl.getText());
            Clipboard.getSystemClipboard().setContent(cb);
            ToastManager.info(btnClose, "Copied", "Live HTTPS URL copied to clipboard!");
        }
    }

    @FXML
    private void handleOpenBrowser() {
        if (txtLiveUrl != null && !txtLiveUrl.getText().isBlank()) {
            try {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(URI.create(txtLiveUrl.getText()));
                }
            } catch (Exception ex) {
                AppDialogService.error(btnClose, "Browser Error", "Could not open browser", ex.getMessage());
            }
        }
    }

    @FXML
    private void handleClose() {
        if (pollingTimeline != null) {
            pollingTimeline.stop();
        }
        Stage stage = (Stage) btnClose.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}
