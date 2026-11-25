package org.example.server.aging;

import org.example.server.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping({"/api/aging", "/api/financial/aging"})
public class AgingAnalysisController {

    private final AgingAnalysisService service;

    public AgingAnalysisController(AgingAnalysisService service) {
        this.service = service;
    }

    private static void requireView(String detail) {
        if (!CurrentUser.hasPermission("AGING_ANALYSIS.VIEW")
                && !CurrentUser.hasPermission("GENERAL_LEDGER.VIEW")) {
            CurrentUser.requirePermission("AGING_ANALYSIS.VIEW", detail);
        }
    }

    @GetMapping({"", "/"})
    public AgingAnalysisDtos.DesktopAgingReport getAgingReport(
            @RequestParam(defaultValue = "DEBTOR") String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        requireView("View Aging Analysis report");
        return service.getDesktopAgingReport(type, asOfDate);
    }

    @GetMapping("/receivables")
    public AgingAnalysisDtos.AgingSummaryDto getReceivablesAging(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        requireView("View Receivables Aging");
        return service.getReceivablesAging(asOfDate);
    }

    @GetMapping("/payables")
    public AgingAnalysisDtos.AgingSummaryDto getPayablesAging(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        requireView("View Payables Aging");
        return service.getPayablesAging(asOfDate);
    }

    @GetMapping("/party-statement/{partyId}")
    public AgingAnalysisDtos.PartyStatementDto getPartyStatement(
            @PathVariable Long partyId,
            @RequestParam(defaultValue = "DEBTOR") String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        requireView("View Party Aging Statement");
        return service.getPartyStatement(partyId, type, asOfDate);
    }

    @PostMapping("/remind/{partyId}")
    public boolean sendPaymentReminder(@PathVariable Long partyId, @RequestParam(required = false) String user) {
        CurrentUser.requirePermission("AGING_ANALYSIS.REMIND", "Send payment reminder");
        return service.sendPaymentReminder(partyId, user);
    }
}
