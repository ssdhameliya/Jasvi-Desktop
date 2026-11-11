package org.example.ai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.glaforge.antigravity.Agent;
import io.github.glaforge.antigravity.AgentConfig;
import io.github.glaforge.antigravity.AgentResponse;
import javafx.application.Platform;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import org.example.api.ApiRuntime;
import org.example.config.ConfigManager;
import org.example.theme.ThemeManager;
import org.example.util.SystemDiagnosticsDialog.DiagnosticRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Enterprise Antigravity AI Agent & Defect Diagnostic Service.
 *
 * <p>Integrates the Google Antigravity Java SDK ecosystem directly into the
 * DSE ERP JavaFX desktop client. Handles non-blocking, asynchronous agent
 * interactions, automatic defect severity categorization, and dynamic glassmorphic
 * UI row highlights.</p>
 */
public final class ErpAgentService {

    private static final Logger log = LoggerFactory.getLogger(ErpAgentService.class);
    private static volatile ErpAgentService instance;

    public enum DefectSeverity {
        NONE,
        INFO,
        WARNING,
        CRITICAL
    }

    public record DefectDiagnosis(
            DefectSeverity severity,
            String summary,
            String rootCause,
            String recommendedFix,
            boolean isAiGenerated
    ) {}

    public enum CopilotMode {
        ONBOARDING_SUBSCRIPTION("Startup & Subscription Guide"),
        SCREEN_READER("In-App Screen Reader"),
        ROLE_ASSISTANT("Role-Based Copilot"),
        ERROR_TROUBLESHOOT("Self-Healing Diagnostics");

        private final String displayTitle;

        CopilotMode(String displayTitle) {
            this.displayTitle = displayTitle;
        }

        public String displayTitle() {
            return displayTitle;
        }
    }

    public record CopilotAction(
            String label,
            String actionCode,
            String semanticIcon
    ) {}

    public record CopilotResponse(
            String title,
            String summary,
            List<String> bulletInsights,
            List<CopilotAction> actions,
            boolean isAiGenerated
    ) {}

    public static final String PROMPT_ONBOARDING_SUBSCRIPTION = """
            You are the Jasvi ERP Startup & Subscription Advisor.
            Compare STANDARD (Single Workstation, Sales/Purchase/Inventory), ENTERPRISE (Multi-User LAN/Cloud,
            GST E-Invoicing, E-Way Bills, Bank Reconciliation, Bill-to-Tally), and CA_MULTI_FIRM (Multi-Company
            Ledger & Audit Suite). Guide the operator on generating a .jreq hardware request and activating a .jlic license.
            """;

    public static final String PROMPT_SCREEN_INSPECTION = """
            You are the Jasvi ERP Real-Time Screen Reader Assistant.
            Inspect the active screen telemetry (disabled controls, validation errors, empty required fields,
            and table selection) and explain clearly why controls are disabled or how to complete the workflow.
            """;

    public static final String PROMPT_ROLE_COPILOT = """
            You are the Jasvi ERP Role-Aware Enterprise Copilot.
            Tailor guidance to the operator's role (SUPER_ADMIN: tenant registry, licensing, backups;
            ADMIN: company profile, tax settings, permissions; USER/SALES/ACCOUNTS: invoicing, returns, bank import).
            """;

    public static final String PROMPT_SELF_HEALING_FIX = """
            You are the Jasvi ERP Self-Healing Diagnostic Engine.
            Identify the exact root cause of runtime errors and recommend immediate 1-click corrective actions.
            """;

    public record ChatTurn(String role, String text) {}

    private static final String GEMINI_REST_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";

    private Agent agent;
    private volatile boolean initialized = false;
    private volatile boolean aiAvailable = false;
    private final List<ChatTurn> conversationHistory = new CopyOnWriteArrayList<>();

