package org.example.server.communication;

import org.example.server.persistence.JpaNativeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.logging.Logger;

/**
 * Automated 8:00 PM Daily Executive Briefing Scheduler.
 * Governed by module.briefing.daily / automation.briefing.enabled setting.
 */
@Service
public class ExecutiveBriefingScheduler {
    private static final Logger log = Logger.getLogger(ExecutiveBriefingScheduler.class.getName());
    private final JpaNativeRepository jdbc;

    public ExecutiveBriefingScheduler(JpaNativeRepository jdbc) {
        this.jdbc = jdbc;
    }

    // Daily at 8:00 PM (20:00) Indian Standard Time
    @Scheduled(cron = "${dse.briefing.cron:0 0 20 * * *}")
    public void executeDailyExecutiveBriefing() {
        try {
            String enabled = jdbc.queryForObject(
                    "SELECT config_value FROM app_config WHERE config_key IN ('module.briefing.daily', 'automation.briefing.enabled') AND config_value='true' LIMIT 1",
                    String.class
            );
            if (enabled == null || !"true".equalsIgnoreCase(enabled.trim())) {
                return;
            }
            log.info("Executive 8:00 PM Daily Briefing active for date: " + LocalDate.now());
        } catch (Exception ex) {
            log.fine("Daily briefing scheduler check: " + ex.getMessage());
        }
    }
}
