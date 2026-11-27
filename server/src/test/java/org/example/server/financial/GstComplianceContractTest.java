package org.example.server.financial;

import org.example.server.gst.GstDtos.*;
import org.example.server.gst.Gstr1Dtos;
import org.example.server.gst.Gstr1Service;
import org.example.server.persistence.JpaNativeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GstComplianceContractTest {

    @Test
    void testPlaceOfSupplyIntraStateSplit() {
        String companyGstin = "24AABCS1429B1Z1"; // Gujarat state 24
        String customerGstin = "24AAACD8819P1Z2"; // Gujarat state 24

        String supplierState = companyGstin.substring(0, 2);
        String posState = customerGstin.substring(0, 2);

        boolean isIntraState = supplierState.equals(posState);
        assertTrue(isIntraState, "Supplies within Gujarat must be intra-state");

        BigDecimal taxable = new BigDecimal("100000.00");
        BigDecimal gstRate = new BigDecimal("18.00");

        BigDecimal totalTax = taxable.multiply(gstRate).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal cgst = isIntraState ? totalTax.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal sgst = isIntraState ? totalTax.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal igst = isIntraState ? BigDecimal.ZERO : totalTax;

        assertEquals(new BigDecimal("9000.00"), cgst);
        assertEquals(new BigDecimal("9000.00"), sgst);
        assertEquals(BigDecimal.ZERO, igst);
        assertEquals(new BigDecimal("18000.00"), cgst.add(sgst));
    }

    @Test
    void testPlaceOfSupplyInterStateSplit() {
        String companyGstin = "24AABCS1429B1Z1"; // Gujarat state 24
        String customerGstin = "27AAACB2212M1Z8"; // Maharashtra state 27

        String supplierState = companyGstin.substring(0, 2);
        String posState = customerGstin.substring(0, 2);

        boolean isInterState = !supplierState.equals(posState);
        assertTrue(isInterState, "Supplies between Gujarat and Maharashtra must be inter-state");

        BigDecimal taxable = new BigDecimal("100000.00");
        BigDecimal gstRate = new BigDecimal("18.00");

        BigDecimal totalTax = taxable.multiply(gstRate).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal cgst = isInterState ? BigDecimal.ZERO : totalTax.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal sgst = isInterState ? BigDecimal.ZERO : totalTax.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal igst = isInterState ? totalTax : BigDecimal.ZERO;

        assertEquals(BigDecimal.ZERO, cgst);
        assertEquals(BigDecimal.ZERO, sgst);
        assertEquals(new BigDecimal("18000.00"), igst);
    }

    @Test
    void testGstr2bFuzzyReconciliationWithinTolerance() {
        BigDecimal portalTax = new BigDecimal("18000.50");
        BigDecimal booksTax = new BigDecimal("18000.00");

        BigDecimal diff = portalTax.subtract(booksTax).abs();
        BigDecimal tolerance = new BigDecimal("1.00");

        boolean isMatched = diff.compareTo(tolerance) <= 0;
        assertTrue(isMatched, "₹ 0.50 difference must be accepted within ± ₹ 1.00 rounding tolerance");
    }

    @Test
    void testTdsSection194QCalculation() {
        // Purchases exceeding 50 Lakhs in a financial year are subject to 0.1% TDS
        BigDecimal threshold = new BigDecimal("5000000.00");
        BigDecimal cumulativePurchases = new BigDecimal("5500000.00");

        assertTrue(cumulativePurchases.compareTo(threshold) > 0, "Exceeds 50L limit");

        BigDecimal excessAmount = cumulativePurchases.subtract(threshold);
        BigDecimal tdsRate = new BigDecimal("0.001"); // 0.1%

        BigDecimal tdsAmount = excessAmount.multiply(tdsRate).setScale(2, java.math.RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("500.00"), tdsAmount, "TDS on 5 Lakhs excess must be ₹ 500");
    }

    @Test
    void gstr1Table9bPopulatesActiveRegisteredCreditNotesAndSkipsPurchaseReturns() {
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("24AABCS1429B1Z1");
        when(jdbc.queryForList(anyString(), any(), any())).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            if (sql.contains("FROM credit_note_header")) {
                assertTrue(sql.contains("NOT IN ('CANCELLED', 'VOID', 'DELETED')"));
                return List.of(Map.ofEntries(
                        Map.entry("id", 1L),
                        Map.entry("credit_note_no", "CN/26-11-2026/0001"),
                        Map.entry("note_date", LocalDate.of(2026, 11, 26)),
                        Map.entry("original_invoice_no", "IN/23-11-2026/0001"),
                        Map.entry("party_name", "Shailesh"),
                        Map.entry("party_gstin", "24AAAAAAAAAAAAA"),
                        Map.entry("taxable_amount", new BigDecimal("3009494.96")),
                        Map.entry("cgst_amount", new BigDecimal("270854.55")),
                        Map.entry("sgst_amount", new BigDecimal("270854.54")),
                        Map.entry("igst_amount", BigDecimal.ZERO),
                        Map.entry("total_amount", new BigDecimal("3551204.00"))
                ));
            }
            if (sql.contains("FROM debit_note_header")) {
                assertTrue(sql.contains("<> 'PURCHASE_RETURN'"));
            }
            return List.of();
        });

        Gstr1Service service = new Gstr1Service(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        Gstr1Dtos.Gstr1FullReturnDto gstr1 = service.generateGstr1("112026");

        assertEquals(1, gstr1.cdnr().size(), "Table 9B CDNR must include active registered B2B credit note");
        assertEquals("24AAAAAAAAAAAAA", gstr1.cdnr().getFirst().ctin());
        assertEquals("CN/26-11-2026/0001", gstr1.cdnr().getFirst().nt().getFirst().nt_num());
        assertEquals("C", gstr1.cdnr().getFirst().nt().getFirst().ntty());
        assertEquals("IN/23-11-2026/0001", gstr1.cdnr().getFirst().nt().getFirst().inum());
    }
}
