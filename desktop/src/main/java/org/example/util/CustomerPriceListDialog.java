package org.example.util;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import org.example.api.customer360.Customer360ApiClient;
import org.example.model.Item;
import org.example.service.ItemService;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Customer-specific Price List & Quantity Slab manager dialog.
 * Allows operators to define negotiated rates, discount %, quantity break slabs,
 * and optional validity windows for any customer and item.
 */
public final class CustomerPriceListDialog {

    private CustomerPriceListDialog() {}

    public static void show(Node owner, int customerId, String customerName, List<Item> knownItems,
                            String preselectItemCode, Runnable onChanged) {
        if (customerId <= 0) {
            AppDialogService.warning(owner, "Select Customer", "Customer Required",
                    "Please select a customer before managing customer price rules.");
            return;
        }
        Customer360ApiClient api = new Customer360ApiClient();
        OwnedDialog<Void> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Customer Price List & Quantity Slabs — " + (customerName == null ? "" : customerName));
        AppDialogRenderer.configureWorkspace(
                dialog,
                "pricing",
                "Customer Price List & Quantity Slabs",
                (customerName == null || customerName.isBlank() ? "Customer #" + customerId : customerName),
                "Define party-specific contracted rates and quantity break slabs applied automatically during Sales Entry",
                ""
        );

        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().setAll(ButtonType.CLOSE);
        pane.setPrefSize(1020, 680);
        pane.setMinSize(860, 560);

        VBox content = new VBox(12);
        content.setPadding(new Insets(8, 12, 8, 12));
        VBox.setVgrow(content, Priority.ALWAYS);

        // Rule Authoring Form
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.getStyleClass().addAll("approved-card", "customer-price-rule-form");
        form.setPadding(new Insets(10, 12, 10, 12));
        for (double pct : new double[]{32, 12, 14, 12, 15, 15}) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(pct);
            cc.setHgrow(Priority.ALWAYS);
            form.getColumnConstraints().add(cc);
        }

        ComboBox<Item> cmbItem = new ComboBox<>();
        cmbItem.setMaxWidth(Double.MAX_VALUE);
        cmbItem.getStyleClass().add("approved-input");
        cmbItem.setPromptText("Select item...");
        cmbItem.setConverter(new StringConverter<>() {
            @Override
            public String toString(Item item) {
                return item == null ? "" : safe(item.getItemCode()) + " - " + safe(item.getDescription());
            }

            @Override
            public Item fromString(String s) {
                if (s == null || s.isBlank()) return null;
                String t = s.trim();
                for (Item item : cmbItem.getItems()) {
                    if (toString(item).equalsIgnoreCase(t) || safe(item.getItemCode()).equalsIgnoreCase(t)) {
                        return item;
                    }
                }
                return cmbItem.getValue();
            }
        });
        List<Item> initialItems = knownItems != null ? knownItems : List.of();
        cmbItem.setItems(FXCollections.observableArrayList(initialItems));

        TextField txtMinQty = new TextField("1");
        txtMinQty.setPromptText("Min Qty");
        txtMinQty.getStyleClass().add("approved-input");

        TextField txtRate = new TextField();
        txtRate.setPromptText("Rate (₹)");
        txtRate.getStyleClass().add("approved-input");

        TextField txtDisc = new TextField("0");
        txtDisc.setPromptText("Disc %");
        txtDisc.getStyleClass().add("approved-input");

        DatePicker dpFrom = new DatePicker();
        dpFrom.setMaxWidth(Double.MAX_VALUE);
        dpFrom.setPromptText("Optional");
        dpFrom.getStyleClass().add("approved-input");

        DatePicker dpTo = new DatePicker();
        dpTo.setMaxWidth(Double.MAX_VALUE);
        dpTo.setPromptText("Optional");
        dpTo.getStyleClass().add("approved-input");
        DatePickerFormatter.attachAll(dpFrom, dpTo);

