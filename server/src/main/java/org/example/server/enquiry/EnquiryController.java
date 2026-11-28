package org.example.server.enquiry;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/enquiries")
public class EnquiryController {
    private final EnquiryService service;

    public EnquiryController(EnquiryService service) {
        this.service = service;
    }

    @GetMapping("/page")
    public EnquiryDtos.EnquiryPage page(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String status) {
        return service.page(page, size, q, type, status);
    }

    @GetMapping("/{id}")
    public EnquiryDtos.EnquiryDetailResponse detail(@PathVariable long id) {
        return service.detail(id);
    }

    @GetMapping("/by-number")
    public EnquiryDtos.EnquiryDetailResponse byNumber(@RequestParam String no) {
        return service.detailByNumber(no);
    }

    @PutMapping("/{id}/status")
    public EnquiryDtos.EnquiryRow updateStatus(
            @PathVariable long id,
            @RequestBody EnquiryDtos.StatusUpdateRequest req) {
        return service.updateStatus(id, req);
    }

    @PostMapping("/{id}/prepare-quotation")
    public EnquiryDtos.PrepareQuotationResponse prepareQuotation(@PathVariable long id) {
        return service.prepareQuotation(id);
    }

    @PostMapping("/{id}/link-quotation")
    public EnquiryDtos.EnquiryRow linkQuotation(
            @PathVariable long id,
            @RequestBody EnquiryDtos.LinkQuotationRequest req) {
        if (req == null || req.quotationId() <= 0) {
            throw new IllegalArgumentException("Valid quotationId is required.");
        }
        return service.linkQuotation(id, req.quotationId());
    }
}
