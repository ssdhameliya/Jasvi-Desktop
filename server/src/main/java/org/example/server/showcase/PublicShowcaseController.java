package org.example.server.showcase;

import jakarta.servlet.http.HttpServletRequest;
import org.example.server.authority.ServerResourceService;
import org.example.server.enquiry.EnquiryDtos;
import org.example.server.enquiry.EnquiryService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/public/showcase")
public class PublicShowcaseController {
    private final ShowcaseService showcaseService;
    private final EnquiryService enquiryService;

    public PublicShowcaseController(ShowcaseService showcaseService, EnquiryService enquiryService) {
        this.showcaseService = showcaseService;
        this.enquiryService = enquiryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getShowcase() {
        String json = showcaseService.getShowcaseJson();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS))
                .body(json);
    }

    @GetMapping(value = "/media/{key}")
    public ResponseEntity<byte[]> getMedia(@PathVariable String key) {
        try {
            ServerResourceService.ResourceFile file = showcaseService.getMedia(key);
            MediaType mediaType = MediaType.parseMediaType(file.contentType());
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                    .header("X-Resource-SHA256", file.checksum())
                    .body(file.content());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping(value = "/rfq", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EnquiryDtos.PublicSubmitResponse> submitRfq(
            @RequestBody EnquiryDtos.PublicRfqRequest rfqPayload,
            HttpServletRequest request) {
        return ResponseEntity.ok(enquiryService.submitPublicRfq(rfqPayload, clientIp(request), userAgent(request)));
    }

    @PostMapping(value = "/feedback", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EnquiryDtos.PublicSubmitResponse> submitFeedback(
            @RequestBody EnquiryDtos.PublicFeedbackRequest feedbackPayload,
            HttpServletRequest request) {
        return ResponseEntity.ok(enquiryService.submitPublicFeedback(feedbackPayload, clientIp(request), userAgent(request)));
    }

    private static String clientIp(HttpServletRequest req) {
        if (req == null) return "";
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr() == null ? "" : req.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest req) {
        if (req == null) return "";
        String ua = req.getHeader("User-Agent");
        return ua == null ? "" : ua;
    }
}