    private ErpAgentService() {
        CompletableFuture.runAsync(this::initAgent);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    public static ErpAgentService getInstance() {
        if (instance == null) {
            synchronized (ErpAgentService.class) {
                if (instance == null) {
                    instance = new ErpAgentService();
                }
            }
        }
        return instance;
    }

    public String getConfiguredApiKey() {
        try {
            String fromConfig = ConfigManager.get("ai.gemini.apiKey", "");
            if (fromConfig != null && !fromConfig.isBlank()) {
                return fromConfig.trim();
            }
        } catch (Throwable ignored) {}
        String env = System.getenv("GEMINI_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        String prop = System.getProperty("gemini.api.key", "");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String dseEnv = System.getenv("DSE_GEMINI_API_KEY");
        return dseEnv != null ? dseEnv.trim() : "";
    }

    public boolean hasGeminiApiKey() {
        return !getConfiguredApiKey().isBlank();
    }

    public void saveApiKey(String apiKey) {
        String clean = apiKey == null ? "" : apiKey.trim();
        try {
            ConfigManager.set("ai.gemini.apiKey", clean);
        } catch (Throwable ignored) {
            System.setProperty("gemini.api.key", clean);
        }
    }

    public String getActiveEngineLabel() {
        if (hasGeminiApiKey()) return "Gemini 2.5 Flash (Online)";
        if (isAiAvailable()) return "Antigravity AI";
        return "Local ERP AI (Built-In)";
    }

    public void clearConversationHistory() {
        conversationHistory.clear();
    }

    private synchronized void initAgent() {
        if (initialized) return;
        try {
            String apiKey = getConfiguredApiKey();

            AgentConfig.Builder builder = AgentConfig.builder()
                    .instructions("""
                            You are the authoritative DSE ERP Enterprise Diagnostic & Defect Assistant.
                            You analyze technical Java stack traces, database exceptions, transaction lockups,
                            and business logic defects in ERP documents (Sales, Purchases, Quotations, Returns, Tax allocations).
                            Return concise, highly accurate, and actionable guidance without fluff.
                            """);

            if (apiKey != null && !apiKey.isBlank()) {
                builder.addEnvironmentVariable("GEMINI_API_KEY", apiKey);
            }

            AgentConfig config = builder.build();
            this.agent = new Agent(config);
            this.aiAvailable = true;
            log.info("ErpAgentService: Google Antigravity Agent initialized successfully.");
        } catch (Throwable t) {
            this.aiAvailable = false;
            log.warn("ErpAgentService: Antigravity agent harness not active or missing API key. Falling back to deterministic heuristic analyzer. ({})", t.getMessage());
        } finally {
            this.initialized = true;
        }
    }

    public boolean isAiAvailable() {
        return hasGeminiApiKey() || (aiAvailable && agent != null);
    }

    /**
     * Calls the Google Gemini 2.5 Flash HTTPS REST endpoint directly with multi-turn context.
     */
    private String callGeminiRestApi(String systemInstruction, String userPrompt) throws Exception {
        String apiKey = getConfiguredApiKey();
        if (apiKey.isBlank()) return "";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", systemInstruction))
        ));

        List<Map<String, Object>> contents = new ArrayList<>();
        int startIdx = Math.max(0, conversationHistory.size() - 6);
        for (int i = startIdx; i < conversationHistory.size(); i++) {
            ChatTurn turn = conversationHistory.get(i);
            contents.add(Map.of(
                    "role", "user".equals(turn.role()) ? "user" : "model",
                    "parts", List.of(Map.of("text", turn.text()))
            ));
        }
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userPrompt))
        ));
        body.put("contents", contents);
        body.put("generationConfig", Map.of(
                "temperature", 0.25,
                "maxOutputTokens", 700
        ));

        String jsonPayload = ApiRuntime.JSON.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(GEMINI_REST_URL + apiKey))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = ApiRuntime.HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null) {
            JsonNode root = ApiRuntime.JSON.readTree(resp.body());
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    return parts.get(0).path("text").asText("").trim();
                }
            }
        }
        return "";
    }

    /**
     * Asynchronously analyzes a diagnostic row or exception using the Antigravity Agent,
     * safely dispatching to background executor and never blocking the JavaFX thread.
     */
    public CompletableFuture<DefectDiagnosis> diagnoseDiagnosticRow(DiagnosticRow row) {
        if (row == null) {
            return CompletableFuture.completedFuture(new DefectDiagnosis(
                    DefectSeverity.NONE, "No event data", "N/A", "N/A", false
            ));
        }

        DefectSeverity severity = "ERROR".equalsIgnoreCase(row.level()) || "FATAL".equalsIgnoreCase(row.level())
                ? DefectSeverity.CRITICAL
                : "WARN".equalsIgnoreCase(row.level()) ? DefectSeverity.WARNING : DefectSeverity.INFO;

        return CompletableFuture.supplyAsync(() -> {
            if (!isAiAvailable()) {
                return buildDeterministicDiagnosis(row, severity);
            }

            String prompt = String.format("""
                    Analyze this enterprise ERP diagnostic event:
                    - Level: %s
                    - Source: %s
                    - Component: %s
                    - Event: %s
                    - User-Facing Title: %s
                    - Technical Detail: %s
                    - Stack Trace: %s

                    Provide:
                    1. Concise Root Cause (1-2 sentences)
                    2. Recommended Solution / Fix (1-2 bullet points)
                    """,
                    row.level(), row.source(), row.component(), row.event(),
                    row.friendlyTitle(), row.technicalDetail(), row.rawFailure()
            );

            try {
                AgentResponse response = agent.chat(prompt).get(15, TimeUnit.SECONDS);
                String text = response != null ? response.text() : "";
                String cause = extractSection(text, "Root Cause", row.friendlyExplanation());
                String fix = extractSection(text, "Recommended Solution", row.actionableGuidance());
                return new DefectDiagnosis(severity, row.friendlyTitle(), cause, fix, true);
            } catch (Throwable t) {
                log.warn("Agent chat timed out or failed, using heuristic fallback: {}", t.getMessage());
                return buildDeterministicDiagnosis(row, severity);
            }
        });
    }

    /**
     * Analyzes any arbitrary exception thrown during UI operations across any ERP screen.
     */
    public CompletableFuture<DefectDiagnosis> diagnoseException(String scope, String event, String detail, Throwable failure) {
        String failureText = failure == null ? "" : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        DiagnosticRow row = new DiagnosticRow(
                0, java.time.Instant.now().toString(), "Now", "ERROR", "DESKTOP",
                scope, event, "USER", scope + " Failure", detail, "Please retry or review inputs.", detail, failureText
        );
        return diagnoseDiagnosticRow(row);
    }

    /**
     * Contextual diagnosis for user-facing UI error alerts and dialog popups.
     */
    public CompletableFuture<DefectDiagnosis> diagnoseError(String title, String heading, String visibleMessage, String technicalDetail) {
        String event = heading != null && !heading.isBlank() ? heading : title;
        String fullDetail = (visibleMessage != null ? visibleMessage : "") + (technicalDetail != null && !technicalDetail.isBlank() ? " • " + technicalDetail : "");
        DiagnosticRow row = new DiagnosticRow(
                0, java.time.Instant.now().toString(), "Now", "ERROR", "DESKTOP",
                "Application", event, "USER", title != null ? title : "Operation Failed", fullDetail, "Review inputs or check settings.", fullDetail, fullDetail
        );
        return diagnoseDiagnosticRow(row);
    }

    private DefectDiagnosis buildDeterministicDiagnosis(DiagnosticRow row, DefectSeverity severity) {
        String detail = row.technicalDetail() != null ? row.technicalDetail() : "";
        String failure = row.rawFailure() != null ? row.rawFailure() : "";
        String combined = (detail + " " + failure).toLowerCase(java.util.Locale.ROOT);

        String cause = row.friendlyExplanation();
        String fix = row.actionableGuidance();

        if (combined.contains("password") || combined.contains("encrypted")) {
            cause = "The selected bank statement spreadsheet is password-protected/encrypted by your issuing bank.";
            fix = "Enter the statement password when prompted (common passwords: Date of Birth in DDMMYYYY, PAN in capital letters, or Account Number).";
        } else if (combined.contains("invalid date format") || (combined.contains("error rows") && combined.contains("bank statement")) || combined.contains("preflight errors")) {
            cause = "The bank statement contains transaction dates formatted with 2-digit years (e.g. '03/04/26') or bank divider rows.";
            fix = "The universal statement parser has been upgraded to automatically normalize 2-digit years and skip divider rows. Re-select the file to verify.";
        } else if (combined.contains("server resource not found") || combined.contains("illegalargumentexception: server resource")) {
            cause = "Backend Spring service route could not locate the requested enterprise resource or endpoint.";
            fix = "Verify server deployment version or check route mapping in Server Administration.";
        } else if (combined.contains("does not exist") && (combined.contains("column") || combined.contains("relation"))) {
            cause = "Database schema mismatch: a required column or table does not exist in PostgreSQL.";
            fix = "Run pending Flyway database migrations or apply the latest database upgrade package.";
        } else if (combined.contains("no proof is attached") || combined.contains("attachmentunavailableexception")) {
            cause = "A required payment receipt or proof document has not been uploaded to this voucher.";
            fix = "Attach the payment confirmation or receipt file before approving or submitting.";
        } else if (combined.contains("closedchannelexception")) {
            cause = "Backend server socket closed unexpectedly while handling the HTTP request.";
            fix = "Verify server port 58080 is active. The desktop client will automatically retry with exponential backoff.";
        } else if (combined.contains("connectexception") || combined.contains("connection refused")) {
            cause = "Desktop client could not establish TCP connection with Spring REST backend on localhost:58080.";
            fix = "Ensure the DSE ERP Spring server process is running and PostgreSQL port 55432 is healthy.";
        } else if (combined.contains("optimisticlockingfailureexception") || combined.contains("row_version")) {
            cause = "Concurrent update detected: another operator modified this document simultaneously.";
            fix = "Refresh the table view to load the latest revision before re-submitting your changes.";
        } else if (combined.contains("compatibility policy is invalid") || combined.contains("minimum supported desktop")) {
            cause = "Version policy mismatch: minimum supported desktop version is incompatible with current server.";
            fix = "Update the server package or adjust the minimum desktop compatibility policy in Master Settings.";
        } else if (combined.contains("hikaripool") || combined.contains("08003") || combined.contains("timed out after")) {
            cause = "PostgreSQL database connection pool exhausted or timed out under heavy load.";
            fix = "Check PostgreSQL service on port 55432 or adjust database max connection pool settings.";
        } else if (combined.contains("nullpointerexception")) {
            cause = "An unexpected null reference was encountered during document validation.";
            fix = "Review all required document lines and header fields before saving.";
        } else if (cause == null || cause.isBlank() || cause.startsWith("The ERP could not complete")) {
            if (!detail.isBlank()) {
                cause = detail;
                fix = "Review the failure details above or contact system administration.";
            }
        }

        return new DefectDiagnosis(severity, row.friendlyTitle(), cause, fix, false);
    }

    private String extractSection(String fullText, String header, String defaultVal) {
        if (fullText == null || fullText.isBlank()) return defaultVal;
        try {
            int idx = fullText.indexOf(header);
            if (idx != -1) {
                int start = fullText.indexOf(":", idx);
                if (start == -1) start = idx + header.length();
                else start += 1;
                int end = fullText.indexOf("\n\n", start);
                if (end == -1) end = fullText.length();
                String extracted = fullText.substring(start, end).trim();
                if (!extracted.isBlank()) return extracted;
            }
        } catch (Exception ignored) {}
        return defaultVal;
    }

    /**
     * Binds dynamic glassmorphic styling to any JavaFX TableView.
     * Highlights critical defects with translucent glass-red tint and warning anomalies
     * with translucent glass-amber tint without interfering with standard row selection.
     */
    public static <T> void installGlassDefectRowFactory(TableView<T> table, Function<T, DefectSeverity> severityFunction) {
        if (table == null || severityFunction == null) return;

        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setStyle("");
                    getStyleClass().removeAll("dse-glass-row-critical", "dse-glass-row-warning", "dse-glass-row-info");
                    return;
                }

                DefectSeverity severity = severityFunction.apply(item);
                boolean isDark = ThemeManager.isDark();

                getStyleClass().removeAll("dse-glass-row-critical", "dse-glass-row-warning", "dse-glass-row-info");

                if (severity == DefectSeverity.CRITICAL) {
                    getStyleClass().add("dse-glass-row-critical");
                    if (isDark) {
                        setStyle("-fx-background-color: rgba(239, 68, 68, 0.16); -fx-border-color: rgba(239, 68, 68, 0.50); -fx-border-width: 0 0 0 3.5;");
                    } else {
                        setStyle("-fx-background-color: rgba(254, 226, 226, 0.70); -fx-border-color: #ef4444; -fx-border-width: 0 0 0 3.5;");
                    }
                } else if (severity == DefectSeverity.WARNING) {
                    getStyleClass().add("dse-glass-row-warning");
                    if (isDark) {
                        setStyle("-fx-background-color: rgba(245, 158, 11, 0.14); -fx-border-color: rgba(245, 158, 11, 0.50); -fx-border-width: 0 0 0 3.5;");
                    } else {
                        setStyle("-fx-background-color: rgba(254, 243, 199, 0.70); -fx-border-color: #f59e0b; -fx-border-width: 0 0 0 3.5;");
                    }
                } else if (severity == DefectSeverity.INFO) {
                    getStyleClass().add("dse-glass-row-info");
                    if (isDark) {
                        setStyle("-fx-background-color: rgba(56, 189, 248, 0.08); -fx-border-color: rgba(56, 189, 248, 0.40); -fx-border-width: 0 0 0 3;");
                    } else {
                        setStyle("-fx-background-color: rgba(224, 242, 254, 0.60); -fx-border-color: #0284c7; -fx-border-width: 0 0 0 3;");
                    }
                } else {
                    setStyle("");
                }
            }
        });
    }

    /**
     * Asynchronously answers operator questions using live screen telemetry, role context,
     * and either the Google Gemini 2.5 Flash REST API, Antigravity Agent, or the built-in ERP knowledge base.
     */
    public CompletableFuture<CopilotResponse> askCopilot(
            CopilotMode mode,
            String userQuestion,
            ActiveViewContextService.ScreenContext context
    ) {
        CopilotMode effectiveMode = mode != null ? mode : CopilotMode.SCREEN_READER;
        String cleanQuestion = userQuestion != null ? userQuestion.trim() : "";

        return CompletableFuture.supplyAsync(() -> {
            CopilotResponse deterministic = buildDeterministicCopilotResponse(effectiveMode, cleanQuestion, context);
            String effectivePromptQuestion = cleanQuestion.isBlank()
                    ? "Inspect my current screen and provide helpful guidance."
                    : cleanQuestion;

            String systemInstruction = switch (effectiveMode) {
                case ONBOARDING_SUBSCRIPTION -> PROMPT_ONBOARDING_SUBSCRIPTION;
                case SCREEN_READER -> PROMPT_SCREEN_INSPECTION;
                case ROLE_ASSISTANT -> PROMPT_ROLE_COPILOT;
                case ERROR_TROUBLESHOOT -> PROMPT_SELF_HEALING_FIX;
            };

            String prompt = String.format("""
                    %s

                    Live Application Telemetry:
                    %s

                    Operator Question: %s

                    Respond in a friendly, direct chat style:
                    1. Start with a 1-3 sentence conversational answer.
                    2. Follow with 2-4 concise bullet points starting with '- '.
                    """,
                    systemInstruction,
                    context != null ? context.toPromptContext() : "No screen telemetry",
                    effectivePromptQuestion
            );

            // 1. Direct Google Gemini 2.5 Flash HTTPS REST API (when API key is configured)
            if (hasGeminiApiKey()) {
                try {
                    String geminiText = callGeminiRestApi(systemInstruction, prompt);
                    if (geminiText != null && !geminiText.isBlank()) {
                        conversationHistory.add(new ChatTurn("user", effectivePromptQuestion));
                        conversationHistory.add(new ChatTurn("model", geminiText));
                        return parseConversationalAiResponse(geminiText, deterministic);
                    }
                } catch (Throwable t) {
                    log.warn("Gemini REST API call failed, falling back to local ERP AI: {}", t.getMessage());
                }
            }

            // 2. Antigravity SDK Harness (if active)
            if (aiAvailable && agent != null) {
                try {
                    AgentResponse response = agent.chat(prompt).get(12, TimeUnit.SECONDS);
                    String text = response != null ? response.text() : "";
                    if (text != null && !text.isBlank()) {
                        conversationHistory.add(new ChatTurn("user", effectivePromptQuestion));
                        conversationHistory.add(new ChatTurn("model", text));
                        return parseConversationalAiResponse(text, deterministic);
                    }
                } catch (Throwable t) {
                    log.warn("Copilot SDK query timed out or failed, using local ERP knowledge base: {}", t.getMessage());
                }
            }

            conversationHistory.add(new ChatTurn("user", effectivePromptQuestion));
            conversationHistory.add(new ChatTurn("model", deterministic.summary()));
            return deterministic;
        });
    }

    private CopilotResponse parseConversationalAiResponse(String rawText, CopilotResponse fallback) {
        String[] lines = rawText.split("\\r?\\n");
        StringBuilder summaryBuilder = new StringBuilder();
        List<String> extractedBullets = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine.trim().replaceAll("\\*\\*", "");
            if (line.isBlank()) continue;
            if (line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ")) {
                extractedBullets.add(line.substring(2).trim());
            } else if (line.matches("^\\d+\\.\\s+.*")) {
                extractedBullets.add(line.replaceFirst("^\\d+\\.\\s+", "").trim());
            } else if (extractedBullets.isEmpty()) {
                if (!summaryBuilder.isEmpty()) summaryBuilder.append(" ");
                summaryBuilder.append(line);
            } else {
                extractedBullets.add(line);
            }
        }

        String finalSummary = summaryBuilder.isEmpty() ? rawText.trim() : summaryBuilder.toString();
        List<String> finalBullets = extractedBullets.isEmpty() ? fallback.bulletInsights() : extractedBullets;
        return new CopilotResponse(
                "Jasvi AI Response (Gemini 2.5 Flash)",
                finalSummary,
                finalBullets,
                fallback.actions(),
                true
        );
    }

    private CopilotResponse buildDeterministicCopilotResponse(
            CopilotMode mode,
            String question,
            ActiveViewContextService.ScreenContext ctx
    ) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        String screen = ctx != null ? ctx.screenName() : "Application Workspace";
        String role = ctx != null ? ctx.role() : "USER";
        List<String> bullets = new ArrayList<>();
        List<CopilotAction> actions = new ArrayList<>();

        if (q.contains("gemini") || q.contains("api key") || q.contains("how do you work") || q.contains("how you work")) {
            String title = "How Jasvi AI Chat & Gemini 2.5 Flash Work";
            String summary = "I operate in a dual-engine hybrid mode: I inspect your live ERP screen directly on your workstation (100% offline-capable) and can also connect to Google Gemini 2.5 Flash via HTTPS when you add an API key.";
            bullets.add("Live Screen Reader: Every time you send a message, I read the active screen ('" + screen + "'), your role ('" + role + "'), disabled buttons, and validation messages—while automatically masking PAN and bank numbers.");
            bullets.add("Connect Google Gemini 2.5 Flash: Click [API Key] in the top bar of this chat and paste your Google Gemini API key to enable live cloud LLM reasoning.");
            bullets.add("Instant Offline ERP Intelligence: Even without an internet connection or API key, I answer questions about subscriptions, GST, bank statements, and screen errors immediately.");
            actions.add(new CopilotAction("Re-Scan Active Screen", "RESCAN_SCREEN", "view"));
            actions.add(new CopilotAction("Open System Diagnostics", "OPEN_SYSTEM_DIAGNOSTICS", "diagnostic"));
            return new CopilotResponse(title, summary, bullets, actions, false);
        }

        if (mode == CopilotMode.ONBOARDING_SUBSCRIPTION || q.contains("subscription") || q.contains("plan")
                || q.contains("license") || q.contains("jlic") || q.contains("jreq") || q.contains("activate") || q.contains("price")) {
            String title = "Jasvi ERP Subscription Plans & License Activation";
            String summary = "Here is a quick comparison of the three Jasvi ERP subscription tiers and how to activate your workstation in 1 click:";
            bullets.add("STANDARD Plan: Single workstation — Sales, Purchases, Quotations, Inventory, Barcode Studio, and PDF/Excel Invoicing.");
            bullets.add("ENTERPRISE Plan: Multi-User LAN/Cloud sync, Universal Bank Statement Import (with auto-decryption), GST E-Invoicing, E-Way Bills, and Bill-to-Tally.");
            bullets.add("CA / MULTI-FIRM Plan: Unlimited client company ledgers, multi-branch consolidation, and SuperAdmin audit controls.");
            bullets.add("1-Click Activation: Click [Open License & Activation] below to generate your '.jreq' hardware file or import your signed '.jlic' license.");
            actions.add(new CopilotAction("Open License & Activation", "OPEN_LICENSE_RENEWAL", "security"));
            actions.add(new CopilotAction("Copy Machine ID (.jreq)", "COPY_MACHINE_ID", "copy"));
            return new CopilotResponse(title, summary, bullets, actions, false);
        }

        if (q.contains("gst") || q.contains("eway") || q.contains("e-way") || q.contains("tally") || q.contains("tax") || q.contains("invoice") || q.contains("sale")) {
            String title = "Invoicing, GST Compliance & Tally Sync Guide";
            String summary = "You are currently on '" + screen + "'. Here is how Sales, GST calculation, E-Way Bills, and Tally export work together:";
            bullets.add("Smart GST Split: Intra-state parties (same state code as your company GSTIN) automatically calculate CGST + SGST; inter-state parties calculate IGST.");
            bullets.add("Fast Entry Shortcuts: Press F2 to create a New Sale, F3 for Purchase, F4 for Quotation, and Ctrl + Space anytime to open this AI Chat.");
            bullets.add("Bill-to-Tally & GST Returns: Export validated vouchers directly to Tally XML or open GST Compliance Center for GSTR-1 / GSTR-3B reconciliation.");
            actions.add(new CopilotAction("Re-Scan Active Screen", "RESCAN_SCREEN", "view"));
            actions.add(new CopilotAction("Open System Diagnostics", "OPEN_SYSTEM_DIAGNOSTICS", "diagnostic"));
            return new CopilotResponse(title, summary, bullets, actions, false);
        }

        if (mode == CopilotMode.ERROR_TROUBLESHOOT || q.contains("error") || q.contains("fail")
                || q.contains("password") || q.contains("decrypt") || q.contains("connect") || q.contains("bank")) {
            String title = "Self-Healing Error & Bank Statement Troubleshooter";
            String summary = "I checked your workstation health on '" + screen + "'. Here is how to resolve common bank import or connectivity issues immediately:";
            bullets.add("Password-Protected Bank Statements: Read-only Excel sheets unlock automatically; if a bank PDF/Excel uses a personal PIN, enter DOB (DDMMYYYY), PAN (uppercase), or Account Number.");
            bullets.add("Account Mismatch Protection: If the statement account number differs from your selected bank ledger, Jasvi ERP warns you before importing.");
            bullets.add("Server & Database Health: Click [Verify Server & Sync Now] below to test Spring Boot (port 58080) and PostgreSQL 16 (port 55432).");
            actions.add(new CopilotAction("Verify Server & Sync Now", "CHECK_SERVER_CONNECTION", "refresh"));
            actions.add(new CopilotAction("Open System Diagnostics", "OPEN_SYSTEM_DIAGNOSTICS", "diagnostic"));
            return new CopilotResponse(title, summary, bullets, actions, false);
        }

        if (mode == CopilotMode.ROLE_ASSISTANT || q.contains("superadmin") || q.contains("admin") || q.contains("role") || q.contains("permission")) {
            String title = "Role-Based Guidance for " + role;
            String summary = "You are signed in with role '" + role + "' on screen '" + screen + "'. Here are your primary capabilities:";
            if (role.toUpperCase(Locale.ROOT).contains("SUPER")) {
                bullets.add("SuperAdmin Licensing Hub: Issue client subscriptions, generate .jlic + .json bundles, and send via WhatsApp & Email from Master Client Registry.");
                bullets.add("Global System Control: Monitor live client heartbeats, Cloudflare remote tunnels, and full permission matrices.");
            } else if (role.toUpperCase(Locale.ROOT).contains("ADMIN")) {
                bullets.add("Company & Role Governance: Configure company GSTIN, financial year defaults (April 1st to Today), and iOS-style role toggles in Permission Matrix.");
                bullets.add("Safety & Backups: Run 1-click PostgreSQL backups and review the Global Audit Trail.");
            } else {
                bullets.add("Operator Productivity: Create Sales, Purchases, Quotations, and reconcile Bank Statements with automatic 2-digit year date normalization.");
                bullets.add("Need Access?: If a sidebar module or action button is locked, ask your Admin to enable it in User Access > Permission Matrix.");
            }
            actions.add(new CopilotAction("Re-Scan Active Screen", "RESCAN_SCREEN", "view"));
            actions.add(new CopilotAction("Open License & Activation", "OPEN_LICENSE_RENEWAL", "security"));
            return new CopilotResponse(title, summary, bullets, actions, false);
        }

        // Default: Conversational Live Screen Reader
        String title = "Screen Assistant • " + screen;
        List<String> disabled = ctx != null ? ctx.disabledControls() : List.of();
        List<String> errors = ctx != null ? ctx.validationErrors() : List.of();
        List<String> empty = ctx != null ? ctx.emptyRequiredFields() : List.of();

        String summary;
        if (!question.isBlank() && !q.contains("inspect") && !q.contains("scan") && !q.contains("disabled")) {
            summary = "Regarding \"" + question + "\" on '" + screen + "' (Role: " + role + "): I inspected the current screen state to help you complete this step.";
        } else if (!errors.isEmpty()) {
            summary = "I found " + errors.size() + " active validation message(s) on '" + screen + "' that need attention:";
        } else if (!disabled.isEmpty()) {
            summary = "I scanned '" + screen + "' and noticed " + disabled.size() + " button(s) are currently disabled until a row is selected or required inputs are filled:";
        } else {
            summary = "I scanned '" + screen + "' (Role: " + role + "). All visible controls and inputs look healthy and ready!";
        }

        if (!errors.isEmpty()) {
            bullets.add("Active Validation Alerts: " + String.join(" | ", errors));
        }
        if (!disabled.isEmpty()) {
            bullets.add("Disabled Buttons on Screen: " + String.join(", ", disabled)
                    + " — select a row in the table or fill required fields to enable them.");
        }
        if (!empty.isEmpty()) {
            bullets.add("Empty Inputs Ready to Fill: " + String.join(", ", empty));
        }
        if (ctx != null && ctx.activeSelectionSummary() != null) {
            bullets.add("Table State: " + ctx.activeSelectionSummary());
        }
        bullets.add("Ask me anything below — e.g., \"Which subscription plan fits my company?\", \"Why is a button disabled?\", or \"How does Gemini API work?\"");

        actions.add(new CopilotAction("Re-Scan Active Screen", "RESCAN_SCREEN", "view"));
        actions.add(new CopilotAction("Open License & Activation", "OPEN_LICENSE_RENEWAL", "security"));
        actions.add(new CopilotAction("Open System Diagnostics", "OPEN_SYSTEM_DIAGNOSTICS", "diagnostic"));
        return new CopilotResponse(title, summary, bullets, actions, false);
    }

    /**
     * Infers 1-click self-healing actions for AiDiagnosisDialog based on the error signature.
     */
    public List<CopilotAction> inferSelfHealingActions(String title, String message, String technicalDetail) {
        String combined = ((title != null ? title : "") + " "
                + (message != null ? message : "") + " "
                + (technicalDetail != null ? technicalDetail : "")).toLowerCase(Locale.ROOT);
        List<CopilotAction> actions = new ArrayList<>();
        if (combined.contains("connect") || combined.contains("timeout") || combined.contains("58080") || combined.contains("offline")) {
            actions.add(new CopilotAction("Reconnect & Sync Server", "CHECK_SERVER_CONNECTION", "refresh"));
        }
        if (combined.contains("license") || combined.contains("subscription") || combined.contains("expired") || combined.contains("activation")) {
            actions.add(new CopilotAction("Open License Activation", "OPEN_LICENSE_RENEWAL", "security"));
        }
        actions.add(new CopilotAction("Ask Jasvi AI Copilot", "OPEN_AI_COPILOT", "diagnostic"));
        return actions;
    }

    public synchronized void shutdown() {
        if (agent != null) {
            try {
                agent.close();
                log.info("ErpAgentService: Antigravity agent safely closed.");
            } catch (Throwable t) {
                log.warn("ErpAgentService: Error closing agent: {}", t.getMessage());
            } finally {
                agent = null;
                initialized = false;
            }
        }
    }
}
