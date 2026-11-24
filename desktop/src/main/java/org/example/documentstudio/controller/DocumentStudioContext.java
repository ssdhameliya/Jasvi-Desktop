package org.example.documentstudio.controller;

/** Shared navigation context for Document Studio landing modes. */
public final class DocumentStudioContext {
    public enum Mode { PDF, EXCEL }

    private static volatile Mode landingMode = Mode.EXCEL;

    private DocumentStudioContext() {}

    /** Selects which Document Studio library should be shown when the landing page opens. */
    public static void selectMode(Mode mode) { landingMode = mode == null ? Mode.EXCEL : mode; }

    /** Returns the requested landing mode without clearing it, so child pages can return to it. */
    public static Mode currentMode() { return landingMode; }
}
