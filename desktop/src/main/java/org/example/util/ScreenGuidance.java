package org.example.util;

import java.util.List;

/**
 * Immutable operational guidance model for Jasvi ERP screens and settings panels.
 * Holds functional workflows, green-highlighted best practices, and red-highlighted alerts.
 */
public record ScreenGuidance(
    String screenKey,
    String screenTitle,
    String iconName,
    String overviewSummary,
    List<String> coreWorkflows,
    List<String> importantPoints,  // Rendered in BOLD GREEN
    List<String> criticalAlerts,   // Rendered in BOLD RED
    List<String> keyboardShortcuts // Quick key bindings & tips
) {
    public ScreenGuidance {
        coreWorkflows = coreWorkflows != null ? List.copyOf(coreWorkflows) : List.of();
        importantPoints = importantPoints != null ? List.copyOf(importantPoints) : List.of();
        criticalAlerts = criticalAlerts != null ? List.copyOf(criticalAlerts) : List.of();
        keyboardShortcuts = keyboardShortcuts != null ? List.copyOf(keyboardShortcuts) : List.of();
    }
}
