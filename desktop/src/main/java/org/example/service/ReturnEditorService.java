package org.example.service;

import org.example.util.BusinessClock;
import org.example.util.DatePickerFormatter;
import org.example.util.DynamicTableLayoutManager;
import org.example.util.IconFactory;

import org.example.util.OwnedAlert;
import org.example.util.ScreenRefreshPolicy;
import org.example.util.OwnedDialog;
import org.example.util.PopupTableWorkspace;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.example.api.returns.ReturnApiClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

/**
 * One-screen editor for sales and purchase returns. It supports multiple
 * invoice lines, independently entered quantities and one reason per item.
 */
public final class ReturnEditorService {
    public enum Type {
        SALES("SALES RETURN", "SAL-RET-", 1),
        PURCHASE("PURCHASE RETURN", "PUR-RET-", -1);

        private final String databaseValue;
        private final String numberPrefix;
        private final int stockDirection;

        Type(String databaseValue, String numberPrefix, int stockDirection) {
            this.databaseValue = databaseValue;
            this.numberPrefix = numberPrefix;
            this.stockDirection = stockDirection;
        }
    }

    /** Immutable invoice-line input used by both sales and purchase screens. */
    public record InvoiceItem(Long sourceLineId, String code, String description, double quantity,
                              double rate, double discountPercent, double taxPercent,
                              double lineTotal, double alreadyReturnedAmount) {
        public InvoiceItem(String code, String description, double quantity, double rate, double discountPercent, double taxPercent) {
            this(null, code, description, quantity, rate, discountPercent, taxPercent,
                    org.example.shared.DocumentCalculationEngine.line(quantity, rate, discountPercent, taxPercent).totalAmount(), 0);
        }
    }

    private ReturnEditorService() {
    }

