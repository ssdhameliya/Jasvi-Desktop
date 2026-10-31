package org.example.ai;

import io.github.glaforge.antigravity.Agent;
import io.github.glaforge.antigravity.AgentConfig;
import io.github.glaforge.antigravity.AgentResponse;
import javafx.application.Platform;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import org.example.theme.ThemeManager;
import org.example.util.SystemDiagnosticsDialog.DiagnosticRow;
import org.example.util.UiTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
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

    private Agent agent;
    private volatile boolean initialized = false;
    private volatile boolean aiAvailable = false;

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

    private synchronized void initAgent() {
        if (initialized) return;
        try {
            String apiKey = System.getenv("GEMINI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = System.getProperty("gemini.api.key", "");
            }
            if (apiKey.isBlank()) {
                apiKey = System.getenv("DSE_GEMINI_API_KEY");
            }

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
        return aiAvailable && agent != null;
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

    private DefectDiagnosis buildDeterministicDiagnosis(DiagnosticRow row, DefectSeverity severity) {
        String detail = row.technicalDetail() != null ? row.technicalDetail() : "";
        String failure = row.rawFailure() != null ? row.rawFailure() : "";
        String combined = (detail + " " + failure).toLowerCase(java.util.Locale.ROOT);

        String cause = row.friendlyExplanation();
        String fix = row.actionableGuidance();

        if (combined.contains("server resource not found") || combined.contains("illegalargumentexception: server resource")) {
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
