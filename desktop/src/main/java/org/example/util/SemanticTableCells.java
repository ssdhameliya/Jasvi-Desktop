package org.example.util;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.TableCell;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Value-aware renderer for register status cells.
 * The value selects both the icon meaning and the colour.
 */
public final class SemanticTableCells {
    private SemanticTableCells() {}

    public static <S> TableCell<S, String> status(String semantic) {
        final String role = semantic == null ? "status" : semantic.toLowerCase(Locale.ROOT);
        return new CachedGraphicCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                reset(this, empty ? null : value);
                if (empty || value == null || value.isBlank()) {
                    clearState(this);
                    return;
                }
                apply(this, presentation(role, value));
            }
        };
    }

    public static <S> TableCell<S, Boolean> activeBoolean() {
        return new CachedGraphicCell<>() {
            private State currentState;
            @Override protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                setText(null);
                setGraphic(null);
                setStyle("");
                setAlignment(Pos.CENTER_LEFT);
                if (empty || value == null) {
                    if (currentState != null) {
                        getStyleClass().remove(currentState.styleClass);
                        currentState = null;
                    }
                    return;
                }
                if (!getStyleClass().contains("semantic-register-cell")) getStyleClass().add("semantic-register-cell");
                setText(value ? "Active" : "Inactive");
                Presentation p = value ? new Presentation("complete", State.SUCCESS) : new Presentation("cancel", State.DANGER);
                if (currentState != p.state) {
                    if (currentState != null) getStyleClass().remove(currentState.styleClass);
                    if (!getStyleClass().contains(p.state.styleClass)) getStyleClass().add(p.state.styleClass);
                    currentState = p.state;
                }
                setGraphic(graphic(p));
                setGraphicTextGap(5);
            }
        };
    }

    public static <S> TableCell<S, String> dueDate() {
        return new CachedGraphicCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                reset(this, empty ? null : value);
                if (empty || value == null || value.isBlank()) {
                    clearState(this);
                    return;
                }
                String v = value.trim().toUpperCase(Locale.ROOT);
                Presentation p;
                if (v.startsWith("PAID") || v.contains("CLOSED") || v.contains("SETTLED") || v.contains("COMPLETE")) {
                    p = new Presentation("complete", State.SUCCESS);
                } else if (v.contains("OVERDUE") || v.contains("PAST DUE")) {
                    p = new Presentation("warning", State.DANGER);
                } else if (v.contains("DELETED")) {
                    p = new Presentation("delete", State.DANGER);
                } else if (v.contains("CANCEL")) {
                    p = new Presentation("cancel", State.DANGER);
                } else if (v.contains("NOT SET") || v.contains("N/A")) {
                    p = new Presentation("calendar", State.NEUTRAL);
                } else if (v.contains("TODAY")) {
                    p = new Presentation("calendar", State.WARNING);
                } else if (v.matches(".*\\b([1-7])\\s+DAYS?\\b.*") || v.contains("SOON")) {
                    p = new Presentation("reminder", State.WARNING);
                } else {
                    // Future due dates that are not immediate use a calendar rather
                    // than the same clock glyph used for pending/soon states.
                    p = new Presentation("calendar", State.INFO);
                }
                apply(this, p);
            }
        };
    }

    public static <S> TableCell<S, LocalDate> date() {
        return new TableCell<>() {
            @Override
            protected void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(BusinessClock.formatDate(item));
                setAlignment(Pos.CENTER_LEFT);
            }
        };
    }

    public static <S> TableCell<S, String> dateString() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(BusinessClock.formatDate(item));
                setAlignment(Pos.CENTER_LEFT);
            }
        };
    }

    public static <S> TableCell<S, Instant> timestamp() {
        return new TableCell<>() {
            @Override
            protected void updateItem(Instant item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(BusinessClock.formatTimestamp(item));
                setAlignment(Pos.CENTER_LEFT);
            }
        };
    }

    public static <S> TableCell<S, String> timestampString() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(BusinessClock.formatTimestamp(item));
                setAlignment(Pos.CENTER_LEFT);
            }
        };
    }

    public static <S> TableCell<S, String> moneyString() {
        return moneyString("green");
    }

    public static <S> TableCell<S, String> moneyString(String color) {
        final String colorClass = color == null || color.isBlank() ? "erp-table-value-colour-green" : "erp-table-value-colour-" + color.toLowerCase(Locale.ROOT);
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setGraphic(null);
                    getStyleClass().removeAll("erp-table-value-colour-blue", "erp-table-value-colour-green", "erp-table-value-colour-orange", "erp-table-value-colour-purple", "erp-table-value-colour-pink", "erp-table-value-colour-teal", "erp-table-value-colour-indigo");
                    return;
                }
                setText(item);
                setAlignment(Pos.CENTER_RIGHT);
                if (!getStyleClass().contains(colorClass)) {
                    getStyleClass().removeAll("erp-table-value-colour-blue", "erp-table-value-colour-green", "erp-table-value-colour-orange", "erp-table-value-colour-purple", "erp-table-value-colour-pink", "erp-table-value-colour-teal", "erp-table-value-colour-indigo");
                    getStyleClass().add(colorClass);
                }
            }
        };
    }

    public static <S> TableCell<S, Double> money() {
        return money("green");
    }

    public static <S> TableCell<S, Double> money(String color) {
        final String colorClass = color == null || color.isBlank() ? "erp-table-value-colour-green" : "erp-table-value-colour-" + color.toLowerCase(Locale.ROOT);
        return new TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    getStyleClass().removeAll("erp-table-value-colour-blue", "erp-table-value-colour-green", "erp-table-value-colour-orange", "erp-table-value-colour-purple", "erp-table-value-colour-pink", "erp-table-value-colour-teal", "erp-table-value-colour-indigo");
                    return;
                }
                setText(String.format(Locale.of("en", "IN"), "₹ %,.2f", item));
                setAlignment(Pos.CENTER_RIGHT);
                if (!getStyleClass().contains(colorClass)) {
                    getStyleClass().removeAll("erp-table-value-colour-blue", "erp-table-value-colour-green", "erp-table-value-colour-orange", "erp-table-value-colour-purple", "erp-table-value-colour-pink", "erp-table-value-colour-teal", "erp-table-value-colour-indigo");
                    getStyleClass().add(colorClass);
                }
            }
        };
    }

    /**
     * Value-aware semantic renderer for numerical currency and amounts.
     * Evaluates number polarity, role semantics (credit, debit, balance, total)
     * and applies canonical theme styles with zero color leaks and right alignment.
     */
    public static <S, N extends Number> TableCell<S, N> amount() {
        return amount("amount");
    }

    public static <S, N extends Number> TableCell<S, N> amount(String role) {
        final String r = role == null ? "amount" : role.toLowerCase(Locale.ROOT).trim();
        return new TableCell<>() {
            private String currentColour;

            @Override
            protected void updateItem(N item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    clearColour(this);
                    currentColour = null;
                    return;
                }
                double val = item.doubleValue();
                setText(String.format(Locale.of("en", "IN"), "₹ %,.2f", val));
                setAlignment(Pos.CENTER_RIGHT);

                String targetColour = resolveAmountColour(r, val);
                if (!java.util.Objects.equals(currentColour, targetColour)) {
                    clearColour(this);
                    if (targetColour != null) {
                        getStyleClass().add("erp-table-value-colour-" + targetColour);
                    }
                    currentColour = targetColour;
                }
            }
        };
    }

    /**
     * Value-aware semantic renderer for String-formatted currency and amounts.
     */
    public static <S> TableCell<S, String> amountString(String role) {
        final String r = role == null ? "amount" : role.toLowerCase(Locale.ROOT).trim();
        return new TableCell<>() {
            private String currentColour;

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setGraphic(null);
                    clearColour(this);
                    currentColour = null;
                    return;
                }
                setText(item);
                setAlignment(Pos.CENTER_RIGHT);

                double val = parseNumeric(item);
                String targetColour = resolveAmountColour(r, val);
                if (!java.util.Objects.equals(currentColour, targetColour)) {
                    clearColour(this);
                    if (targetColour != null) {
                        getStyleClass().add("erp-table-value-colour-" + targetColour);
                    }
                    currentColour = targetColour;
                }
            }
        };
    }

    /**
     * Value-aware semantic renderer for numerical quantities and unformatted decimals.
     */
    public static <S, N extends Number> TableCell<S, N> number() {
        return number("number", 2);
    }

    public static <S, N extends Number> TableCell<S, N> number(String role) {
        return number(role, 2);
    }

    public static <S, N extends Number> TableCell<S, N> number(String role, int decimals) {
        final String r = role == null ? "number" : role.toLowerCase(Locale.ROOT).trim();
        final String fmt = "%," + (decimals > 0 ? ("." + decimals) : "") + "f";
        return new TableCell<>() {
            private String currentColour;

            @Override
            protected void updateItem(N item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    clearColour(this);
                    currentColour = null;
                    return;
                }
                double val = item.doubleValue();
                setText(String.format(Locale.of("en", "IN"), fmt, val));
                setAlignment(Pos.CENTER_RIGHT);

                String targetColour = resolveAmountColour(r, val);
                if (!java.util.Objects.equals(currentColour, targetColour)) {
                    clearColour(this);
                    if (targetColour != null) {
                        getStyleClass().add("erp-table-value-colour-" + targetColour);
                    }
                    currentColour = targetColour;
                }
            }
        };
    }

    private static String resolveAmountColour(String role, double val) {
        if (role.contains("debit") || role.contains("expense") || role.contains("withdrawal") || role.contains("outflow") || role.contains("refund")) {
            return val > 0.009 ? "pink" : "green";
        }
        if (role.contains("credit") || role.contains("deposit") || role.contains("inflow") || role.contains("paid") || role.contains("receipt")) {
            return val < -0.009 ? "pink" : "green";
        }
        if (role.contains("balance") || role.contains("outstanding") || role.contains("due")) {
            if (val > 0.009) return "orange";
            return "green"; // Settled ₹0.00 or credit balance
        }
        if (role.contains("tax") || role.contains("gst")) {
            return "purple";
        }
        if (role.contains("discount")) {
            return "orange";
        }
        if (role.contains("qty") || role.contains("quantity") || role.contains("number") || role.contains("rate") || role.contains("price")) {
            if (val < -0.009) return "pink";
            return "teal";
        }
        if (role.contains("total") || role.contains("invoice") || role.contains("bill") || role.contains("net")) {
            if (val < -0.009) return "pink";
            return "blue";
        }
        // General amount
        if (val < -0.009) return "pink";
        if (val > 0.009) return "green";
        return "blue";
    }

    private static void clearColour(TableCell<?, ?> cell) {
        cell.getStyleClass().removeAll(
                "erp-table-value-colour-blue",
                "erp-table-value-colour-green",
                "erp-table-value-colour-orange",
                "erp-table-value-colour-purple",
                "erp-table-value-colour-pink",
                "erp-table-value-colour-teal",
                "erp-table-value-colour-indigo",
                "erp-value-positive",
                "erp-value-negative"
        );
    }

    private static double parseNumeric(String text) {
        if (text == null) return 0.0;
        try {
            String clean = text.replace("₹", "").replace(",", "").replace(" ", "").trim();
            if (clean.isBlank() || "-".equals(clean) || "—".equals(clean)) return 0.0;
            return Double.parseDouble(clean);
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static Presentation presentation(String role, String value) {
        String v = value.trim().toUpperCase(Locale.ROOT);
        State state = classify(v);
        return switch (role) {
            case "email" -> {
                if (v.contains("FAIL") || v.contains("ERROR")) yield new Presentation("warning", State.DANGER);
                // NOT SENT contains the word SENT, so it must be classified first.
                if (v.contains("NOT SENT") || v.contains("UNSENT")) yield new Presentation("email", State.WARNING);
                if (v.contains("SENT") || v.contains("DELIVERED")) yield new Presentation("sent", State.SUCCESS);
                if (v.contains("PROCESS") || v.contains("QUEUE")) yield new Presentation("refresh", State.INFO);
                yield new Presentation("email", State.WARNING);
            }
            case "document" -> {
                if (v.contains("DELETE")) yield new Presentation("delete", State.DANGER);
                if (v.contains("CANCEL") || v.contains("REJECT")) yield new Presentation("cancel", State.DANGER);
                if (v.contains("RETURN")) yield new Presentation("return", State.SUCCESS);
                if (v.contains("DRAFT")) yield new Presentation("draft", State.WARNING);
                if (v.contains("COMPLETE") || v.contains("POSTED") || v.contains("APPROVED")) yield new Presentation("complete", State.SUCCESS);
                if (v.contains("IN PROGRESS") || v.contains("PROCESS")) yield new Presentation("refresh", State.INFO);
                if (v.contains("PENDING") || v.contains("OPEN")) yield new Presentation("reminder", State.WARNING);
                yield new Presentation("document", state);
            }
            case "return" -> {
                if (v.contains("DELETE")) yield new Presentation("delete", State.DANGER);
                if (v.contains("CANCEL") || v.contains("REJECT") || v.contains("FAIL")) yield new Presentation("cancel", State.DANGER);
                if (v.contains("COMPLETE") || v.contains("RETURNED")) yield new Presentation("return", State.SUCCESS);
                if (v.contains("APPROVED") || v.contains("ACCEPTED")) yield new Presentation("complete", State.SUCCESS);
                if (v.contains("PARTIAL") || v.contains("IN PROGRESS") || v.contains("PROCESS")) yield new Presentation("refresh", State.INFO);
                if (v.contains("PENDING") || v.contains("OPEN") || v.contains("CREATED") || v.contains("WAIT")) yield new Presentation("reminder", State.WARNING);
                yield new Presentation("document", state);
            }
            case "refund" -> {
                if (v.contains("FAIL") || v.contains("CANCEL") || v.contains("REJECT")) yield new Presentation("warning", State.DANGER);
                if (v.contains("REFUNDED") || v.contains("COMPLETE") || v.contains("PAID")) yield new Presentation("refund", State.SUCCESS);
                if (v.contains("PARTIAL")) yield new Presentation("partial", State.INFO);
                if (v.contains("IN PROGRESS") || v.contains("PROCESS")) yield new Presentation("refresh", State.INFO);
                if (v.contains("PENDING") || v.contains("OPEN") || v.contains("WAIT")) yield new Presentation("reminder", State.WARNING);
                yield new Presentation("payment", state);
            }
            case "payment" -> {
                if (v.contains("OVERDUE") || v.contains("FAIL")) yield new Presentation("warning", State.DANGER);
                if (v.contains("PAID") || v.contains("SETTLED") || v.contains("COMPLETE")) yield new Presentation("payment", State.SUCCESS);
                if (v.contains("PARTIAL")) yield new Presentation("partial", State.INFO);
                if (v.contains("IN PROGRESS") || v.contains("PROCESS")) yield new Presentation("refresh", State.INFO);
                if (v.contains("PENDING") || v.contains("OPEN") || v.contains("DUE")) yield new Presentation("reminder", State.WARNING);
                yield new Presentation("payment", state);
            }
            case "reconcile", "reconciliation" -> {
                if (v.contains("FULL") || v.contains("RECONCILED") || v.contains("MATCHED")) yield new Presentation("complete", State.SUCCESS);
                if (v.contains("PARTIAL")) yield new Presentation("partial", State.INFO);
                if (v.contains("REVIEW") || v.contains("OPEN") || v.contains("PENDING")) yield new Presentation("reminder", State.WARNING);
                if (v.contains("FAIL") || v.contains("ERROR") || v.contains("CONFLICT")) yield new Presentation("warning", State.DANGER);
                if (v.contains("IMPORT")) yield new Presentation("status", State.INFO);
                yield new Presentation("status", state);
            }
            case "bank" -> {
                if (v.contains("MATCHED") || v.contains("RECONCILED")) yield new Presentation("complete", State.SUCCESS);
                if (v.contains("EXPENSE")) yield new Presentation("payment", State.PURPLE);
                if (v.contains("REVIEW")) yield new Presentation("status", State.INFO);
                if (v.contains("SUGGEST")) yield new Presentation("reminder", State.WARNING);
                if (v.contains("IGNORE")) yield new Presentation("status", State.NEUTRAL);
                if (v.contains("UNMATCH") || v.contains("FAIL") || v.contains("ERROR")) yield new Presentation("warning", State.DANGER);
                yield new Presentation("status", state);
            }
            case "inventory", "stock" -> {
                if (v.contains("OUT OF STOCK") || v.contains("OUT-OF-STOCK")) yield new Presentation("warning", State.DANGER);
                if (v.contains("LOW")) yield new Presentation("reminder", State.WARNING);
                if (v.contains("IN STOCK") || v.contains("AVAILABLE")) yield new Presentation("complete", State.SUCCESS);
                yield new Presentation("inventory", state);
            }
            case "bank-entry", "finance" -> {
                if (v.contains("DEPOSIT") || v.contains("CREDIT")) yield new Presentation("credit", State.SUCCESS);
                if (v.contains("WITHDRAW") || v.contains("DEBIT")) yield new Presentation("debit", State.DANGER);
                if (v.contains("EXPENSE")) yield new Presentation("payment", State.PURPLE);
                yield new Presentation("payment", state);
            }
            case "validation", "import-action" -> {
                if (v.contains("FAILED") || v.contains("ERROR") || v.contains("CONFLICT") || v.contains("BANK-LINKED")) yield new Presentation("warning", State.DANGER);
                if (v.contains("REVIEW") || v.contains("DUPLICATE")) yield new Presentation("reminder", State.WARNING);
                if (v.contains("UPDATE")) yield new Presentation("refresh", State.INFO);
                if (v.contains("ALREADY CURRENT") || v.contains("PASSED") || v.contains("VALIDATED") || v.contains("CREATED") || v.contains("NEW") || v.contains("IMPORTED")) yield new Presentation("complete", State.SUCCESS);
                yield new Presentation("status", state);
            }
            default -> new Presentation(iconForState(state), state);
        };
    }

    private static String iconForState(State state) {
        return switch (state) {
            case SUCCESS -> "complete";
            case INFO, PURPLE -> "status";
            case WARNING -> "reminder";
            case DANGER -> "warning";
            case NEUTRAL -> "document";
        };
    }

    private static final String STATE_PROP = "erp.semantic.state";

    private static void reset(TableCell<?, String> cell, String value) {
        cell.setText(value);
        cell.setGraphic(null);
        cell.setStyle("");
        cell.setAlignment(Pos.CENTER_LEFT);
        if (!cell.getStyleClass().contains("semantic-register-cell")) {
            cell.getStyleClass().add("semantic-register-cell");
        }
    }

    private static void clearState(TableCell<?, ?> cell) {
        State current = (State) cell.getProperties().remove(STATE_PROP);
        if (current != null) {
            cell.getStyleClass().remove(current.styleClass);
        }
    }

    private static void apply(TableCell<?, String> cell, Presentation p) {
        State current = (State) cell.getProperties().get(STATE_PROP);
        if (current != p.state) {
            if (current != null) {
                cell.getStyleClass().remove(current.styleClass);
            }
            if (!cell.getStyleClass().contains(p.state.styleClass)) {
                cell.getStyleClass().add(p.state.styleClass);
            }
            cell.getProperties().put(STATE_PROP, p.state);
        }
        // VirtualFlow reuses the same TableCell instances while scrolling. Cache one
        // status glyph per semantic/state on each realized cell so row recycling does
        // not allocate a fresh FontIcon/CSS node on every scroll pulse.
        if (cell instanceof CachedGraphicCell<?, ?> cached) cell.setGraphic(cached.graphic(p));
        else cell.setGraphic(IconFactory.statusIcon(p.icon, p.state.iconState));
        cell.setGraphicTextGap(5);
    }

    private abstract static class CachedGraphicCell<S, T> extends TableCell<S, T> {
        private final Map<String, Node> graphicCache = new HashMap<>();

        protected final Node graphic(Presentation presentation) {
            String key = presentation.icon + "|" + presentation.state.iconState;
            return graphicCache.computeIfAbsent(key,
                    ignored -> IconFactory.statusIcon(presentation.icon, presentation.state.iconState));
        }
    }

    private static State classify(String v) {
        if (v.contains("FAILED") || v.contains("ERROR") || v.contains("CANCEL") || v.contains("DELETE")
                || v.contains("OVERDUE") || v.contains("LOCKED") || v.contains("REJECT") || v.contains("INACTIVE")) return State.DANGER;
        if (v.contains("PARTIAL") || v.contains("IN PROGRESS") || v.contains("PROCESSING") || v.contains("IMPORTED")) return State.INFO;
        if (v.contains("NOT SENT") || v.contains("PENDING") || v.contains("DRAFT") || v.contains("OPEN")
                || v.contains("DUE") || v.contains("SNOOZE")) return State.WARNING;
        if (v.contains("SENT") || v.contains("PAID") || v.contains("COMPLETED") || v.contains("RETURNED")
                || v.contains("ACTIVE") || v.contains("APPROVED") || v.contains("ACCEPTED") || v.contains("ENABLED")
                || v.contains("VERIFIED") || v.contains("SUCCESS") || v.contains("PASSED") || v.contains("POSTED")) return State.SUCCESS;
        return State.NEUTRAL;
    }

    private record Presentation(String icon, State state) {}

    private enum State {
        SUCCESS("pill-success","success"),
        INFO("pill-info","info"),
        PURPLE("pill-info","purple"),
        WARNING("pill-warning","warning"),
        DANGER("pill-danger","danger"),
        NEUTRAL("pill-neutral","neutral");
        private final String styleClass;
        private final String iconState;
        State(String styleClass,String iconState){this.styleClass=styleClass;this.iconState=iconState;}
    }
}