    /** Opens the editor and returns the created return number when saved. */
    public static Optional<String> show(Window owner, Type type, String invoiceNo,
                                        String partyName, int partyId,
                                        List<InvoiceItem> invoiceItems) {
        if (invoiceItems == null || invoiceItems.isEmpty()) {
            warning("This invoice does not contain any returnable items.");
            return Optional.empty();
        }

        List<ReturnLine> rows = loadReturnableLines(type, invoiceNo, invoiceItems);
        if (rows == null || rows.isEmpty()) {
            warning("This invoice does not contain any returnable items.");
            return Optional.empty();
        }
        if (rows.stream().noneMatch(row -> row.available() > 0.0001)) {
            warning("Every item on " + invoiceNo + " has already been returned.");
            return Optional.empty();
        }

        Dialog<ButtonType> dialog = new OwnedDialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(type == Type.SALES ? "Create Sales Return" : "Create Purchase Return");
        dialog.setHeaderText("Select invoice items and enter the quantity to return.");

        DatePicker returnDate = new DatePicker(BusinessClock.today());
        DatePickerFormatter.attach(returnDate);
        returnDate.setMaxWidth(Double.MAX_VALUE);
        Label total = PopupTableWorkspace.metricValue(money(0), "warning");

        TableView<ReturnLine> table = buildTable(type);
        table.getItems().setAll(rows);
        table.setPrefSize(1080, 430);
        table.setEditable(true);
        PopupTableWorkspace.prepareTable(table, "erp-table-profile-dialog");

        Runnable updateTotal = () -> total.setText(money(rows.stream()
            .filter(ReturnLine::selected)
            .mapToDouble(ReturnLine::returnAmount)
            .sum()));
        rows.forEach(row -> {
            row.selectedProperty().addListener((observable, oldValue, newValue) -> {
                // Checking a line means "return the remaining quantity" by
                // default. The visible quantity editor can then be reduced
                // for a partial return.
                if (newValue && row.returnQuantity() <= 0 && row.available() > 0) {
                    row.setReturnQuantity(row.available());
                } else if (!newValue) {
                    row.setReturnQuantity(0);
                }
                table.refresh();
                updateTotal.run();
            });
            row.returnQuantityProperty().addListener((observable, oldValue, newValue) -> updateTotal.run());
        });

        CheckBox selectAvailable = new CheckBox("Select all available items");
        selectAvailable.getProperties().put("erp.icon.skip", true);
        selectAvailable.setOnAction(event -> {
            rows.forEach(row -> row.setSelected(selectAvailable.isSelected() && row.available() > 0.0001));
            updateTotal.run();
        });

        Label selectedCount = PopupTableWorkspace.footerText("0 items selected");
        Runnable updateSelectedCount = () -> {
            long count = rows.stream().filter(ReturnLine::selected).count();
            selectedCount.setText(count + " item" + (count == 1 ? "" : "s") + " selected");
        };
        rows.forEach(row -> row.selectedProperty().addListener((o, a, b) -> updateSelectedCount.run()));
        updateSelectedCount.run();

        Label invoiceValue = PopupTableWorkspace.metricValue(invoiceNo, "document");
        Label partyValue = PopupTableWorkspace.metricValue(partyName, type == Type.SALES ? "customer" : "supplier");
        HBox metrics = PopupTableWorkspace.metricStrip(
            PopupTableWorkspace.metricCard("Invoice", invoiceValue, "document"),
            PopupTableWorkspace.metricCard(type == Type.SALES ? "Customer" : "Supplier", partyValue, type == Type.SALES ? "customer" : "supplier"),
            PopupTableWorkspace.controlCard("Return Date", returnDate, "calendar"),
            PopupTableWorkspace.metricCard("Estimated Total", total, "warning")
        );
        Label lblPostingProfile = new Label("📦 PHYSICAL RESTOCK PROFILE");
        lblPostingProfile.getStyleClass().addAll("filter-chip", "erp-table-value-colour-green");
        lblPostingProfile.setGraphic(IconFactory.compactIcon("item", 14));

        Label lblPostingDetails = new Label("Stock will be restocked to inventory at FIFO valuation • Statutory GST Credit Note drafted for GSTR-1 Table 9B");
        lblPostingDetails.getStyleClass().addAll("approved-lead");

        Runnable updatePostingPreview = () -> {
            List<ReturnLine> sel = rows.stream().filter(ReturnLine::selected).toList();
            if (sel.isEmpty()) {
                lblPostingProfile.setText("⚡ SELECT ITEMS TO PREVIEW POSTING");
                lblPostingProfile.setGraphic(IconFactory.compactIcon("status", 14));
                lblPostingDetails.setText("Select items and reasons to see real-time GL double-entry, inventory movement, and GST impact.");
                return;
            }
            boolean anyRebate = sel.stream().anyMatch(r -> isCommercialRebate(r.reason()));
            boolean anyPhysical = sel.stream().anyMatch(r -> isPhysicalStockReturn(r.reason()));

            if (anyRebate) {
                lblPostingProfile.setText("🎉 COMMERCIAL REBATE (CGST SEC 15(3)(b))");
                lblPostingProfile.setGraphic(IconFactory.compactIcon("notes", 14));
                lblPostingDetails.setText("ZERO Inventory Movement (Stock unaffected) • Dr Discount Allowed / Cr Debtors • Zero GST Reversal");
            } else if (!anyPhysical) {
                lblPostingProfile.setText("🏷️ RATE DIFFERENCE / PRICE ADJUSTMENT");
                lblPostingProfile.setGraphic(IconFactory.compactIcon("currency", 14));
                lblPostingDetails.setText("ZERO Inventory Movement (Stock unaffected) • Reverses Revenue & Output GST • Statutory GST Credit Note (GSTR-1 Table 9B)");
            } else {
                lblPostingProfile.setText("📦 PHYSICAL GOODS RESTOCK PROFILE");
                lblPostingProfile.setGraphic(IconFactory.compactIcon("item", 14));
                lblPostingDetails.setText("Inward Stock Restock at FIFO Valuation • Reverses Revenue & Output GST • Statutory GST Credit Note (GSTR-1 Table 9B)");
            }
        };

        rows.forEach(row -> {
            row.selectedProperty().addListener((o, a, b) -> updatePostingPreview.run());
            row.reasonProperty().addListener((o, a, b) -> updatePostingPreview.run());
        });
        updatePostingPreview.run();

        HBox postingPreviewStrip = new HBox(10, lblPostingProfile, lblPostingDetails);
        postingPreviewStrip.setAlignment(Pos.CENTER_LEFT);
        postingPreviewStrip.setPadding(new javafx.geometry.Insets(6, 12, 6, 12));
        postingPreviewStrip.getStyleClass().add("approved-surface");

        HBox selectionBar = PopupTableWorkspace.footer(selectAvailable);
        VBox tableArea = new VBox(8, selectionBar, table, postingPreviewStrip);
        VBox.setVgrow(table, Priority.ALWAYS);
        HBox footer = PopupTableWorkspace.footerWithRight(selectedCount,
            PopupTableWorkspace.footerText("Enter a reason for every selected return item."));
        VBox content = PopupTableWorkspace.content(metrics, tableArea, footer);
        dialog.getDialogPane().setContent(content);
        PopupTableWorkspace.applyInspectionSizing(dialog, null);

        ButtonType save = new ButtonType("Create Return", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        while (true) {
            Optional<ButtonType> result = dialog.showAndWait();
            if (result.isEmpty() || result.get() != save) return Optional.empty();
            try {
                List<ReturnLine> selected = validate(rows, returnDate.getValue());
                String returnNo = save(type, invoiceNo, partyId, returnDate.getValue(), selected);
                NotificationService.add((type == Type.SALES ? "Sales" : "Purchase") +
                    " return " + returnNo + " created with " + selected.size() + " item(s).");
                return Optional.of(returnNo);
            } catch (Exception exception) {
                warning(exception.getMessage() == null ? "The return could not be created." : exception.getMessage());
            }
        }
    }

    private static TableView<ReturnLine> buildTable(Type type) {
        TableView<ReturnLine> table = new TableView<>();
        table.getStyleClass().addAll("approved-table", "erp-table-profile-dialog");
        // Deliberate exception to numbered register rows: a return transaction
        // can select several invoice lines with a different quantity per line.
        table.getProperties().put("erp-keep-selection", true);

        TableColumn<ReturnLine, Boolean> selected = new TableColumn<>("Select");
        selected.setCellValueFactory(value -> value.getValue().selectedProperty());
        selected.setCellFactory(CheckBoxTableCell.forTableColumn(selected));
        selected.setEditable(true);

        TableColumn<ReturnLine, String> code = textColumn("Item Code", row -> row.codeProperty());
        TableColumn<ReturnLine, String> item = textColumn("Item Name", row -> row.descriptionProperty());
        TableColumn<ReturnLine, Number> invoiced = numberColumn("Invoiced", row -> row.invoicedProperty());
        TableColumn<ReturnLine, Number> returned = numberColumn("Returned", row -> row.returnedProperty());
        TableColumn<ReturnLine, Number> available = numberColumn("Available", row -> row.availableProperty());

        TableColumn<ReturnLine, ReturnLine> quantity = new TableColumn<>("Return Qty");
        quantity.setCellValueFactory(value -> new javafx.beans.property.ReadOnlyObjectWrapper<>(value.getValue()));
        quantity.setCellFactory(column -> new QuantityEditorCell());

        TableColumn<ReturnLine, Number> rate = numberColumn("Rate", row -> row.rateProperty());
        TableColumn<ReturnLine, Number> tax = numberColumn("Tax %", row -> row.taxProperty());
        TableColumn<ReturnLine, Number> amount = numberColumn("Return Amount", row -> new SimpleDoubleProperty(row.returnAmount()));
        amount.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                if (empty) {
                    textProperty().unbind();
                    setText(null);
                } else {
                    ReturnLine row = getTableRow().getItem();
                    if (row != null) {
                        textProperty().unbind();
                        textProperty().bind(row.returnQuantityProperty().multiply(
                            row.rate() * (1 + row.tax() / 100.0)).asString("₹ %,.2f"));
                    }
                }
            }
        });

        TableColumn<ReturnLine, ReturnLine> reason = new TableColumn<>("Reason");
        reason.setCellValueFactory(value -> new javafx.beans.property.ReadOnlyObjectWrapper<>(value.getValue()));
        List<String> reasonOptions = loadLookupReasons(type);
        reason.setCellFactory(column -> new ReasonEditorCell(reasonOptions));

        IconFactory.applyTableHeaderIcon(selected, "select");
        IconFactory.applyTableHeaderIcon(code, "identity");
        IconFactory.applyTableHeaderIcon(item, "item");
        IconFactory.applyTableHeaderIcon(invoiced, "document");
        IconFactory.applyTableHeaderIcon(returned, "return");
        IconFactory.applyTableHeaderIcon(available, "quantity");
        IconFactory.applyTableHeaderIcon(quantity, "return");
        IconFactory.applyTableHeaderIcon(rate, "currency");
        IconFactory.applyTableHeaderIcon(tax, "tax");
        IconFactory.applyTableHeaderIcon(amount, "currency");
        IconFactory.applyTableHeaderIcon(reason, "notes");

        table.getColumns().addAll(selected, code, item, invoiced, returned, available,
            quantity, rate, tax, amount, reason);
        DynamicTableLayoutManager.install(table);
        return table;
    }

    private static TableColumn<ReturnLine, String> textColumn(
        String title,
        java.util.function.Function<ReturnLine, javafx.beans.value.ObservableValue<String>> value) {
        TableColumn<ReturnLine, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> value.apply(cell.getValue()));
        return column;
    }

    private static TableColumn<ReturnLine, Number> numberColumn(
        String title,
        java.util.function.Function<ReturnLine, javafx.beans.value.ObservableValue<Number>> value) {
        TableColumn<ReturnLine, Number> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> value.apply(cell.getValue()));
        return column;
    }

    /**
     * Always-visible quantity editor. Unlike TextFieldTableCell, this does not
     * require a hidden double-click/Enter sequence and is therefore usable as
     * soon as the return dialog opens.
     */
    private static final class QuantityEditorCell extends TableCell<ReturnLine, ReturnLine> {
        private final TextField editor = new TextField();
        private boolean refreshing;

        QuantityEditorCell() {
            editor.setPromptText("0");
            editor.setMaxWidth(Double.MAX_VALUE);
            editor.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d*(\\.\\d{0,3})?") ? change : null));
            editor.textProperty().addListener((observable, oldValue, newValue) -> {
                if (refreshing || getItem() == null) return;
                double value = parseQuantity(newValue);
                getItem().setReturnQuantity(value);
                if (value > 0 && !getItem().selected()) getItem().setSelected(true);
            });
        }

        @Override protected void updateItem(ReturnLine row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            refreshing = true;
            editor.setText(row.returnQuantity() <= 0 ? "" : formatQuantity(row.returnQuantity()));
            editor.setDisable(row.available() <= 0.0001);
            refreshing = false;
            setGraphic(editor);
        }
    }

    private static final List<String> DEFAULT_PURCHASE_REASONS = List.of(
            "Defective Material (Physical Return)",
            "Goods returned to supplier (Physical Return)",
            "Wrong Item Delivered (Physical Return)",
            "Transit Damage (Physical Return)",
            "Excess Quantity (Physical Return)",
            "Quality Rejection (Physical Return)",
            "Rate Difference / Price Correction (Zero Stock)",
            "Commercial Discount / Volume Rebate (Zero Stock, Non-GST)",
            "Supplier Return",
            "Rate Difference",
            "Commercial Discount"
    );

    private static final List<String> DEFAULT_SALES_REASONS = List.of(
            "Goods returned by customer (Physical Return)",
            "Defective Material (Physical Return)",
            "Wrong Item Delivered (Physical Return)",
            "Transit Damage (Physical Return)",
            "Excess Quantity (Physical Return)",
            "Quality Rejection (Physical Return)",
            "Rate Difference / Price Correction (Zero Stock)",
            "Commercial Discount / Volume Rebate (Zero Stock, Non-GST)",
            "Customer Return",
            "Rate Difference",
            "Commercial Discount"
    );

    private static boolean isPhysicalStockReturn(String reason) {
        if (reason == null || reason.isBlank()) return true;
        String r = reason.trim().toUpperCase(Locale.ROOT);
        if (r.contains("RATE DIFF") || r.contains("PRICE") || r.contains("OVER BILL") || r.contains("TAX RATE")) return false;
        if (r.contains("DISCOUNT") || r.contains("REBATE") || r.contains("INCENTIVE") || r.contains("GOODWILL")) return false;
        if (r.contains("COMMERCIAL")) return false;
        return true;
    }

    private static boolean isCommercialRebate(String reason) {
        if (reason == null || reason.isBlank()) return false;
        String r = reason.trim().toUpperCase(Locale.ROOT);
        return r.contains("COMMERCIAL") || r.contains("DISCOUNT") || r.contains("REBATE") || r.contains("INCENTIVE") || r.contains("GOODWILL");
    }

    private static List<String> loadLookupReasons() {
        return loadLookupReasons(Type.PURCHASE);
    }

    private static List<String> loadLookupReasons(Type type) {
        try {
            LookupService lookup = new LookupService();
            if (type == Type.PURCHASE) {
                List<String> list = lookup.getValuesByCategoryCode("PURCHASE_RETURN_REASON");
                if (list == null || list.isEmpty()) list = lookup.getValuesByCategoryCode("DEBIT_NOTE_REASON");
                if (list != null && !list.isEmpty()) return list;
                return DEFAULT_PURCHASE_REASONS;
            } else {
                List<String> list = lookup.getValuesByCategoryCode("SALES_RETURN_REASON");
                if (list == null || list.isEmpty()) list = lookup.getValuesByCategoryCode("CREDIT_NOTE_REASON");
                if (list == null || list.isEmpty()) list = lookup.getValuesByCategoryCode("RETURN_REASON");
                if (list != null && !list.isEmpty()) return list;
                return DEFAULT_SALES_REASONS;
            }
        } catch (Exception ignored) {
        }
        return type == Type.PURCHASE ? DEFAULT_PURCHASE_REASONS : DEFAULT_SALES_REASONS;
    }

    /** Always-visible reason editor powered by Master Lookups for sales and purchase returns. */
    private static final class ReasonEditorCell extends TableCell<ReturnLine, ReturnLine> {
        private final ComboBox<String> editor = new ComboBox<>();
        private boolean refreshing;

        ReasonEditorCell(List<String> reasonOptions) {
            editor.setEditable(true);
            editor.getItems().setAll(reasonOptions != null && !reasonOptions.isEmpty()
                    ? reasonOptions
                    : loadLookupReasons());
            editor.setMaxWidth(Double.MAX_VALUE);
            editor.setPromptText("Select or enter reason");
            editor.getStyleClass().addAll("approved-input", "return-reason-combo");
            editor.valueProperty().addListener((observable, oldValue, newValue) -> {
                if (!refreshing && getItem() != null && newValue != null) getItem().setReason(newValue);
            });
            if (editor.getEditor() != null) {
                editor.getEditor().textProperty().addListener((observable, oldValue, newValue) -> {
                    if (!refreshing && getItem() != null && newValue != null) getItem().setReason(newValue);
                });
            }
        }

        @Override protected void updateItem(ReturnLine row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            refreshing = true;
            String current = row.reason();
            if (current != null && !current.isBlank()) {
                editor.setValue(current);
                if (editor.getEditor() != null) editor.getEditor().setText(current);
            } else if (!editor.getItems().isEmpty()) {
                String first = editor.getItems().get(0);
                editor.setValue(first);
                row.setReason(first);
                if (editor.getEditor() != null) editor.getEditor().setText(first);
            }
            refreshing = false;
            setGraphic(editor);
        }
    }

    private static double parseQuantity(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static List<ReturnLine> loadReturnableLines(Type type, String invoiceNo, List<InvoiceItem> invoiceItems) {
        try {
            List<ReturnApiClient.ReturnableLine> authoritative = new ReturnApiClient().returnableLines(type.databaseValue, invoiceNo);
            if (authoritative != null && !authoritative.isEmpty()) {
                List<ReturnLine> rows = new ArrayList<>();
                for (ReturnApiClient.ReturnableLine line : authoritative) {
                    InvoiceItem item = new InvoiceItem(line.sourceLineId(), line.code(), line.description(), line.quantity(),
                            line.rate(), line.discountPercent(), line.taxPercent(), line.lineTotal(), line.returnedAmount());
                    rows.add(new ReturnLine(item, line.returnedQuantity()));
                }
                return rows;
            }
        } catch (Exception exception) {
            java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.WARNING, "Authoritative returnable invoice lines could not be loaded; falling back to invoice items: " + exception.getMessage());
        }
        // Graceful fallback to provided invoice items so users are never blocked by network latency
        List<ReturnLine> fallbackRows = new ArrayList<>();
        if (invoiceItems != null) {
            for (InvoiceItem item : invoiceItems) {
                fallbackRows.add(new ReturnLine(item, 0.0));
            }
        }
        return fallbackRows;
    }

    private static List<ReturnLine> validate(List<ReturnLine> rows, LocalDate returnDate) {
        if (returnDate == null) throw new IllegalArgumentException("Select the return date.");
        List<ReturnLine> selected = rows.stream().filter(ReturnLine::selected).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one item to return.");
        for (ReturnLine row : selected) {
            if (row.returnQuantity() <= 0) {
                throw new IllegalArgumentException("Enter a return quantity for " + row.description());
            }
            if (row.returnQuantity() > row.available() + 0.0001) {
                throw new IllegalArgumentException(row.description() + " has only " +
                    formatQuantity(row.available()) + " available to return.");
            }
            if (row.reason().isBlank()) {
                throw new IllegalArgumentException("Enter a return reason for " + row.description());
            }
        }
        return selected;
    }

    private static String save(Type type, String invoiceNo, int partyId, LocalDate returnDate, List<ReturnLine> rows) {
        List<ReturnApiClient.CreateLine> lines=rows.stream().map(r->new ReturnApiClient.CreateLine(r.code(),r.sourceLineId(),r.returnQuantity(),r.returnAmount(),r.reason())).toList();
        String returnNo = new ReturnApiClient().create(new ReturnApiClient.CreateRequest(type.databaseValue,invoiceNo,partyId,returnDate.toString(),lines));
        if (type == Type.SALES) {
            ScreenRefreshPolicy.invalidate("sales-returns");
            ScreenRefreshPolicy.invalidate("sales-register");
        } else {
            ScreenRefreshPolicy.invalidate("purchase-returns");
            ScreenRefreshPolicy.invalidate("purchase-register");
        }
        return returnNo;
    }

    private static String money(double value) {
        return String.format(Locale.getDefault(), "₹ %,.2f", value);
    }

    private static String formatQuantity(double value) {
        return String.format(Locale.getDefault(), "%,.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static void warning(String message) {
        new OwnedAlert(Alert.AlertType.WARNING, message).showAndWait();
    }

    /** Mutable row model used by the editable return table. */
    private static final class ReturnLine {
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private final Long sourceLineId;
        private final StringProperty code;
        private final StringProperty description;
        private final DoubleProperty invoiced;
        private final DoubleProperty returned;
        private final DoubleProperty available;
        private final DoubleProperty returnQuantity = new SimpleDoubleProperty(0);
        private final DoubleProperty rate;
        private final DoubleProperty discount;
        private final DoubleProperty tax;
        private final double originalLineTotal;
        private final double alreadyReturnedAmount;
        private final StringProperty reason = new SimpleStringProperty("");

        ReturnLine(InvoiceItem item, double alreadyReturned) {
            sourceLineId = item.sourceLineId();
            code = new SimpleStringProperty(item.code());
            description = new SimpleStringProperty(item.description());
            invoiced = new SimpleDoubleProperty(item.quantity());
            returned = new SimpleDoubleProperty(alreadyReturned);
            available = new SimpleDoubleProperty(Math.max(0, item.quantity() - alreadyReturned));
            rate = new SimpleDoubleProperty(item.rate());
            discount = new SimpleDoubleProperty(item.discountPercent());
            tax = new SimpleDoubleProperty(item.taxPercent());
            originalLineTotal = item.lineTotal();
            alreadyReturnedAmount = item.alreadyReturnedAmount();
        }

        Long sourceLineId() { return sourceLineId; }
        boolean selected() { return selected.get(); }
        void setSelected(boolean value) { selected.set(value); }
        BooleanProperty selectedProperty() { return selected; }
        String code() { return code.get(); }
        StringProperty codeProperty() { return code; }
        String description() { return description.get(); }
        StringProperty descriptionProperty() { return description; }
        DoubleProperty invoicedProperty() { return invoiced; }
        DoubleProperty returnedProperty() { return returned; }
        double available() { return available.get(); }
        DoubleProperty availableProperty() { return available; }
        double returnQuantity() { return returnQuantity.get(); }
        void setReturnQuantity(double value) { returnQuantity.set(value); }
        DoubleProperty returnQuantityProperty() { return returnQuantity; }
        double rate() { return rate.get(); }
        DoubleProperty rateProperty() { return rate; }
        double discount() { return discount.get(); }
        double tax() { return tax.get(); }
        DoubleProperty taxProperty() { return tax; }
        String reason() { return reason.get() == null ? "" : reason.get().trim(); }
        void setReason(String value) { reason.set(value == null ? "" : value); }
        StringProperty reasonProperty() { return reason; }
        double returnAmount() {
            double qty = org.example.shared.DocumentCalculationEngine.quantity(returnQuantity());
            if (qty <= 0 || invoiced.get() <= 0) return 0;
            double remainingAmount = Math.max(0, originalLineTotal - alreadyReturnedAmount);
            double raw = qty + 0.0001 >= available() ? remainingAmount : (originalLineTotal / invoiced.get()) * qty;
            return java.math.BigDecimal.valueOf(raw).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
        }
    }
}
