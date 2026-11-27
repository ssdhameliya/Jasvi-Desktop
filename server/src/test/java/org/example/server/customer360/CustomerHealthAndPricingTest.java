package org.example.server.customer360;

import org.example.server.audit.AuditService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerHealthAndPricingTest {

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void setSalesUser() {
        var principal = new AuthenticatedUser(10, "billing", "SALES");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, "",
                        List.of(new SimpleGrantedAuthority("SALES.CREATE"), new SimpleGrantedAuthority("SALES.VIEW"))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void creditHealthComputesNetReceivableOverdueAndCreditUsedPercent() {
        setSalesUser();
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        AuditService audit = mock(AuditService.class);

        when(jdbc.query(contains("FROM party_master"), any(BiFunction.class), eq(7)))
                .thenReturn(java.util.Collections.singletonList(new Object[]{new BigDecimal("10000.00"), new BigDecimal("200000.00")}));
        when(jdbc.queryForObject(contains("COALESCE(SUM(GREATEST(COALESCE(total_amount,0)-COALESCE(paid_amount,0),0)),0) FROM sales_header WHERE customer_id=? AND UPPER"),
                eq(BigDecimal.class), eq(7)))
                .thenReturn(new BigDecimal("140000.00"));
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM sales_header"), eq(Long.class), eq(7), any()))
                .thenReturn(2L);
        when(jdbc.queryForObject(contains("dse_safe_date(due_date) < dse_safe_date(?)"), eq(BigDecimal.class), eq(7), any()))
                .thenReturn(new BigDecimal("45000.00"));
        when(jdbc.query(contains("FROM payment_record pr JOIN sales_header"), any(BiFunction.class), eq(7)))
                .thenReturn(java.util.Collections.singletonList(new Object[]{new BigDecimal("25000.00"), "2026-11-20"}));

        Customer360Service svc = new Customer360Service(jdbc, audit);
        Customer360Dtos.CreditHealth health = svc.creditHealth(7);

        assertEquals(new BigDecimal("150000.00"), health.netReceivable());
        assertEquals(2L, health.overdueCount());
        assertEquals(new BigDecimal("45000.00"), health.overdueAmount());
        assertEquals(new BigDecimal("200000.00"), health.creditLimit());
        assertEquals(75.0, health.creditUsedPercent(), 0.01);
        assertEquals(new BigDecimal("25000.00"), health.lastPaymentAmount());
    }

    @SuppressWarnings("unchecked")
    @Test
    void itemPricingMatchesHighestQualifyingQuantitySlabAndLastSoldRate() {
        setSalesUser();
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        AuditService audit = mock(AuditService.class);

        when(jdbc.query(contains("WHERE id=? AND UPPER(party_type)='CUSTOMER'"), any(BiFunction.class), eq(7)))
                .thenReturn(List.of(new Customer360Dtos.Customer(7, "C007", "Acme", "", "", "", "", "", BigDecimal.ZERO, true, 0L)));
        when(jdbc.query(contains("FROM item_master WHERE UPPER(item_code)"), any(BiFunction.class), eq("ITM-01")))
                .thenReturn(java.util.Collections.singletonList(new double[]{350.0, 500.0}));
        when(jdbc.query(contains("FROM sales_line sl JOIN sales_header"), any(BiFunction.class), eq(7), eq("ITM-01")))
                .thenReturn(java.util.Collections.singletonList(new Object[]{460.0, 2.0, "2026-11-10", "IN/10-11-2026/0012"}));
        when(jdbc.query(contains("FROM customer_price_rule"), any(BiFunction.class), eq(7)))
                .thenReturn(List.of(
                        new Customer360Dtos.PriceRuleRow(1L, 7, "ITM-01", "Widget", 1, 480.0, 0, "", "", "", true, 0L),
                        new Customer360Dtos.PriceRuleRow(2L, 7, "ITM-01", "Widget", 50, 440.0, 5.0, "", "", "Bulk slab", true, 0L),
                        new Customer360Dtos.PriceRuleRow(3L, 7, "ITM-01", "Widget", 100, 410.0, 5.0, "", "", "Tier 100+", true, 0L)
                ));

        Customer360Service svc = new Customer360Service(jdbc, audit);
        Customer360Dtos.ItemPricingInsight insight = svc.itemPricing(7, "ITM-01", 60.0);

        assertEquals(350.0, insight.purchaseCost(), 0.01);
        assertEquals(460.0, insight.lastSoldRate(), 0.01);
        assertNotNull(insight.matchedRule());
        assertEquals(2L, insight.matchedRule().id());
        assertEquals(440.0, insight.matchedRule().specialRate(), 0.01);
        assertEquals(3, insight.allSlabs().size());
    }
}
