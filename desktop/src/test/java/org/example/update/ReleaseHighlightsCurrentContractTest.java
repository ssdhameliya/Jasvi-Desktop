package org.example.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseHighlightsCurrentContractTest {
    @Test
    void currentReleaseNotesAreApplicationOwnedAndDescribeTheCurrentCorrectionSet() throws Exception {
        String source = Files.readString(Path.of("src/main/java/org/example/update/ReleaseHighlights.java"));
        assertTrue(source.contains("Universal Instant Search Dropdowns"));
        assertTrue(source.contains("Universal Keyboard Workflow"));
        assertTrue(source.contains("Universal Modal Loading Overlay Engine"));
        assertTrue(source.contains("Customer-facing branding"));
        assertTrue(source.contains("Sales Register"));

        int start = source.indexOf("public static String resolve(");
        String resolve = source.substring(start);
        assertTrue(resolve.contains("if (BuildInfo.version().equals(version)) return fallback"),
                "The running build must use its packaged application-owned release summary");
        assertTrue(resolve.contains("generatedGitHubSummary"),
                "Historical/other release notes should still reject auto-generated GitHub summaries");
    }
}
