package org.example.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.example.api.licensing.RemoteAccessApiClient;
import org.example.config.ConfigManager;
import org.example.util.AppDialogService;
import org.example.util.DocumentInspectionScaffold;
import org.example.util.IconFactory;
import org.example.util.UiTaskExecutor;

import java.net.URI;
import java.util.Map;

public class RemoteAccessDialogController {

    @FXML private StackPane headerIconHolder;
    @FXML private HBox heroHeader;
    @FXML private Label lblTunnelStatusBadge;
    @FXML private TextField txtCustomDomain;
    @FXML private TextField txtTunnelToken;
    @FXML private Button btnSaveCustomDomain;
    @FXML private Button btnToggleTunnel;
    @FXML private TextField txtLiveUrl;
    @FXML private Button btnCopyUrl;
    @FXML private Button btnOpenBrowser;
    @FXML private Label lblTunnelUptime;
    @FXML private Button btnClose;

    private final RemoteAccessApiClient api = new RemoteAccessApiClient();
    private Timeline pollingTimeline;
    private long currentUptimeSeconds = 0;
    private Dialog<?> hostDialog;

    public void setHostDialog(Dialog<?> dialog) {
        this.hostDialog = dialog;
        if (heroHeader != null && dialog != null) {
            DocumentInspectionScaffold.installDragPhysics(heroHeader, dialog);
        }
    }

    public static void show(Window owner) {
        RemoteAccessDialog.show(owner);
    }

    @FXML
    private void initialize() {
        if (headerIconHolder != null) {
            headerIconHolder.getChildren().setAll(IconFactory.compactIcon("server", 22));
        }
        if (btnCopyUrl != null) btnCopyUrl.setGraphic(IconFactory.compactIcon("copy", 14));
        if (btnOpenBrowser != null) btnOpenBrowser.setGraphic(IconFactory.compactIcon("external", 14));
        if (btnSaveCustomDomain != null) btnSaveCustomDomain.setGraphic(IconFactory.compactIcon("save", 14));

        // Load saved company custom domain and tunnel token
        String savedDomain = ConfigManager.get("gateway.custom.url", "");
        String savedToken = ConfigManager.get("gateway.cloudflare.token", "");
        if (txtCustomDomain != null) txtCustomDomain.setText(savedDomain);
        if (txtTunnelToken != null) txtTunnelToken.setText(savedToken);

        refreshStatus();

        pollingTimeline = new Timeline(new KeyFrame(Duration.seconds(3), e -> refreshStatus()));
        pollingTimeline.setCycleCount(Timeline.INDEFINITE);
        pollingTimeline.play();

        javafx.application.Platform.runLater(() -> {
            if (heroHeader != null && hostDialog != null) {
                DocumentInspectionScaffold.installDragPhysics(heroHeader, hostDialog);
            }
        });
    }

    private void refreshStatus() {
        UiTaskExecutor.submitAction(
                "get-tunnel-status",
                api::getStatus,
                this::applyStatus,
                err -> {
                    // Fail silently during background polling to avoid user disruption
                }
        );
    }

    private void applyStatus(Map<String, Object> status) {
        if (status == null) return;

        Object statusObj = status.get("status");
        boolean active = "ONLINE".equalsIgnoreCase(String.valueOf(statusObj))
                || Boolean.TRUE.equals(status.get("active"));
        String url = status.get("liveUrl") != null
                ? String.valueOf(status.get("liveUrl"))
                : (status.get("url") != null ? String.valueOf(status.get("url")) : null);
        Number uptime = (Number) status.get("uptimeSeconds");
        if (uptime != null) {
            currentUptimeSeconds = uptime.longValue();
        }

        Platform.runLater(() -> {
            if (active) {
                lblTunnelStatusBadge.setText("ONLINE");
                lblTunnelStatusBadge.getStyleClass().removeAll("badge-inactive", "status-warning");
                if (!lblTunnelStatusBadge.getStyleClass().contains("badge-active")) {
                    lblTunnelStatusBadge.getStyleClass().add("badge-active");
                }

                btnToggleTunnel.setText("Stop Sharing");
                btnToggleTunnel.getStyleClass().removeAll("btn-primary", "primary-button", "approved-primary-button");
                if (!btnToggleTunnel.getStyleClass().contains("btn-danger")) {
                    btnToggleTunnel.getStyleClass().add("btn-danger");
                }

                txtLiveUrl.setText(url != null && !url.isBlank() ? url : "Connecting...");
                btnCopyUrl.setDisable(url == null || url.isBlank());
                btnOpenBrowser.setDisable(url == null || url.isBlank());

                formatUptime(currentUptimeSeconds);
            } else {
                lblTunnelStatusBadge.setText("OFFLINE");
                lblTunnelStatusBadge.getStyleClass().removeAll("badge-active", "status-warning");
                if (!lblTunnelStatusBadge.getStyleClass().contains("badge-inactive")) {
                    lblTunnelStatusBadge.getStyleClass().add("badge-inactive");
                }

                btnToggleTunnel.setText("Start Remote Sharing");
                btnToggleTunnel.getStyleClass().removeAll("btn-danger");
                if (!btnToggleTunnel.getStyleClass().contains("btn-primary")) {
                    btnToggleTunnel.getStyleClass().add("btn-primary");
                }

                txtLiveUrl.setText("");
                btnCopyUrl.setDisable(true);
                btnOpenBrowser.setDisable(true);
                lblTunnelUptime.setText("Uptime: 00:00:00");
            }
        });
    }

