package org.example.controller;

import org.example.util.AppDialogService;

import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import org.example.api.support.SupportApiClient;
import org.example.navigation.NavigationManager;
import org.example.navigation.ScreenLifecycle;
import org.example.service.EmailService;
import org.example.service.ManagedInvoicePdfService;
import org.example.service.PurchaseService;
import org.example.service.SalesService;
import org.example.service.SessionService;
import org.example.util.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class CommunicationCenterController implements ScreenLifecycle {
    private final SupportApiClient supportApi = new SupportApiClient();

    @FXML private Label lblTotal, lblSuccess, lblFailed, lblChannels;
    @FXML private StackPane communicationTotalIcon, communicationSuccessIcon, communicationFailedIcon, communicationChannelIcon;
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbChannel, cmbStatus;
    @FXML private TableView<Row> table;
    @FXML private TableColumn<Row, String> colTime, colEntity, colChannel, colRecipient, colSubject, colStatus, colError, colUser;
    @FXML private TableColumn<Row, Void> colActions;

    private List<Row> all = List.of();

    @FXML
    public void initialize() {
        installKpiIcons();
        colTime.setCellValueFactory(v -> v.getValue().time);
        colEntity.setCellValueFactory(v -> v.getValue().entity);
        colChannel.setCellValueFactory(v -> v.getValue().channel);
        colRecipient.setCellValueFactory(v -> v.getValue().recipient);
        colSubject.setCellValueFactory(v -> v.getValue().subject);
        colStatus.setCellValueFactory(v -> v.getValue().status);
        colError.setCellValueFactory(v -> v.getValue().error);
        colUser.setCellValueFactory(v -> v.getValue().user);
        configureActions();

        cmbChannel.getItems().setAll("All Channels", "EMAIL", "WHATSAPP");
        cmbChannel.setValue("All Channels");
        cmbStatus.getItems().setAll("All Statuses", "SENT", "FAILED");
        cmbStatus.setValue("All Statuses");

        txtSearch.textProperty().addListener((o, a, b) -> filter());
        cmbChannel.valueProperty().addListener((o, a, b) -> filter());
        cmbStatus.valueProperty().addListener((o, a, b) -> filter());
        refresh();
    }

    private void installKpiIcons() {
        setKpiIcon(communicationTotalIcon, "communication");
        setKpiIcon(communicationSuccessIcon, "complete");
        setKpiIcon(communicationFailedIcon, "error");
        setKpiIcon(communicationChannelIcon, "email");
    }

    private void setKpiIcon(StackPane pane, String semantic) {
        if (pane != null) pane.getChildren().setAll(IconFactory.compactIcon(semantic, 22));
    }

    @FXML
    public void refresh() {
        UiTaskExecutor.submitLatest("communication-load", this::readRows, this::applyRows, error -> new OwnedAlert(Alert.AlertType.ERROR, error.getMessage()).showAndWait());
    }

    private List<Row> readRows() {
        List<Row> x = new ArrayList<>();
        for (var r : supportApi.communications()) {
            x.add(new Row(BusinessClock.formatTimestamp(r.createdAt()), r.documentLabel(), r.entityType(), r.entityId(), r.channel(), r.recipient(), r.subject(), r.status(), r.errorMessage(), r.createdBy()));
        }
        return x;
    }

    private void applyRows(List<Row> x) {
        long started = System.nanoTime();
        all = x;
        lblTotal.setText(String.valueOf(x.size()));
        lblSuccess.setText(String.valueOf(x.stream().filter(r -> !r.status.get().equals("FAILED")).count()));
        lblFailed.setText(String.valueOf(x.stream().filter(r -> r.status.get().equals("FAILED")).count()));
        long email = x.stream().filter(r -> r.channel.get().equals("EMAIL")).count();
        lblChannels.setText(email + " / " + (x.size() - email));
        filter();
        long ms = (System.nanoTime() - started) / 1_000_000L;
        if (ms >= 20) PerformanceMonitor.event("controller-phase", "communication-apply | " + ms + " ms");
    }

    private void filter() {
        String q = txtSearch == null || txtSearch.getText() == null ? "" : txtSearch.getText().trim().toLowerCase(Locale.ROOT);
        String channel = cmbChannel == null || cmbChannel.getValue() == null ? "All Channels" : cmbChannel.getValue();
        String status = cmbStatus == null || cmbStatus.getValue() == null ? "All Statuses" : cmbStatus.getValue();
        table.getItems().setAll(all.stream()
                .filter(r -> q.isBlank() || (r.entity.get() + " " + r.recipient.get() + " " + r.subject.get()).toLowerCase(Locale.ROOT).contains(q))
                .filter(r -> channel.startsWith("All") || r.channel.get().equalsIgnoreCase(channel))
                .filter(r -> status.startsWith("All") || r.status.get().equalsIgnoreCase(status))
                .toList());
    }

    private void configureActions() {
        if (colActions == null) return;
        colActions.setCellFactory(c -> new TableCell<>() {
            private final Button resend = new Button("Re-send");
            {
                resend.getStyleClass().addAll("approved-button","approved-secondary-button","communication-resend-button");
                UiActionIcons.apply(resend,"refresh", "Re-send communication with draft preview & live status");
                resend.setOnAction(e -> {
                    Row row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null) resend(row);
                });
            }

            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                Row row = (empty || getTableRow() == null) ? null : getTableRow().getItem();
                boolean show = row != null && ("EMAIL".equalsIgnoreCase(row.channel.get()) || "WHATSAPP".equalsIgnoreCase(row.channel.get()));
                setGraphic(show ? resend : null);
                setAlignment(Pos.CENTER);
            }
        });
    }

    private void resend(Row row) {
        org.example.service.PermissionService.require("COMMUNICATION.RESEND", "re-send a communication");
        String channel = row.channel.get();

        if ("WHATSAPP".equalsIgnoreCase(channel)) {
            resendWhatsapp(row);
        } else {
            resendEmail(row);
        }
    }

    private void resendWhatsapp(Row row) {
        UiTaskExecutor.submitAction(
                "prep-whatsapp-resend",
                () -> {
                    Path pdf = originalDocumentPdf(row);
                    String phone = row.recipient.get().trim();
                    String message = (row.subject.get() != null && !row.subject.get().isBlank())
                            ? row.subject.get()
                            : ("Document " + row.entity.get() + " from " + org.example.service.BrandingService.applicationName());

                    return new WhatsappTransmissionDialog.WhatsappDraft(
                            "Re-send WhatsApp Document",
                            "Direct delivery via WhatsApp Business Engine to customer mobile",
                            row.entityType != null ? row.entityType : "COMMUNICATION",
                            row.entity.get(),
                            phone,
                            message,
                            pdf,
                            "Connected: Primary Company WhatsApp"
                    );
                },
                draft -> {
                    Optional<WhatsappTransmissionDialog.WhatsappTransmissionResult> opt = WhatsappTransmissionDialog.show(table, draft);
                    if (opt.isEmpty()) return;
                    var result = opt.get();

                    LiveOperationDialog.run(table, "Re-sending WhatsApp Message", "whatsapp", "Connecting to WhatsApp Business Engine...", reporter -> {
                        reporter.stage(1, 3, "Validating recipient " + result.recipientPhone() + "...");
                        var wahaApi = new org.example.api.whatsapp.BusinessWhatsappApiClient();

                        reporter.stage(2, 3, "Transmitting via WhatsApp Business Engine...");
                        org.example.api.whatsapp.BusinessWhatsappApiClient.SendResponse sendResp;

                        if (draft.attachmentPdf() != null && Files.isRegularFile(draft.attachmentPdf())) {
                            sendResp = wahaApi.sendDocument(new org.example.api.whatsapp.BusinessWhatsappApiClient.DocumentSendRequest(
                                    row.entityType, row.entity.get(), row.entityId, null, result.recipientPhone(), result.message(), 0L
                            ));
                        } else {
                            String ph = result.recipientPhone().replaceAll("\\D", "");
                            if (ph.length() == 10) ph = "91" + ph;
                            sendResp = wahaApi.sendText(new org.example.api.whatsapp.BusinessWhatsappApiClient.TextSendRequest(ph, result.message(), null));
                        }

                        if (!sendResp.success()) throw new IllegalStateException(sendResp.message());
                        reporter.stage(3, 3, "Message dispatched via channel " + sendResp.channelCode());

                        supportApi.communication(new SupportApiClient.CommunicationRequest(
                                row.entityType, row.entityId, "WHATSAPP", result.recipientPhone(), row.subject.get(), "SENT", null, (SessionService.current() != null ? SessionService.current().getUsername() : "System")
                        ));
                    }, () -> {
                        AppDialogService.success(table, "WhatsApp Resent", "Document " + row.entity.get() + " was resent successfully via WhatsApp.");
                        refresh();
                    }, error -> new OwnedAlert(Alert.AlertType.ERROR, error.getMessage()).showAndWait());
                },
                failure -> new OwnedAlert(Alert.AlertType.ERROR, "Failed to prepare WhatsApp draft:\n\n" + failure.getMessage()).showAndWait()
        );
    }

    private void resendEmail(Row row) {
        UiTaskExecutor.submitAction(
                "prep-email-resend",
                () -> {
                    Path pdf = originalDocumentPdf(row);
                    String to = row.recipient.get().trim();
                    String subject = row.subject.get().isBlank() ? row.entity.get() : row.subject.get();
                    String body = "Please find the original document attached.\n\nResent from " + org.example.service.BrandingService.applicationName() + " Communication Center.";

                    return new EmailTransmissionDialog.EmailDraft(
                            "Re-send Document Email",
                            "Transmit document " + row.entity.get() + " to customer inbox",
                            row.entityType != null ? row.entityType : "DOCUMENT",
                            row.entity.get(),
                            to,
                            subject,
                            body,
                            pdf,
                            null
                    );
                },
                draft -> {
                    Optional<EmailTransmissionDialog.TransmissionResult> opt = EmailTransmissionDialog.show(table, draft);
                    if (opt.isEmpty()) return;
                    var result = opt.get();

                    LiveOperationDialog.run(table, "Re-sending Email", "email", "Connecting to Mail Transfer Agent...", reporter -> {
                        reporter.stage(1, 3, "Validating mail recipients...");
                        reporter.stage(2, 3, "Dispatching email to " + result.recipientEmail() + "...");

                        EmailService.resend(result.recipientEmail(), result.subject(), result.body(), result.attachmentPdf());
                        reporter.stage(3, 3, "Mail sent successfully.");

                        supportApi.communication(new SupportApiClient.CommunicationRequest(
                                row.entityType, row.entityId, "EMAIL", result.recipientEmail(), result.subject(), "SENT", null, (SessionService.current() != null ? SessionService.current().getUsername() : "System")
                        ));
                    }, () -> {
                        AppDialogService.success(table, "Email Resent", "Document " + row.entity.get() + " was resent successfully to " + result.recipientEmail());
                        refresh();
                    }, error -> new OwnedAlert(Alert.AlertType.ERROR, error.getMessage()).showAndWait());
                },
                failure -> new OwnedAlert(Alert.AlertType.ERROR, "Failed to prepare email draft:\n\n" + failure.getMessage()).showAndWait()
        );
    }

    private Path originalDocumentPdf(Row row) throws Exception {
        String type = row.entityType == null ? "" : row.entityType.toUpperCase(Locale.ROOT);
        String number = row.entity.get();
        return switch (type) {
            case "SALE" -> {
                var sale = new SalesService().getByInvoice(number);
                yield sale == null ? null : ManagedInvoicePdfService.sales(sale);
            }
            case "PURCHASE" -> {
                var purchase = new PurchaseService().getByInvoice(number);
                yield purchase == null ? null : ManagedInvoicePdfService.purchase(purchase);
            }
            case "QUOTATION" -> ManagedInvoicePdfService.quotation(number);
            default -> null;
        };
    }

    @FXML
    private void openEmailSettings() {
        SettingsController.requestSection(SettingsController.Section.EMAIL);
        NavigationManager.getInstance().loadPage("/fxml/pages/Settings.fxml");
    }

    public static final class Row {
        final SimpleStringProperty time, entity, channel, recipient, subject, status, error, user;
        final String entityType;
        final int entityId;

        Row(String timeValue, String entityValue, String type, int id, String channelValue, String recipientValue, String subjectValue, String statusValue, String errorValue, String userValue) {
            time = new SimpleStringProperty(timeValue);
            entity = new SimpleStringProperty(entityValue);
            entityType = type;
            entityId = id;
            channel = new SimpleStringProperty(channelValue);
            recipient = new SimpleStringProperty(recipientValue == null ? "" : recipientValue);
            subject = new SimpleStringProperty(subjectValue == null ? "" : subjectValue);
            status = new SimpleStringProperty(statusValue);
            error = new SimpleStringProperty(errorValue == null ? "" : errorValue);
            user = new SimpleStringProperty(userValue == null ? "" : userValue);
        }
    }
}
