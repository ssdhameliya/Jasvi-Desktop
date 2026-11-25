package org.example.server.financial;

import org.example.server.audit.AuditService;
import org.example.server.insights.CashPositionService;
import org.example.server.operations.BusinessOperationsService;
import org.example.server.operations.OperationDtos;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.PartyEntity;
import org.example.server.persistence.entity.PurchaseChargeEntity;
import org.example.server.persistence.entity.PurchaseHeaderEntity;
import org.example.server.persistence.entity.PurchaseLineEntity;
import org.example.server.persistence.entity.SalesChargeEntity;
import org.example.server.persistence.entity.SalesHeaderEntity;
import org.example.server.persistence.entity.SalesLineEntity;
import org.example.server.persistence.repository.BankReconciliationAllocationRepository;
import org.example.server.persistence.repository.FinanceRegisterRepository;
import org.example.server.persistence.repository.ItemRepository;
import org.example.server.persistence.repository.LookupRepository;
import org.example.server.persistence.repository.MasterCategoryRepository;
import org.example.server.persistence.repository.PartyRepository;
import org.example.server.persistence.repository.PurchaseChargeRepository;
import org.example.server.persistence.repository.PurchaseHeaderRepository;
import org.example.server.persistence.repository.PurchaseLineRepository;
import org.example.server.persistence.repository.SalesChargeRepository;
import org.example.server.persistence.repository.SalesHeaderRepository;
import org.example.server.persistence.repository.SalesLineRepository;
import org.example.server.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that updating Sale or Purchase additional charges flushes the delete of
 * existing sequence rows before inserting replacement rows with sequence_no = 1..N.
 */
class ChargeReplacementOrderContractTest {

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(1, "admin", "ADMIN"),
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void chargeDeleteQueriesFlushWithoutClearingParentHeaderPersistenceContext() throws Exception {
        Method deleteSales = SalesChargeRepository.class.getMethod("deleteBySalesId", Integer.class);
        Modifying salesModifying = deleteSales.getAnnotation(Modifying.class);
        Query salesQuery = deleteSales.getAnnotation(Query.class);
        assertNotNull(salesModifying, "SalesChargeRepository.deleteBySalesId must declare @Modifying");
        assertTrue(salesModifying.flushAutomatically(), "SalesChargeRepository.deleteBySalesId must flushAutomatically");
        assertFalse(salesModifying.clearAutomatically(), "clearAutomatically must stay false so managed SalesHeaderEntity is not detached");
        assertNotNull(salesQuery, "SalesChargeRepository.deleteBySalesId must declare explicit DELETE @Query");
        assertTrue(salesQuery.value().toUpperCase().startsWith("DELETE FROM SALESCHARGEENTITY"));

        Method deletePurchases = PurchaseChargeRepository.class.getMethod("deleteByPurchaseId", Integer.class);
        Modifying purchaseModifying = deletePurchases.getAnnotation(Modifying.class);
        Query purchaseQuery = deletePurchases.getAnnotation(Query.class);
        assertNotNull(purchaseModifying, "PurchaseChargeRepository.deleteByPurchaseId must declare @Modifying");
        assertTrue(purchaseModifying.flushAutomatically(), "PurchaseChargeRepository.deleteByPurchaseId must flushAutomatically");
        assertFalse(purchaseModifying.clearAutomatically(), "clearAutomatically must stay false so managed PurchaseHeaderEntity is not detached");
        assertNotNull(purchaseQuery, "PurchaseChargeRepository.deleteByPurchaseId must declare explicit DELETE @Query");
        assertTrue(purchaseQuery.value().toUpperCase().startsWith("DELETE FROM PURCHASECHARGEENTITY"));
    }

