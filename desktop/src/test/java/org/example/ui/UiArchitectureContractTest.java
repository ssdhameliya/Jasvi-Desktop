package org.example.ui;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UiArchitectureContractTest {
    private static final Path FXML_ROOT = Path.of("src/main/resources/fxml");

    @Test
    void kpiSectionsHaveOneRuntimeWidthAuthority() throws Exception {
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(FXML_ROOT)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".fxml")).toList()) {
                var document = parse(path);
                NodeList all = document.getElementsByTagName("*");
                for (int i = 0; i < all.getLength(); i++) {
                    if (!(all.item(i) instanceof Element element)) continue;
                    String styles = element.getAttribute("styleClass");
                    if (!containsStyle(styles, "erp-kpi-section")) continue;
                    if (containsStyle(styles, "erp-kpi-bounded")) {
                        violations.add(path + ": bounded KPI class is forbidden");
                    }
                    NodeList children = element.getChildNodes();
                    for (int c = 0; c < children.getLength(); c++) {
                        Node child = children.item(c);
                        if (child.getNodeType() == Node.ELEMENT_NODE && "columnConstraints".equals(child.getNodeName())) {
                            violations.add(path + ": KPI section contains static columnConstraints");
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    void sidebarButtonsNeverCarryActionPrimaryStyling() throws Exception {
        Path dashboard = FXML_ROOT.resolve("pages/Dashboard.fxml");
        var document = parse(dashboard);
        Element sidebar = findByStyle(document.getDocumentElement(), "erp-sidebar");
        assertNotNull(sidebar, "Dashboard sidebar root is missing");

        List<String> violations = new ArrayList<>();
        NodeList buttons = sidebar.getElementsByTagName("Button");
        for (int i = 0; i < buttons.getLength(); i++) {
            Element button = (Element) buttons.item(i);
            String id = button.getAttributeNS("http://javafx.com/fxml/1", "id");
            String styles = button.getAttribute("styleClass");
            for (String forbidden : List.of("approved-primary-button", "primary-button", "erp-button-primary", "erp-action-button")) {
                if (containsStyle(styles, forbidden)) violations.add(id + " carries forbidden sidebar style " + forbidden);
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    void dialogsAreOwnedAndManagedByPlatformStandards() throws Exception {
        Path controllerRoot = Path.of("src/main/java/org/example/controller");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(controllerRoot)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(path);
                if (content.contains("new Dialog<") && !content.contains("new OwnedDialog<")) {
                    violations.add(path + ": forbidden unmanaged raw 'new Dialog<' found (must use OwnedDialog or AppDialogService)");
                }
                if (content.contains("new Stage(") || content.contains("new Stage ()")) {
                    violations.add(path + ": forbidden raw 'new Stage(' found in business controller");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    void everyFxmlOnActionMatchesAValidControllerMethod() throws Exception {
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(FXML_ROOT)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".fxml")).toList()) {
                var document = parse(path);
                Element root = document.getDocumentElement();
                String controllerName = root.getAttributeNS("http://javafx.com/fxml/1", "controller");
                if (controllerName == null || controllerName.isBlank()) {
                    controllerName = root.getAttribute("fx:controller");
                }
                if (controllerName == null || controllerName.isBlank()) continue;
                Class<?> controllerClass;
                try {
                    controllerClass = Class.forName(controllerName);
                } catch (ClassNotFoundException e) {
                    continue;
                }
                NodeList all = document.getElementsByTagName("*");
                for (int i = 0; i < all.getLength(); i++) {
                    if (!(all.item(i) instanceof Element element)) continue;
                    String onAction = element.getAttribute("onAction");
                    if (onAction == null || !onAction.startsWith("#")) continue;
                    String methodName = onAction.substring(1).trim();
                    if (methodName.isEmpty()) continue;
                    boolean found = false;
                    Class<?> current = controllerClass;
                    while (current != null && current != Object.class) {
                        for (var method : current.getDeclaredMethods()) {
                            if (method.getName().equals(methodName)) {
                                if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                                        || method.isAnnotationPresent(javafx.fxml.FXML.class)) {
                                    found = true;
                                    break;
                                }
                            }
                        }
                        if (found) break;
                        current = current.getSuperclass();
                    }
                    if (!found) {
                        violations.add(path + " -> " + controllerName + " :: onAction='#" + methodName + "' is missing @FXML annotation or method declaration");
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Unresolved FXML onAction handlers found:\n" + String.join("\n", violations));
    }

    @Test
    void profileScreenLifecycleGuaranteesIsolatedTasks() throws Exception {
        Path controllerPath = Path.of("src/main/java/org/example/controller/ProfileController.java");
        if (!Files.exists(controllerPath)) {
            controllerPath = Path.of("desktop/src/main/java/org/example/controller/ProfileController.java");
        }
        String source = Files.readString(controllerPath);
        assertTrue(source.contains("implements ScreenLifecycle"), "ProfileController must implement ScreenLifecycle");
        assertTrue(source.contains("onScreenShown"), "ProfileController must implement onScreenShown");
        assertTrue(source.contains("onScreenHidden"), "ProfileController must implement onScreenHidden");
        assertTrue(source.contains("System.identityHashCode(this)"),
                "ProfileController must use an instance-isolated task key to prevent sibling screen cancellations");
    }

    @Test
    void executiveBriefingMultiCadenceAndRecipientChipBoxContract() throws Exception {
        Path fxmlPath = Path.of("src/main/resources/fxml/pages/settings/ModulesSettingsPanel.fxml");
        assertTrue(Files.exists(fxmlPath), "ModulesSettingsPanel.fxml must exist");
        String fxml = Files.readString(fxmlPath);

        assertTrue(fxml.contains("fx:id=\"vboxBriefingPhone\""), "ModulesSettingsPanel must declare vboxBriefingPhone");
        assertTrue(fxml.contains("fx:id=\"vboxBriefingEmail\""), "ModulesSettingsPanel must declare vboxBriefingEmail");
        assertTrue(fxml.contains("fx:id=\"chkCadenceDaily\"") && fxml.contains("ios-toggle-switch"),
                "ModulesSettingsPanel must declare chkCadenceDaily with ios-toggle-switch styleClass");
        assertTrue(fxml.contains("fx:id=\"chkCadenceWeekly\""), "ModulesSettingsPanel must declare chkCadenceWeekly");
        assertTrue(fxml.contains("fx:id=\"chkCadenceMonthly\""), "ModulesSettingsPanel must declare chkCadenceMonthly");
        assertTrue(fxml.contains("fx:id=\"chkCadenceQuarterly\""), "ModulesSettingsPanel must declare chkCadenceQuarterly");
        assertTrue(fxml.contains("fx:id=\"chkCadenceHalfYearly\""), "ModulesSettingsPanel must declare chkCadenceHalfYearly");
        assertTrue(fxml.contains("fx:id=\"chkCadenceYearly\""), "ModulesSettingsPanel must declare chkCadenceYearly");
        assertTrue(fxml.contains("fx:id=\"cmbBriefingPeriodMode\""), "ModulesSettingsPanel must declare cmbBriefingPeriodMode");
        assertTrue(fxml.contains("fx:id=\"cmbBriefingCustomStart\""), "ModulesSettingsPanel must declare cmbBriefingCustomStart");
        assertTrue(fxml.contains("fx:id=\"txtBriefingPortalUrl\""), "ModulesSettingsPanel must declare txtBriefingPortalUrl");

        Path controllerPath = Path.of("src/main/java/org/example/controller/SettingsController.java");
        assertTrue(Files.exists(controllerPath), "SettingsController.java must exist");
        String controllerSource = Files.readString(controllerPath);

        assertTrue(controllerSource.contains("RecipientChipBox.attach(vboxBriefingPhone, txtBriefingPhone, org.example.util.RecipientChipBox.ChannelType.WHATSAPP)"),
                "SettingsController must attach RecipientChipBox for mobile recipients");
        assertTrue(controllerSource.contains("RecipientChipBox.attach(vboxBriefingEmail, txtBriefingEmails, org.example.util.RecipientChipBox.ChannelType.EMAIL)"),
                "SettingsController must attach RecipientChipBox for email recipients");
        assertTrue(controllerSource.contains("triggerExecutiveBriefing(true, targetCadence)"),
                "SettingsController must dispatch test briefings with target cadence");
    }

    private static org.w3c.dom.Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static Element findByStyle(Element root, String style) {
        if (containsStyle(root.getAttribute("styleClass"), style)) return root;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                Element found = findByStyle(child, style);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsStyle(String styleClass, String wanted) {
        if (styleClass == null || styleClass.isBlank()) return false;
        for (String value : styleClass.split(",")) if (wanted.equals(value.trim())) return true;
        return false;
    }
}
