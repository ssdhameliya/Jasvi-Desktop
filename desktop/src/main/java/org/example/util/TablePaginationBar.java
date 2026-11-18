package org.example.util;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Reusable, zero-truncation pagination bar for high-volume JavaFX TableViews
 * across enterprise registers and popup dialogs (Light/Dark theme symmetric).
 */
public final class TablePaginationBar<T> extends HBox {

    private final TableView<T> targetTable;
    private final Label lblSummary = new Label("Showing 0 Records");
    private final Label lblRowsPerPage = new Label("Rows per page");
    private final ComboBox<Integer> cmbPageSize = new ComboBox<>(FXCollections.observableArrayList(25, 50, 100, 200));
    private final Button btnFirst = new Button("«");
    private final Button btnPrev = new Button("‹");
    private final Label lblPage = new Label("1 / 1");
    private final Button btnNext = new Button("›");
    private final Button btnLast = new Button("»");

    private List<T> backingItems = List.of();
    private int currentPage = 1;
    private int pageSize;

    public TablePaginationBar(TableView<T> targetTable) {
        this(targetTable, 25);
    }

    public TablePaginationBar(TableView<T> targetTable, int defaultPageSize) {
        this.targetTable = Objects.requireNonNull(targetTable, "targetTable");
        this.pageSize = defaultPageSize > 0 ? defaultPageSize : 25;
        if (!cmbPageSize.getItems().contains(this.pageSize)) {
            cmbPageSize.getItems().add(0, this.pageSize);
        }

        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        setPadding(new Insets(6, 4, 2, 4));
        getStyleClass().add("erp-pagination-bar");

        lblSummary.getStyleClass().add("erp-pagination-summary");
        lblSummary.setMinWidth(Region.USE_PREF_SIZE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        lblRowsPerPage.getStyleClass().add("erp-pagination-label");
        lblRowsPerPage.setMinWidth(Region.USE_PREF_SIZE);

        cmbPageSize.setValue(this.pageSize);
        cmbPageSize.getStyleClass().addAll("approved-combobox", "erp-pagination-size-combo");
        cmbPageSize.setMinWidth(Region.USE_PREF_SIZE);
        cmbPageSize.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal > 0 && newVal != this.pageSize) {
                this.pageSize = newVal;
                this.currentPage = 1;
                renderPage();
            }
        });

        configureNavButton(btnFirst, "First page", () -> goToPage(1));
        configureNavButton(btnPrev, "Previous page", () -> goToPage(currentPage - 1));
        configureNavButton(btnNext, "Next page", () -> goToPage(currentPage + 1));
        configureNavButton(btnLast, "Last page", () -> goToPage(totalPages()));

        lblPage.getStyleClass().add("erp-pagination-page-pill");
        lblPage.setMinWidth(Region.USE_PREF_SIZE);

        HBox navGroup = new HBox(4, btnFirst, btnPrev, lblPage, btnNext, btnLast);
        navGroup.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(lblSummary, spacer, lblRowsPerPage, cmbPageSize, navGroup);
        renderPage();
    }

    private void configureNavButton(Button button, String tip, Runnable action) {
        button.getStyleClass().addAll("approved-button", "erp-pagination-nav-btn");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(e -> action.run());
    }

    /** Replaces the complete filtered dataset and resets to page 1. */
    public void setAllItems(List<T> items) {
        this.backingItems = items == null ? List.of() : new ArrayList<>(items);
        this.currentPage = 1;
        renderPage();
    }

    /** Automatically synchronizes pagination whenever a FilteredList or ObservableList changes. */
    public void bindTo(ObservableList<T> sourceList) {
        Objects.requireNonNull(sourceList, "sourceList");
        setAllItems(sourceList);
        sourceList.addListener((ListChangeListener<T>) change -> setAllItems(sourceList));
    }

    public List<T> getAllItems() {
        return List.copyOf(backingItems);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int totalPages() {
        int total = backingItems.size();
        return total <= 0 ? 1 : (int) Math.ceil((double) total / pageSize);
    }

    public void goToPage(int targetPage) {
        int pages = totalPages();
        int clamped = Math.max(1, Math.min(targetPage, pages));
        if (clamped != currentPage) {
            currentPage = clamped;
            renderPage();
        }
    }

    public void refresh() {
        renderPage();
    }

    private void renderPage() {
        int total = backingItems.size();
        int pages = totalPages();
        if (currentPage > pages) currentPage = pages;
        if (currentPage < 1) currentPage = 1;

        if (total == 0) {
            targetTable.getItems().clear();
            lblSummary.setText("Showing 0 Records");
        } else {
            int fromIndex = (currentPage - 1) * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, total);
            targetTable.getItems().setAll(backingItems.subList(fromIndex, toIndex));
            lblSummary.setText(String.format(Locale.ENGLISH, "Showing %,d–%,d of %,d Record%s",
                    fromIndex + 1, toIndex, total, total == 1 ? "" : "s"));
        }

        lblPage.setText(currentPage + " / " + pages);
        btnFirst.setDisable(currentPage <= 1);
        btnPrev.setDisable(currentPage <= 1);
        btnNext.setDisable(currentPage >= pages);
        btnLast.setDisable(currentPage >= pages);
    }
}