    @Test
    void updateSaleWithModifiedChargesFlushesDeleteBeforeInsertingSequenceOne() {
        SalesHeaderRepository sales = mock(SalesHeaderRepository.class);
        SalesLineRepository salesLines = mock(SalesLineRepository.class);
        SalesChargeRepository salesCharges = mock(SalesChargeRepository.class);
        PurchaseHeaderRepository purchases = mock(PurchaseHeaderRepository.class);
        PurchaseLineRepository purchaseLines = mock(PurchaseLineRepository.class);
        PurchaseChargeRepository purchaseCharges = mock(PurchaseChargeRepository.class);
        PartyRepository parties = mock(PartyRepository.class);
        ItemRepository items = mock(ItemRepository.class);
        LookupRepository lookups = mock(LookupRepository.class);
        MasterCategoryRepository categories = mock(MasterCategoryRepository.class);
        FinanceRegisterRepository finance = mock(FinanceRegisterRepository.class);
        BankReconciliationAllocationRepository allocations = mock(BankReconciliationAllocationRepository.class);
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        AuditService audit = mock(AuditService.class);
        CashPositionService cash = mock(CashPositionService.class);

        BusinessOperationsService service = new BusinessOperationsService(
                sales, salesLines, salesCharges, purchases, purchaseLines, purchaseCharges,
                parties, items, lookups, categories, finance, allocations, jdbc, audit, cash);

        PartyEntity customer = new PartyEntity();
        customer.setId(10);
        customer.setPartyType("CUSTOMER");
        customer.setPartyCode("CUS/01");
        customer.setName("Test Customer");
        customer.setActive(1);

        SalesHeaderEntity existingHeader = new SalesHeaderEntity();
        existingHeader.setId(42);
        existingHeader.setInvoiceNo("IN/21-11-2026/0002");
        existingHeader.setInvoiceDate("2026-11-21");
        existingHeader.setCustomer(customer);
        existingHeader.setSubtotal(6000d);
        existingHeader.setGstAmount(1170d);
        existingHeader.setTotalAmount(7670d);
        existingHeader.setDiscountAmount(0d);
        existingHeader.setPaidAmount(0d);
        existingHeader.setPaymentStatus("PENDING");
        existingHeader.setDocumentStatus("APPROVED");
        existingHeader.setApprovalStatus("APPROVED");
        existingHeader.setInventoryPosted(true);
        existingHeader.setRowVersion(0L);

        SalesLineEntity existingLine = new SalesLineEntity();
        existingLine.setSalesId(42);
        existingLine.setItemCode("ITM-01");
        existingLine.setQuantity(4d);
        existingLine.setRate(1500d);
        existingLine.setDiscountPercent(0d);
        existingLine.setDiscountAmount(0d);
        existingLine.setGstPercent(18d);
        existingLine.setLineTotal(7080d);

        SalesChargeEntity existingCharge = new SalesChargeEntity();
        existingCharge.setId(101);
        existingCharge.setSalesId(42);
        existingCharge.setSequenceNo(1);
        existingCharge.setChargeName("Freight");
        existingCharge.setAmount(new BigDecimal("500.00"));
        existingCharge.setTaxable(true);
        existingCharge.setGstPercent(new BigDecimal("18.00"));

        when(sales.findByInvoiceNoForUpdate("IN/21-11-2026/0002")).thenReturn(Optional.of(existingHeader));
        when(parties.findById(10)).thenReturn(Optional.of(customer));
        when(salesLines.findBySalesIdOrderByIdAsc(42)).thenReturn(List.of(existingLine));
        when(salesCharges.findBySalesIdOrderBySequenceNoAscIdAsc(42)).thenReturn(List.of(existingCharge));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Double.class), any(), any())).thenReturn(0d);

        OperationDtos.SaleDto updateRequest = new OperationDtos.SaleDto(
                42, "IN/21-11-2026/0002", "2026-11-21",
                new OperationDtos.PartyDto(10, "CUS/01", "Test Customer", null, null, null, null),
                6000d, 0d, 1170d, 7670d, "", null, false, "2026-12-06", 0d, "PENDING", false,
                "TAX", "Admin", "DESKTOP", "", "", "15 Days", "", "", null, "", "GST", "", "", "", "", "",
                "", "", "", true, "", "Freight", 750d, "", "APPROVED", "", 4d,
                List.of(new OperationDtos.ChargeDto("Freight", 750d, true, 18d)),
                List.of(new OperationDtos.LineDto("ITM-01", "Pipe", "Pipe", "73063000", "Nos", "", 4d, 1500d, 0d, 0d, 18d, 7080d)),
                0L);

        service.updateSale(updateRequest);

        InOrder order = inOrder(salesCharges);
        order.verify(salesCharges).deleteBySalesId(42);
        order.verify(salesCharges).flush();
        order.verify(salesCharges).save(Mockito.argThat(saved ->
                Integer.valueOf(42).equals(saved.getSalesId())
                        && Integer.valueOf(1).equals(saved.getSequenceNo())
                        && new BigDecimal("750.00").compareTo(saved.getAmount()) == 0));
        order.verify(salesCharges).flush();
    }

    @Test
    void updatePurchaseWithModifiedChargesFlushesDeleteBeforeInsertingSequenceOne() {
        SalesHeaderRepository sales = mock(SalesHeaderRepository.class);
        SalesLineRepository salesLines = mock(SalesLineRepository.class);
        SalesChargeRepository salesCharges = mock(SalesChargeRepository.class);
        PurchaseHeaderRepository purchases = mock(PurchaseHeaderRepository.class);
        PurchaseLineRepository purchaseLines = mock(PurchaseLineRepository.class);
        PurchaseChargeRepository purchaseCharges = mock(PurchaseChargeRepository.class);
        PartyRepository parties = mock(PartyRepository.class);
        ItemRepository items = mock(ItemRepository.class);
        LookupRepository lookups = mock(LookupRepository.class);
        MasterCategoryRepository categories = mock(MasterCategoryRepository.class);
        FinanceRegisterRepository finance = mock(FinanceRegisterRepository.class);
        BankReconciliationAllocationRepository allocations = mock(BankReconciliationAllocationRepository.class);
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        AuditService audit = mock(AuditService.class);
        CashPositionService cash = mock(CashPositionService.class);

        BusinessOperationsService service = new BusinessOperationsService(
                sales, salesLines, salesCharges, purchases, purchaseLines, purchaseCharges,
                parties, items, lookups, categories, finance, allocations, jdbc, audit, cash);

        PartyEntity supplier = new PartyEntity();
        supplier.setId(20);
        supplier.setPartyType("SUPPLIER");
        supplier.setPartyCode("SUP/01");
        supplier.setName("Test Supplier");
        supplier.setActive(1);

        PurchaseHeaderEntity existingHeader = new PurchaseHeaderEntity();
        existingHeader.setId(77);
        existingHeader.setInvoiceNo("PO/21-11-2026/0002");
        existingHeader.setInvoiceDate("2026-11-21");
        existingHeader.setSupplier(supplier);
        existingHeader.setSubtotal(2000d);
        existingHeader.setGstAmount(360d);
        existingHeader.setTotalAmount(2860d);
        existingHeader.setDiscountAmount(0d);
        existingHeader.setPaidAmount(0d);
        existingHeader.setPaymentStatus("PENDING");
        existingHeader.setDocumentStatus("COMPLETED");
        existingHeader.setApprovalStatus("APPROVED");
        existingHeader.setInventoryPosted(true);
        existingHeader.setRowVersion(0L);

        PurchaseLineEntity existingLine = new PurchaseLineEntity();
        existingLine.setPurchaseId(77);
        existingLine.setItemCode("ITM-01");
        existingLine.setQuantity(2d);
        existingLine.setRate(1000d);
        existingLine.setDiscountPercent(0d);
        existingLine.setDiscountAmount(0d);
        existingLine.setGstPercent(18d);
        existingLine.setLineTotal(2360d);

        PurchaseChargeEntity existingCharge = new PurchaseChargeEntity();
        existingCharge.setId(201);
        existingCharge.setPurchaseId(77);
        existingCharge.setSequenceNo(1);
        existingCharge.setChargeName("Freight");
        existingCharge.setAmount(new BigDecimal("500.00"));
        existingCharge.setTaxable(false);
        existingCharge.setGstPercent(new BigDecimal("0.00"));

        when(purchases.findByInvoiceNoForUpdate("PO/21-11-2026/0002")).thenReturn(Optional.of(existingHeader));
        when(parties.findById(20)).thenReturn(Optional.of(supplier));
        when(purchaseLines.findByPurchaseIdOrderByIdAsc(77)).thenReturn(List.of(existingLine));
        when(purchaseCharges.findByPurchaseIdOrderBySequenceNoAscIdAsc(77)).thenReturn(List.of(existingCharge));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Double.class), any(), any())).thenReturn(0d);

        OperationDtos.PurchaseDto updateRequest = new OperationDtos.PurchaseDto(
                77, "PO/21-11-2026/0002", "2026-11-21",
                new OperationDtos.PartyDto(20, "SUP/01", "Test Supplier", null, null, null, null),
                2000d, 360d, 2860d, "", null, false, "2026-12-06", 0d, "PENDING", "COMPLETED",
                "MAIN", "15 Days", "INR", "", "REGISTERED", "", "", "PERCENT", 0d, "", "admin",
                "", "", "", "", "", "GST", "", "", "", "", "", "", "", true, 2d,
                List.of(new OperationDtos.ChargeDto("Freight", 900d, true, 18d)),
                List.of(new OperationDtos.LineDto("ITM-01", "Pipe", "Pipe", "73063000", "Nos", "", 2d, 1000d, 0d, 0d, 18d, 2360d)),
                0L);

        service.updatePurchase(updateRequest);

        InOrder order = inOrder(purchaseCharges);
        order.verify(purchaseCharges).deleteByPurchaseId(77);
        order.verify(purchaseCharges).flush();
        order.verify(purchaseCharges).save(Mockito.argThat(saved ->
                Integer.valueOf(77).equals(saved.getPurchaseId())
                        && Integer.valueOf(1).equals(saved.getSequenceNo())
                        && new BigDecimal("900.00").compareTo(saved.getAmount()) == 0));
        order.verify(purchaseCharges).flush();
    }
}