        TextField txtNotes = new TextField();
        txtNotes.setPromptText("Optional contract note / reference");
        txtNotes.getStyleClass().add("approved-input");

        CheckBox chkActive = new CheckBox("Active rule");
        chkActive.setSelected(true);
        chkActive.getStyleClass().add("approved-checkbox");

        form.add(labeledField("Item *", "item", cmbItem), 0, 0);
        form.add(labeledField("Min Qty *", "quantity", txtMinQty), 1, 0);
        form.add(labeledField("Special Rate (₹) *", "amount", txtRate), 2, 0);
        form.add(labeledField("Discount %", "discount", txtDisc), 3, 0);
        form.add(labeledField("Valid From", "date", dpFrom), 4, 0);
        form.add(labeledField("Valid To", "date", dpTo), 5, 0);

        HBox row2 = new HBox(10);
        row2.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(txtNotes, Priority.ALWAYS);

        Button btnSaveRule = new Button("Save Price Rule");
        btnSaveRule.getStyleClass().addAll("approved-button", "approved-primary-button");
        UiActionIcons.apply(btnSaveRule, "save", "Save rule");

        Button btnClearRule = new Button("Clear / New");
        btnClearRule.getStyleClass().addAll("approved-button", "approved-secondary-button");
        UiActionIcons.apply(btnClearRule, "add", "Clear form");

        Button btnDeleteRule = new Button("Delete Selected");
        btnDeleteRule.getStyleClass().addAll("approved-button", "approved-danger-button");
        btnDeleteRule.setDisable(true);
        UiActionIcons.apply(btnDeleteRule, "delete", "Delete selected rule");

        row2.getChildren().addAll(txtNotes, chkActive, btnClearRule, btnDeleteRule, btnSaveRule);
        form.add(row2, 0, 1, 6, 1);

        // Rules Table
        TableView<Customer360ApiClient.PriceRuleRow> table = new TableView<>();
        PopupTableWorkspace.prepareTable(table, "erp-table-profile-master");
        ObservableList<Customer360ApiClient.PriceRuleRow> rows = FXCollections.observableArrayList();
        table.setItems(rows);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<Customer360ApiClient.PriceRuleRow, String> colCode = new TableColumn<>("Item Code");
        colCode.setCellValueFactory(v -> new SimpleStringProperty(safe(v.getValue().itemCode())));

        TableColumn<Customer360ApiClient.PriceRuleRow, String> colDesc = new TableColumn<>("Item");
        colDesc.setCellValueFactory(v -> new SimpleStringProperty(safe(v.getValue().itemDescription())));

