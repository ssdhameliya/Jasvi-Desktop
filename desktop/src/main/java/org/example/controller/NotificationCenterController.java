package org.example.controller;

import org.example.util.ScreenRefreshPolicy;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.navigation.DeepLinkRouter;
import org.example.navigation.ScreenLifecycle;
import org.example.service.NotificationService;
import org.example.service.NotificationService.NotificationItem;
import org.example.service.NotificationService.ResolvedLink;
import org.example.util.BusinessClock;
import org.example.util.IconFactory;
import org.example.util.AppDialogService;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Premium Notification Center with exact-record actions and stable category filtering. */
public final class NotificationCenterController implements ScreenLifecycle {
    private static final String ALL = "All Notifications";
    private static final List<String> BASE_FILTERS = List.of(
            ALL, "Unread", "Action Needed", "Approval", "Sales", "Purchases", "Quotations",
            "Returns", "Payments", "Inventory", "Reminders", "Communication", "GST",
            "EWay", "Tally", "Ledger", "CRM", "Barcode", "Licensing", "Sync",
            "Security", "System", "Update", "Backup"
    );

    @FXML private StackPane notificationHeaderIcon,allSummaryIcon,unreadSummaryIcon,actionSummaryIcon,detailIcon;
    @FXML private TextField txtNotificationSearch;
    @FXML private ListView<CategoryOption> listNotificationCategories;
    @FXML private ListView<NotificationItem> listNotifications;
    @FXML private Label lblNotificationCount,lblAllCount,lblUnreadCount,lblActionCount,lblDetailTitle,lblDetailMessage,lblDetailMeta,lblDetailReference;
    @FXML private Button btnOpenRecord,btnToggleRead,btnDeleteNotification,btnMarkAllRead,btnRefresh,btnClearHistory,btnClose,btnBottomClose;
    private List<NotificationItem> all = List.of();
    private long refreshSerial;
    private boolean updatingSummary;

    private static volatile String cachedPattern = "";
    private static volatile java.time.ZoneId cachedZone = null;
    private static volatile DateTimeFormatter cachedRowFormatter = null;
    private static volatile DateTimeFormatter cachedDetailFormatter = null;

    private record CategoryOption(String name,long count) { @Override public String toString(){return name;} }