    private void formatUptime(long seconds) {
        long hrs = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        long secs = seconds % 60;
        String formatted = String.format("Uptime: %02d:%02d:%02d", hrs, mins, secs);
        Platform.runLater(() -> {
            if (lblTunnelUptime != null) {
                lblTunnelUptime.setText(formatted);
            }
        });
    }

    @FXML
    private void handleSaveCustomConfig() {
        String domain = txtCustomDomain != null ? txtCustomDomain.getText().trim() : "";
        String token = txtTunnelToken != null ? txtTunnelToken.getText().trim() : "";
        ConfigManager.set("gateway.custom.url", domain);
        ConfigManager.set("gateway.cloudflare.token", token);
        AppDialogService.success(btnClose, "Configuration Saved", "Custom gateway domain and tunnel settings saved.");

        if (!domain.isBlank()) {
            syncRemoteTelemetryAsync(domain);
        }
    }

    @FXML
    private void handleToggleTunnel() {
        boolean isCurrentlyOnline = "ONLINE".equalsIgnoreCase(lblTunnelStatusBadge.getText());

        btnToggleTunnel.setDisable(true);

        if (isCurrentlyOnline) {
            UiTaskExecutor.submitAction(
                    "stop-tunnel",
                    api::stopTunnel,
                    status -> {
                        btnToggleTunnel.setDisable(false);
                        applyStatus(status);
                        AppDialogService.info(btnClose, "Remote Access Closed", "Secure HTTPS tunnel has stopped.");
                    },
                    err -> {
                        btnToggleTunnel.setDisable(false);
                        AppDialogService.error(btnClose, "Error", "Could not stop tunnel", err.getMessage());
                    }
            );
        } else {
            lblTunnelStatusBadge.setText("CONNECTING...");
            lblTunnelStatusBadge.getStyleClass().removeAll("badge-inactive", "badge-active");
            lblTunnelStatusBadge.getStyleClass().add("status-warning");

            UiTaskExecutor.submitAction(
                    "start-tunnel",
                    api::startTunnel,
                    status -> {
                        if (btnToggleTunnel != null) btnToggleTunnel.setDisable(false);
                        applyStatus(status);
                        String liveUrl = status != null ? String.valueOf(status.getOrDefault("liveUrl", status.getOrDefault("url", ""))) : "";
                        if (!liveUrl.isBlank()) {
                            AppDialogService.success(btnClose, "Remote Access Live", "Secure HTTPS tunnel established!\n" + liveUrl);
                            // Dispatch telemetry to Super Admin Master Licensor
                            syncRemoteTelemetryAsync(liveUrl);
                        } else {
                            AppDialogService.info(btnClose, "Connecting Tunnel", "Cloudflare tunnel is initializing in background...");
                        }
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
            if (btnCopyUrl != null) {
                String orig = btnCopyUrl.getText();
                javafx.scene.Node graphic = btnCopyUrl.getGraphic();
                btnCopyUrl.setText("Copied!");
                btnCopyUrl.setGraphic(IconFactory.compactIcon("complete", 14));
                javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(1500));
                pause.setOnFinished(e -> {
                    btnCopyUrl.setText(orig);
                    btnCopyUrl.setGraphic(graphic);
                });
                pause.play();
            }
        }
    }

    @FXML
    private void handleOpenBrowser() {
        String targetUrl = txtLiveUrl != null && !txtLiveUrl.getText().isBlank() ? txtLiveUrl.getText() : null;
        if (targetUrl == null && txtCustomDomain != null && !txtCustomDomain.getText().isBlank()) {
            targetUrl = txtCustomDomain.getText().trim();
        }
        if (targetUrl != null && !targetUrl.isBlank()) {
            try {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(URI.create(targetUrl));
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
        if (hostDialog != null) {
            hostDialog.close();
        } else if (btnClose != null && btnClose.getScene() != null && btnClose.getScene().getWindow() != null) {
            ((Stage) btnClose.getScene().getWindow()).close();
        }
    }

    /** Dispatches active Remote URL to Super Admin Master Licensor for troubleshooting. */
    public static void syncRemoteTelemetryAsync(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return;
        Thread.ofVirtual().name("remote-telemetry-sync").start(() -> {
            try {
                String machineId = org.example.shared.licensing.HardwareFingerprintUtil.getDisplayMachineId();
                String ver = org.example.update.BuildInfo.version();
                new org.example.api.licensing.ClientRegistryApiClient().sendHeartbeat(machineId, ver, 1, remoteUrl);
            } catch (Exception ignored) {
            }
        });
    }
}
