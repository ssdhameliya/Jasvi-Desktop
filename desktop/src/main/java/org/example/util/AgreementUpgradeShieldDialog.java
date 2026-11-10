package org.example.util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Content-adaptive modal shield shown when an employee or administrator attempts
 * to access a feature or module not subscribed in the company's enterprise agreement.
 * Provides instant 1-click WhatsApp upgrade quotation dispatch.
 */
public final class AgreementUpgradeShieldDialog {

    private static final Logger log = LoggerFactory.getLogger(AgreementUpgradeShieldDialog.class);
    private static final String JASVI_WHATSAPP_SUPPORT = "919537544008";

    private AgreementUpgradeShieldDialog() {}

    public static void show(Object ownerTarget, String moduleKey, String moduleTitle, String requiredFeature) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(ownerTarget);
        String title = (moduleTitle != null && !moduleTitle.isBlank()) ? moduleTitle : moduleKey;
        dialog.setTitle(title + " — Subscription Agreement Upgrade");
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        VBox contentBox = new VBox(16);
        contentBox.setPadding(new Insets(20, 24, 20, 24));
        contentBox.getStyleClass().addAll("erp-guidance-dialog-content", "approved-card");
        contentBox.setPrefWidth(540);

        // 1. Hero Header
        HBox heroHeader = new HBox(14);
        heroHeader.setAlignment(Pos.CENTER_LEFT);
        heroHeader.getStyleClass().addAll("erp-guidance-header-box", "approved-section-header");

        StackPane iconBox = new StackPane();
        iconBox.setPrefSize(44, 44);
        iconBox.getStyleClass().addAll("erp-page-header-icon");
        iconBox.getChildren().setAll(IconFactory.icon("security", 24));

        VBox titleCol = new VBox(4);
        Label lblTitle = new Label("Module Not Included in Agreement");
        lblTitle.getStyleClass().addAll("screen-title", "erp-guidance-title");

        String companyName = org.example.config.ConfigManager.get("company.name", "Jasvi Client");
        Label lblSubtitle = new Label("Account: " + companyName);
        lblSubtitle.getStyleClass().addAll("screen-subtitle", "erp-guidance-subtitle");

        titleCol.getChildren().addAll(lblTitle, lblSubtitle);
        HBox.setHgrow(titleCol, Priority.ALWAYS);
        heroHeader.getChildren().addAll(iconBox, titleCol);

        // 2. Explanatory Body Card
        VBox cardBody = new VBox(10);
        cardBody.getStyleClass().addAll("approved-card", "erp-guidance-card");
        cardBody.setPadding(new Insets(14, 16, 14, 16));

        Label lblExplanation = new Label(
                "Access to the '" + title + "' workspace requires the '" + requiredFeature +
                "' entitlement in your enterprise licensing agreement."
        );
        lblExplanation.setWrapText(true);
        lblExplanation.getStyleClass().add("erp-guidance-item-text");

        Label lblDetails = new Label(
                "Your company administrator can customize or add this capability at any time by contacting " +
                "Jasvi Software Solutions. Once added to your agreement, access activates automatically across all workstations."
        );
        lblDetails.setWrapText(true);
        lblDetails.getStyleClass().add("text-muted");

        cardBody.getChildren().addAll(lblExplanation, lblDetails);

        contentBox.getChildren().addAll(heroHeader, cardBody);
        dialog.getDialogPane().setContent(contentBox);

        // 3. Dialog Buttons
        ButtonType btnWhatsAppType = new ButtonType("Request via WhatsApp", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCloseType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(btnWhatsAppType, btnCloseType);

        Button btnWhatsApp = (Button) dialog.getDialogPane().lookupButton(btnWhatsAppType);
        if (btnWhatsApp != null) {
            btnWhatsApp.getStyleClass().addAll("btn-success");
            btnWhatsApp.setGraphic(IconFactory.compactIcon("communication", 16));
            btnWhatsApp.setOnAction(e -> {
                sendWhatsAppRequest(companyName, title, requiredFeature);
            });
        }

        Button btnClose = (Button) dialog.getDialogPane().lookupButton(btnCloseType);
        if (btnClose != null) {
            btnClose.getStyleClass().addAll("btn-secondary");
        }

        dialog.showAndWait();
    }

    private static void sendWhatsAppRequest(String companyName, String moduleTitle, String requiredFeature) {
        try {
            String message = "Hello Jasvi Support, our company (" + companyName +
                    ") would like to add the [" + moduleTitle + " (" + requiredFeature +
                    ")] module to our Jasvi ERP subscription agreement. Please share quotation and activation details.";
            String encoded = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String url = "https://wa.me/" + JASVI_WHATSAPP_SUPPORT + "?text=" + encoded;
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                log.info("[UpgradeShield] WhatsApp dispatch URL: {}", url);
            }
        } catch (Exception ex) {
            log.error("[UpgradeShield] Unable to dispatch WhatsApp request: {}", ex.getMessage(), ex);
        }
    }
}
