package org.example.controller;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LicenseRenewalDialogControllerTest {

    @Test
    void parseCatalogPlansSupportsServerColumnKeysAndFiltersInactiveOrNonPositivePlans() {
        List<Map<String, Object>> serverRows = List.of(
                Map.of(
                        "id", 1,
                        "tier_code", "SILVER",
                        "plan_name", "Silver Starter",
                        "annual_fee", new BigDecimal("2500.00"),
                        "validity_days", 365,
                        "is_active", 1
                ),
                Map.of(
                        "id", 2,
                        "tier_code", "GOLD",
                        "plan_name", "Gold Standard",
                        "annual_fee", 3500.00d,
                        "validity_days", 365,
                        "is_active", 1
                ),
                Map.of(
                        "id", 3,
                        "tier_code", "LEGACY",
                        "plan_name", "Inactive Plan",
                        "annual_fee", 1999.00d,
                        "validity_days", 365,
                        "is_active", 0
                ),
                Map.of(
                        "id", 4,
                        "tier_code", "FREE",
                        "plan_name", "Free Tier",
                        "annual_fee", 0.00d,
                        "validity_days", 30,
                        "is_active", 1
                ),
                Map.of(
                        "plan_code", "PLATINUM",
                        "display_name", "Platinum Enterprise",
                        "tier", "PLATINUM",
                        "price_inr", 7500.0d,
                        "validity_days", 365
                )
        );

        List<LicenseRenewalDialogController.PlanOption> plans = LicenseRenewalDialogController.parseCatalogPlans(serverRows);

        assertEquals(3, plans.size());
        assertEquals("SILVER", plans.get(0).planCode());
        assertEquals("Silver Starter", plans.get(0).displayName());
        assertEquals(2500.0d, plans.get(0).priceInr(), 0.001);
        assertEquals("GOLD", plans.get(1).tier());
        assertEquals(3500.0d, plans.get(1).priceInr(), 0.001);
        assertEquals("PLATINUM", plans.get(2).planCode());
        assertEquals(7500.0d, plans.get(2).priceInr(), 0.001);
    }
}