        TableColumn<Customer360ApiClient.PriceRuleRow, Number> colMinQty = new TableColumn<>("Qty");
        colMinQty.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().minQty()));

        TableColumn<Customer360ApiClient.PriceRuleRow, Number> colRate = new TableColumn<>("Rate (₹)");
        colRate.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().specialRate()));
        colRate.setCellFactory(c -> SemanticTableCells.amount("total"));

        TableColumn<Customer360ApiClient.PriceRuleRow, Number> colDisc = new TableColumn<>("Discount %");
        colDisc.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().discountPercent()));

        TableColumn<Customer360ApiClient.PriceRuleRow, String> colValid = new TableColumn<>("Period");
        colValid.setCellValueFactory(v -> {
            String f = safe(v.getValue().validFrom()), t = safe(v.getValue().validTo());
            String txt = (f.isBlank() && t.isBlank()) ? "Always Active"
                    : (f.isBlank() ? "Until " + BusinessClock.formatDate(t)
                    : (t.isBlank() ? "From " + BusinessClock.formatDate(f)
                    : BusinessClock.formatDate(f) + " - " + BusinessClock.formatDate(t)));
            return new SimpleStringProperty(txt);
        });

        TableColumn<Customer360ApiClient.PriceRuleRow, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().active() ? "ACTIVE" : "INACTIVE"));
        colStatus.setCellFactory(c -> SemanticTableCells.status("status"));

        table.getColumns().setAll(colCode, colDesc, colMinQty, colRate, colDisc, colValid, colStatus);
        DynamicTableLayoutManager.install(table);

        final Customer360ApiClient.PriceRuleRow[] editingRule = {null};

        Runnable resetForm = () -> {
            editingRule[0] = null;
            btnDeleteRule.setDisable(true);
            btnSaveRule.setText("Save Price Rule");
            txtMinQty.setText("1");
            txtDisc.setText("0");
            dpFrom.setValue(null);
            dpTo.setValue(null);
            txtNotes.clear();
            chkActive.setSelected(true);
            Item sel = cmbItem.getValue();
            txtRate.setText(sel != null ? String.format(Locale.ROOT, "%.2f", sel.getSellingPrice()) : "");
            table.getSelectionModel().clearSelection();
        };

        cmbItem.valueProperty().addListener((obs, o, item) -> {
            if (item != null && editingRule[0] == null && (txtRate.getText() == null || txtRate.getText().isBlank())) {
                txtRate.setText(String.format(Locale.ROOT, "%.2f", item.getSellingPrice()));
            }
        });

        table.getSelectionModel().selectedItemProperty().addListener((obs, o, r) -> {
            if (r == null) {
                btnDeleteRule.setDisable(true);
                return;
            }
            editingRule[0] = r;
            btnDeleteRule.setDisable(false);
            btnSaveRule.setText("Update Rule");
            cmbItem.getItems().stream()
                    .filter(it -> safe(it.getItemCode()).equalsIgnoreCase(safe(r.itemCode())))
                    .findFirst().ifPresent(cmbItem::setValue);
            txtMinQty.setText(String.valueOf(r.minQty()));
            txtRate.setText(String.format(Locale.ROOT, "%.2f", r.specialRate()));
            txtDisc.setText(String.format(Locale.ROOT, "%.2f", r.discountPercent()));
            dpFrom.setValue(parseLocalDate(r.validFrom()));
            dpTo.setValue(parseLocalDate(r.validTo()));
            txtNotes.setText(safe(r.notes()));
            chkActive.setSelected(r.active());
        });

        Runnable reloadRules = () -> UiTaskExecutor.submitLatest(
                "customer-price-rules-load-" + customerId,
                () -> api.priceRules(customerId),
                list -> rows.setAll(list == null ? List.of() : list),
                err -> AppDialogService.error(table, "Load Failed", "Could not load price rules", safe(err.getMessage()))
        );

        if (initialItems.isEmpty()) {
            UiTaskExecutor.submitLatest(
                    "customer-price-rules-items",
                    () -> new ItemService().search("", 250),
                    items -> {
                        cmbItem.setItems(FXCollections.observableArrayList(items == null ? List.of() : items));
                        if (preselectItemCode != null && !preselectItemCode.isBlank()) {
                            cmbItem.getItems().stream()
                                    .filter(it -> preselectItemCode.equalsIgnoreCase(safe(it.getItemCode())))
                                    .findFirst().ifPresent(cmbItem::setValue);
                        }
                    },
                    ignored -> {}
            );
        } else if (preselectItemCode != null && !preselectItemCode.isBlank()) {
            cmbItem.getItems().stream()
                    .filter(it -> preselectItemCode.equalsIgnoreCase(safe(it.getItemCode())))
                    .findFirst().ifPresent(cmbItem::setValue);
        }

        btnClearRule.setOnAction(e -> resetForm.run());

        btnSaveRule.setOnAction(e -> {
            Item item = cmbItem.getValue();
            if (item == null || safe(item.getItemCode()).isBlank()) {
                AppDialogService.warning(table, "Validation", "Item Required", "Select an item for the price rule.");
                return;
            }
            try {
                double minQty = Double.parseDouble(txtMinQty.getText().trim());
                double rate = Double.parseDouble(txtRate.getText().trim());
                double disc = txtDisc.getText().isBlank() ? 0 : Double.parseDouble(txtDisc.getText().trim());
                if (minQty <= 0) throw new IllegalArgumentException("Minimum quantity must be greater than zero.");
                if (rate < 0) throw new IllegalArgumentException("Special rate cannot be negative.");
                if (disc < 0 || disc > 100) throw new IllegalArgumentException("Discount % must be between 0 and 100.");
                LocalDate vf = dpFrom.getValue(), vt = dpTo.getValue();
                if (vf != null && vt != null && vf.isAfter(vt)) {
                    throw new IllegalArgumentException("Valid From date cannot be after Valid To date.");
                }
                var payload = new Customer360ApiClient.PriceRuleSave(
                        editingRule[0] == null ? null : editingRule[0].id(),
                        item.getItemCode(),
                        minQty,
                        rate,
                        disc,
                        vf == null ? "" : vf.toString(),
                        vt == null ? "" : vt.toString(),
                        txtNotes.getText(),
                        chkActive.isSelected(),
                        editingRule[0] == null ? 0L : editingRule[0].rowVersion()
                );
                btnSaveRule.setDisable(true);
                UiTaskExecutor.submitAction(
                        "customer-price-rule-save-" + customerId,
                        () -> api.savePriceRule(customerId, payload),
                        saved -> {
                            btnSaveRule.setDisable(false);
                            resetForm.run();
                            reloadRules.run();
                            if (onChanged != null) onChanged.run();
                            AppDialogService.success(table, "Saved", "Price rule saved for " + item.getItemCode() + ".");
                        },
                        err -> {
                            btnSaveRule.setDisable(false);
                            AppDialogService.error(table, "Save Failed", "Could not save price rule", safe(err.getMessage()));
                        }
                );
            } catch (Exception ex) {
                AppDialogService.warning(table, "Invalid Rule", "Check Price Rule Fields",
                        ex instanceof NumberFormatException ? "Enter valid numbers for Min Qty, Rate, and Discount %." : ex.getMessage());
            }
        });

        btnDeleteRule.setOnAction(e -> {
            Customer360ApiClient.PriceRuleRow sel = editingRule[0];
            if (sel == null) return;
            if (!AppDialogService.confirm(table, "Delete Price Rule", "Remove Contracted Slab?",
                    "Delete rule for " + sel.itemCode() + " (Qty >= " + sel.minQty() + " @ ₹ " + sel.specialRate() + ")?")) {
                return;
            }
            UiTaskExecutor.submitAction(
                    "customer-price-rule-delete-" + sel.id(),
                    () -> {
                        api.deletePriceRule(customerId, sel.id(), sel.rowVersion());
                        return null;
                    },
                    ok -> {
                        resetForm.run();
                        reloadRules.run();
                        if (onChanged != null) onChanged.run();
                    },
                    err -> AppDialogService.error(table, "Delete Failed", "Could not delete rule", safe(err.getMessage()))
            );
        });

        content.getChildren().addAll(form, table);
        pane.setContent(content);
        SearchableComboBoxHelper.makeSearchable(cmbItem, it -> it == null ? "" : safe(it.getItemCode()) + " - " + safe(it.getDescription()));
        reloadRules.run();
        dialog.showAndWait();
    }

    private static VBox labeledField(String caption, String semantic, Node control) {
        Label lbl = new Label(caption);
        lbl.getStyleClass().add("field-label");
        lbl.setGraphic(IconFactory.compactIcon(semantic, 13));
        IconFactory.applySemanticLabelColour(lbl, semantic);
        lbl.getProperties().put("erp-icon-preserve", true);
        VBox box = new VBox(4, lbl, control);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static LocalDate parseLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim().length() >= 10 ? s.trim().substring(0, 10) : s.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }
}