    @FXML public void initialize() {
        installIcons();
        listNotificationCategories.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{
            if (!updatingSummary) applyFilter();
        });
        listNotificationCategories.setCellFactory(v -> new CategoryCell());
        txtNotificationSearch.textProperty().addListener((o,a,b)->applyFilter());
        listNotifications.setCellFactory(v -> new Cell());
        listNotifications.getSelectionModel().selectedItemProperty().addListener((o,a,b)->showDetail(b));
        listNotifications.setOnMouseClicked(e -> { if(e.getClickCount()==2) openRecord(); });
        refresh();
    }

    private void installIcons(){
        if(notificationHeaderIcon!=null)notificationHeaderIcon.getChildren().setAll(IconFactory.compactIcon("notification",22));
        if(allSummaryIcon!=null)allSummaryIcon.getChildren().setAll(IconFactory.compactIcon("inbox",20));
        if(unreadSummaryIcon!=null)unreadSummaryIcon.getChildren().setAll(IconFactory.compactIcon("notification",20));
        if(actionSummaryIcon!=null)actionSummaryIcon.getChildren().setAll(IconFactory.compactIcon("warning",20));
        if(detailIcon!=null)detailIcon.getChildren().setAll(IconFactory.compactIcon("notification",22));
        if(lblAllCount!=null)lblAllCount.getProperties().put("erp.value.icon.skip",true);
        if(lblUnreadCount!=null)lblUnreadCount.getProperties().put("erp.value.icon.skip",true);
        if(lblActionCount!=null)lblActionCount.getProperties().put("erp.value.icon.skip",true);
        graphic(btnMarkAllRead,"complete"); graphic(btnRefresh,"refresh"); graphic(btnClearHistory,"delete"); graphic(btnOpenRecord,"view"); graphic(btnToggleRead,"complete"); graphic(btnDeleteNotification,"delete"); graphic(btnClose,"cancel"); graphic(btnBottomClose,"cancel");
    }
    private void graphic(Button button,String semantic){if(button==null)return;button.setGraphic(IconFactory.compactIcon(semantic,14));button.getProperties().put("erp-icon-preserve",true);}

    @Override public void onScreenShown(boolean reused){ if(reused&&ScreenRefreshPolicy.shouldRefresh("notification-center",ScreenRefreshPolicy.Mode.WHEN_STALE,java.time.Duration.ofSeconds(30)))refresh(); }

    @FXML private void refresh() {
        long serial=++refreshSerial;
        lblNotificationCount.setText("Loading…");
        if(btnRefresh!=null)btnRefresh.setDisable(true);
        CompletableFuture.supplyAsync(() -> NotificationService.findRecent(500)).whenComplete((items,error) -> Platform.runLater(() -> {
            if(serial!=refreshSerial)return;
            if(btnRefresh!=null)btnRefresh.setDisable(false);
            all = error==null && items!=null ? List.copyOf(items) : List.of();
            if(error==null)ScreenRefreshPolicy.markRefreshed("notification-center");
            updateSummary(); applyFilter();
        }));
    }

    @FXML private void markAllRead(){ NotificationService.markAllRead(); refresh(); }
    @FXML private void clearHistory(){
        if(all.isEmpty()) return;
        if(!AppDialogService.confirm(listNotifications,"Clear My Notification History?","Remove all notifications from your history?","This clears notification history only for your signed-in user. Other users are not affected.")) return;
        NotificationService.clear(); org.example.util.AppDialogService.success(listNotifications,"Notifications cleared","Your notification history was cleared successfully."); refresh();
    }
    @FXML private void toggleRead(){ NotificationItem item=selected(); if(item==null)return; if(item.read())NotificationService.markUnread(item.id());else NotificationService.markRead(item.id()); org.example.util.AppDialogService.success(listNotifications,item.read()?"Marked unread":"Marked read","Notification status was updated successfully."); refresh(); }
    @FXML private void deleteSelected(){
        NotificationItem item=selected();
        if(item==null)return;
        boolean confirmed = AppDialogService.destructive(listNotifications, "Dismiss Notification", "Dismiss notification '" + item.title() + "'?", "This notification will be permanently removed from your history.", "Dismiss");
        if (!confirmed) return;
        NotificationService.delete(item.id());
        org.example.util.AppDialogService.success(listNotifications,"Notification dismissed","Notification was dismissed from your history.");
        refresh();
    }

    @FXML private void openRecord() {
        NotificationItem item = selected(); if(item==null)return;
        NotificationService.markRead(item.id());
        if (isDiagnosticAlert(item)) {
            Node owner = btnOpenRecord != null ? btnOpenRecord : listNotifications;
            javafx.stage.Window win = owner != null && owner.getScene() != null ? owner.getScene().getWindow() : null;
            javafx.stage.Window parentWin = (win instanceof javafx.stage.Stage st && st.getOwner() != null) ? st.getOwner() : win;
            Node targetOwner = (parentWin != null && parentWin.getScene() != null) ? parentWin.getScene().getRoot() : owner;
            closeDialogIfPresent();
            org.example.util.SystemDiagnosticsDialog.show(targetOwner);
            return;
        }
        String ref=safe(item.referenceNo()).trim();
        boolean legacyPayment="PAYMENT".equalsIgnoreCase(safe(item.moduleKey()))||"PAYMENTS".equalsIgnoreCase(safe(item.moduleKey()))||safe(item.targetFxml()).endsWith("/PaymentHistory.fxml");
        if(item.recordId()!=null&&!legacyPayment){
            openResolved(item,new ResolvedLink(true,item.moduleKey(),item.recordId(),ref,item.targetFxml()));
            return;
        }
        if(ref.isBlank()){
            lblDetailReference.setText("This notification is informational and has no exact ERP record reference.");
            return;
        }
        btnOpenRecord.setDisable(true);btnOpenRecord.setText("Resolving…");
        CompletableFuture.supplyAsync(() -> NotificationService.resolveExact(item)).whenComplete((resolved,error)->Platform.runLater(()->{
            btnOpenRecord.setText(actionButtonText(item));btnOpenRecord.setDisable(false);
            if(error!=null||resolved==null||!resolved.found()||resolved.recordId()==null){
                lblDetailReference.setText("The exact linked record could not be resolved. It may have been deleted or you may no longer have permission to open it.");
                return;
            }
            openResolved(item,resolved);
        }));
    }

    private void openResolved(NotificationItem item,ResolvedLink resolved){
        String target=!safe(resolved.targetFxml()).isBlank()?resolved.targetFxml():item.targetFxml();
        String key=!safe(resolved.moduleKey()).isBlank()?resolved.moduleKey():item.moduleKey();
        String ref=!safe(resolved.reference()).isBlank()?resolved.reference():item.referenceNo();
        if(safe(target).isBlank()){lblDetailReference.setText("The linked record exists but no application destination is available for it.");return;}
        closeDialogIfPresent();
        DeepLinkRouter.open(target,key,resolved.recordId(),ref,safe(item.actionCode()).isBlank()?"VIEW":item.actionCode(),"Notification #"+item.id());
    }

    @FXML private void closeDialog() {
        closeDialogIfPresent();
    }

    private void closeDialogIfPresent() {
        Node anchor = btnClose != null ? btnClose : (btnOpenRecord != null ? btnOpenRecord : listNotifications);
        if (anchor != null && anchor.getScene() != null && anchor.getScene().getWindow() != null) {
            javafx.stage.Window window = anchor.getScene().getWindow();
            if (window instanceof javafx.stage.Stage stage && stage.getOwner() != null) {
                stage.close();
            }
        }
    }

    private void updateSummary() {
        updatingSummary = true;
        try {
            long unread = 0;
            long actionable = 0;
            Map<String, Long> categoryCounts = new HashMap<>();
            for (NotificationItem n : all) {
                if (!n.read()) unread++;
                if (actionable(n)) actionable++;
                String cat = displayCategory(n.category());
                categoryCounts.put(cat, categoryCounts.getOrDefault(cat, 0L) + 1);
            }
            lblAllCount.setText(String.valueOf(all.size()));
            lblUnreadCount.setText(String.valueOf(unread));
            lblActionCount.setText(String.valueOf(actionable));

            String selected = Optional.ofNullable(listNotificationCategories.getSelectionModel().getSelectedItem())
                    .map(CategoryOption::name).orElse(ALL);

            LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
            for (String name : BASE_FILTERS) {
                long c = switch (name) {
                    case ALL -> (long) all.size();
                    case "Unread" -> unread;
                    case "Action Needed" -> actionable;
                    default -> categoryCounts.getOrDefault(name, 0L);
                };
                counts.put(name, c);
            }
            for (Map.Entry<String, Long> e : categoryCounts.entrySet()) {
                counts.putIfAbsent(e.getKey(), e.getValue());
            }

            List<CategoryOption> options = counts.entrySet().stream()
                    .filter(e -> BASE_FILTERS.contains(e.getKey()) || e.getValue() > 0)
                    .map(e -> new CategoryOption(e.getKey(), e.getValue()))
                    .toList();

            listNotificationCategories.getItems().setAll(options);
            listNotificationCategories.getSelectionModel().select(
                    options.stream().filter(x -> x.name().equals(selected)).findFirst()
                            .orElse(options.isEmpty() ? null : options.getFirst())
            );
        } finally {
            updatingSummary = false;
        }
    }

    private long countFor(String mode){if(ALL.equals(mode))return all.size();if("Unread".equals(mode))return all.stream().filter(n->!n.read()).count();if("Action Needed".equals(mode))return all.stream().filter(this::actionable).count();return all.stream().filter(n->displayCategory(n.category()).equalsIgnoreCase(mode)).count();}

    private void applyFilter() {
        String q=safe(txtNotificationSearch.getText()).trim().toLowerCase(Locale.ROOT);
        String mode=Optional.ofNullable(listNotificationCategories.getSelectionModel().getSelectedItem()).map(CategoryOption::name).orElse(ALL);
        List<NotificationItem> visible=all.stream().filter(n->matchesMode(n,mode)).filter(n->{
            if(q.isBlank())return true;
            String hay=(safe(n.title())+" "+safe(n.message())+" "+safe(n.referenceNo())+" "+safe(n.category())+" "+safe(n.moduleKey())).toLowerCase(Locale.ROOT);
            return hay.contains(q);
        }).toList();
        NotificationItem selected=listNotifications.getSelectionModel().getSelectedItem();
        listNotifications.getItems().setAll(visible);
        lblNotificationCount.setText(visible.size()+" shown  •  "+all.stream().filter(n->!n.read()).count()+" unread");
        if(selected!=null&&visible.contains(selected))listNotifications.getSelectionModel().select(selected);
        else if(!visible.isEmpty())listNotifications.getSelectionModel().selectFirst();
        else showDetail(null);
    }

    private boolean matchesMode(NotificationItem n,String mode){
        if(ALL.equals(mode))return true;
        if("Unread".equals(mode))return !n.read();
        if("Action Needed".equals(mode))return actionable(n);
        return displayCategory(n.category()).equalsIgnoreCase(mode);
    }

    private boolean actionable(NotificationItem n){
        if(n==null)return false;
        if("Approval".equalsIgnoreCase(displayCategory(n.category())))return true;
        String action=safe(n.actionCode()).trim().toUpperCase(Locale.ROOT);
        return Set.of("APPROVE","REVIEW","RESOLVE","PAY","PAYMENT","RECONCILE","COMPLETE","EXECUTE","CHECK","INSTALL").contains(action);
    }
    private boolean canOpen(NotificationItem n){return n!=null&&(n.recordId()!=null||!safe(n.referenceNo()).isBlank());}
    private NotificationItem selected(){return listNotifications.getSelectionModel().getSelectedItem();}

    private boolean isDiagnosticAlert(NotificationItem item) {
        if (item == null) return false;
        String title = safe(item.title()).toLowerCase(Locale.ROOT);
        String cat = safe(item.category()).toLowerCase(Locale.ROOT);
        String msg = safe(item.message()).toLowerCase(Locale.ROOT);
        return title.contains("diagnostic") || title.contains("system error") || cat.contains("diagnostic")
                || (cat.contains("system") && (msg.contains("scanner") || msg.contains("diagnostic") || msg.contains("error")));
    }

    private void showDetail(NotificationItem n) {
        boolean has=n!=null;
        boolean diagnostic = isDiagnosticAlert(n);
        boolean canOpen = has && (diagnostic || canOpen(n));
        btnOpenRecord.setDisable(!has || !canOpen);
        btnToggleRead.setDisable(!has);
        btnDeleteNotification.setDisable(!has);
        if(!has){
            lblDetailTitle.setText("Select a notification");
            lblDetailMessage.setText("Choose an item from the list to see its details and available actions.");
            lblDetailMessage.setVisible(true);
            lblDetailMessage.setManaged(true);
            lblDetailMeta.setText("");
            lblDetailReference.setText("");
            btnOpenRecord.setText("Open Record");
            if(detailIcon!=null)detailIcon.getChildren().setAll(IconFactory.compactIcon("notification",22));
            return;
        }
        String category=displayCategory(n.category());
        lblDetailTitle.setText(displayTitle(n));
        String detailMessage = displayMessage(n);
        lblDetailMessage.setText(detailMessage);
        lblDetailMessage.setVisible(!detailMessage.isBlank());
        lblDetailMessage.setManaged(!detailMessage.isBlank());
        lblDetailMeta.setGraphic(IconFactory.compactIcon(diagnostic ? "warning" : categorySemantic(category), 13));
        lblDetailMeta.setText(category+"  •  "+safe(n.severity())+"  •  "+whenText(n.createdAt())+(n.read()?"  •  Read":"  •  Unread"));
        String ref=safe(n.referenceNo());
        String module=safe(n.moduleKey());
        lblDetailReference.setGraphic(IconFactory.compactIcon(diagnostic ? "warning" : "link", 13));
        if (diagnostic) {
            lblDetailReference.setText("System diagnostic telemetry available. Click Inspect Diagnostics below.");
        } else {
            lblDetailReference.setText(ref.isBlank() ? "No record reference" : ("Reference: " + ref + (module.isBlank() ? "" : "  •  " + module)));
        }
        btnToggleRead.setText(n.read()?"Mark Unread":"Mark Read");
        btnOpenRecord.setText(actionButtonText(n));
        graphic(btnOpenRecord, diagnostic ? "validate" : ("APPROVE".equalsIgnoreCase(safe(n.actionCode())) ? "complete" : "view"));
        if(detailIcon!=null)detailIcon.getChildren().setAll(IconFactory.compactIcon(diagnostic ? "warning" : categorySemantic(category), 22));
    }

    private String actionButtonText(NotificationItem n){
        if (isDiagnosticAlert(n)) return "Inspect Diagnostics";
        if (!canOpen(n)) return "No Linked Record";
        return "APPROVE".equalsIgnoreCase(safe(n.actionCode())) ? "Review Approval" : "Open Record";
    }
    private static DateTimeFormatter rowFormatter() {
        String pattern = BusinessClock.datePattern();
        java.time.ZoneId zone = BusinessClock.zone();
        if (cachedRowFormatter == null || !pattern.equals(cachedPattern) || !Objects.equals(zone, cachedZone)) {
            cachedPattern = pattern;
            cachedZone = zone;
            cachedRowFormatter = DateTimeFormatter.ofPattern(pattern + "  •  hh:mm a").withZone(zone);
            cachedDetailFormatter = DateTimeFormatter.ofPattern(pattern + " • hh:mm a").withZone(zone);
        }
        return cachedRowFormatter;
    }

    private static DateTimeFormatter detailFormatter() {
        rowFormatter();
        return cachedDetailFormatter;
    }

    private String whenText(long epoch){return detailFormatter().format(Instant.ofEpochMilli(epoch));}
    private String rowTimestamp(long epoch){return rowFormatter().format(Instant.ofEpochMilli(epoch));}
    private boolean genericTitle(NotificationItem n){String t=safe(n==null?null:n.title()).trim();return t.isBlank()||"Notification".equalsIgnoreCase(t);}
    private String displayTitle(NotificationItem n){if(n==null)return"";return genericTitle(n)?safe(n.message()).trim():safe(n.title()).trim();}
    private String displayMessage(NotificationItem n){if(n==null)return"";return genericTitle(n)?"":safe(n.message()).trim();}
    private String displayCategory(String c){if(c==null||c.isBlank())return"System";String value=c.trim().replace('_',' ').toLowerCase(Locale.ROOT);return Character.toUpperCase(value.charAt(0))+value.substring(1);}
    private String safe(String s){return s==null?"":s;}
    private String categorySemantic(String item){
        String c = safe(item).toLowerCase(Locale.ROOT);
        if (c.contains("approval")) return "complete";
        if (c.contains("sale")) return "sale";
        if (c.contains("purchase")) return "purchase";
        if (c.contains("quotation")) return "quotation";
        if (c.contains("return")) return "return";
        if (c.contains("payment")) return "payment";
        if (c.contains("inventory")) return "item";
        if (c.contains("reminder")) return "reminder";
        if (c.contains("communication")) return "email";
        if (c.contains("gst")) return "gstin";
        if (c.contains("eway")) return "delivery";
        if (c.contains("tally")) return "sync";
        if (c.contains("ledger")) return "ledger";
        if (c.contains("crm")) return "customer";
        if (c.contains("barcode")) return "barcode";
        if (c.contains("licensing")) return "security";
        if (c.contains("sync")) return "sync";
        if (c.contains("security")) return "security";
        if (c.contains("backup")) return "backup";
        if (c.contains("update")) return "refresh";
        if (c.contains("unread")) return "notification";
        if (c.contains("action")) return "warning";
        return "notification";
    }
    private String slug(String value){String s=safe(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");return s.isBlank()?"system":s;}

    private final class CategoryCell extends ListCell<CategoryOption>{
        private final Label name = new Label();
        private final Label count = new Label();
        private final StackPane iconHolder = new StackPane();
        private final HBox box = new HBox(8, iconHolder, name, count);

        CategoryCell() {
            name.getStyleClass().add("dse-notification-v3-category-name");
            HBox.setHgrow(name, Priority.ALWAYS);
            count.getStyleClass().add("dse-notification-v3-category-count");
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("dse-notification-v3-category-cell");
        }

        @Override protected void updateItem(CategoryOption option, boolean empty){
            super.updateItem(option, empty);
            if(empty || option == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            iconHolder.getChildren().setAll(IconFactory.compactIcon(categorySemantic(option.name()), 14));
            name.setText(option.name());
            count.setText(String.valueOf(option.count()));
            setText(null);
            setGraphic(box);
        }
    }

    private final class Cell extends ListCell<NotificationItem> {
        private final StackPane iconWell = new StackPane();
        private final Label title = new Label();
        private final Label categoryBadge = new Label();
        private final HBox state = new HBox(5);
        private final Label lblNew = new Label("NEW");
        private final Label lblAction = new Label("ACTION");
        private final Label message = new Label();
        private final Label meta = new Label();
        private final Label time = new Label();
        private final HBox top = new HBox(8);
        private final HBox footer = new HBox(10);
        private final VBox text = new VBox(6);
        private final HBox row = new HBox(12);

        Cell() {
            iconWell.setMinSize(42, 42);
            iconWell.setPrefSize(42, 42);
            iconWell.setMaxSize(42, 42);

            title.setWrapText(true);
            title.setMinWidth(120);
            title.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(title, Priority.ALWAYS);
            title.getStyleClass().add("dse-notification-v3-row-title");

            categoryBadge.getStyleClass().add("dse-notification-v3-badge");

            state.setAlignment(Pos.CENTER_RIGHT);
            lblNew.getStyleClass().add("dse-notification-v3-new");
            lblAction.getStyleClass().add("dse-notification-v3-action-badge");

            top.setAlignment(Pos.CENTER_LEFT);
            top.getChildren().setAll(title, categoryBadge, state);

            message.setWrapText(true);
            message.setMinWidth(120);
            message.setMaxWidth(Double.MAX_VALUE);
            message.getStyleClass().add("dse-notification-v3-row-message");

            meta.setWrapText(true);
            meta.setMinWidth(100);
            HBox.setHgrow(meta, Priority.ALWAYS);
            meta.getStyleClass().add("dse-notification-v3-row-meta");

            time.setAlignment(Pos.CENTER_RIGHT);
            time.getStyleClass().add("dse-notification-v3-row-time");

            footer.setAlignment(Pos.CENTER_LEFT);
            footer.getChildren().setAll(meta, time);

            text.getChildren().setAll(top, message, footer);
            HBox.setHgrow(text, Priority.ALWAYS);
            text.maxWidthProperty().bind(listNotifications.widthProperty().subtract(76));

            row.setAlignment(Pos.TOP_LEFT);
            row.getStyleClass().add("dse-notification-v3-row");
            row.getChildren().setAll(iconWell, text);
        }

        @Override protected void updateItem(NotificationItem n, boolean empty){
            super.updateItem(n, empty);
            if (empty || n == null) {
                setText(null);
                setGraphic(null);
                getStyleClass().removeAll("dse-notification-v3-unread-cell", "dse-notification-v3-action-cell");
                return;
            }
            String category = displayCategory(n.category());
            String catSlug = slug(category);

            iconWell.getStyleClass().removeIf(s -> s != null && s.startsWith("dse-notification-v3-row-icon-"));
            iconWell.getStyleClass().removeAll("dse-notification-v3-row-icon");
            iconWell.getStyleClass().addAll("dse-notification-v3-row-icon", "dse-notification-v3-row-icon-" + catSlug);
            iconWell.getChildren().setAll(IconFactory.compactIcon(categorySemantic(category), 19));

            title.setText(displayTitle(n));

            categoryBadge.setText(category);
            categoryBadge.getStyleClass().removeIf(s -> s != null && s.startsWith("dse-notification-v3-badge-"));
            categoryBadge.getStyleClass().removeAll("dse-notification-v3-badge");
            categoryBadge.getStyleClass().addAll("dse-notification-v3-badge", "dse-notification-v3-badge-" + catSlug);

            state.getChildren().clear();
            if (!n.read()) state.getChildren().add(lblNew);
            if (actionable(n)) state.getChildren().add(lblAction);

            String msg = displayMessage(n);
            if (msg.isBlank()) {
                message.setVisible(false);
                message.setManaged(false);
            } else {
                message.setVisible(true);
                message.setManaged(true);
                message.setText(msg);
            }

            meta.setText(category + (safe(n.referenceNo()).isBlank() ? "" : "  •  " + safe(n.referenceNo())));
            time.setText(rowTimestamp(n.createdAt()));

            getStyleClass().removeAll("dse-notification-v3-unread-cell", "dse-notification-v3-action-cell");
            if (!n.read()) getStyleClass().add("dse-notification-v3-unread-cell");
            if (actionable(n)) getStyleClass().add("dse-notification-v3-action-cell");

            setText(null);
            setGraphic(row);
        }
    }
}
