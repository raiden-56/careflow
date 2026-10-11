package com.careflow.hms.billing;

import com.careflow.hms.billing.BillingDtos.ClaimApproveRequest;
import com.careflow.hms.billing.BillingDtos.ClaimRequest;
import com.careflow.hms.billing.BillingDtos.ClaimSettleRequest;
import com.careflow.hms.billing.BillingDtos.ClaimView;
import com.careflow.hms.billing.BillingDtos.ReasonRequest;
import com.careflow.hms.billing.BillingDtos.RequestView;
import com.careflow.hms.billing.BillingDtos.RespondRequest;
import com.careflow.hms.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@Tag(name = "Billing - insurance claims and patient billing queries")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService claims;
    private final BillingRequestService requests;

    @PostMapping("/invoices/{id}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    public ClaimView create(@PathVariable UUID id, @Valid @RequestBody ClaimRequest body) {
        return claims.create(id, body);
    }

    @GetMapping("/claims")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public PageResponse<ClaimView> list(@RequestParam(required = false) Set<InsuranceClaim.Status> status,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(required = false) String sort) {
        return claims.search(status, q, page, size, sort);
    }

    @GetMapping("/claims/{id}")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public ClaimView get(@PathVariable UUID id) {
        return claims.get(id);
    }

    @PostMapping("/claims/{id}/submit")
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    public ClaimView submit(@PathVariable UUID id) {
        return claims.submit(id);
    }

    @PostMapping("/claims/{id}/review")
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    public ClaimView review(@PathVariable UUID id) {
        return claims.markUnderReview(id);
    }

    @Operation(summary = "Approve fully or partially (approved amount must not exceed the claimed amount)")
    @PostMapping("/claims/{id}/approve")
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    public ClaimView approve(@PathVariable UUID id, @Valid @RequestBody ClaimApproveRequest body) {
        return claims.approve(id, body);
    }

    @PostMapping("/claims/{id}/reject")
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    public ClaimView reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return claims.reject(id, body.reason());
    }

    @Operation(summary = "Record a (partial) settlement: INSURANCE_SETTLEMENT ledger entry with a unique reference")
    @PostMapping("/claims/{id}/settle")
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    public ClaimView settle(@PathVariable UUID id, @Valid @RequestBody ClaimSettleRequest body) {
        return claims.settle(id, body);
    }

    // ------------------------------------------------------------------ patient billing queries (staff side)

    @GetMapping("/billing-requests")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public PageResponse<RequestView> requests(@RequestParam(required = false) Set<BillingRequest.Status> status,
                                              @RequestParam(required = false) BillingRequest.RequestType type,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String sort) {
        return requests.search(status, type, q, page, size, sort);
    }

    @PostMapping("/billing-requests/{id}/respond")
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public RequestView respond(@PathVariable UUID id, @Valid @RequestBody RespondRequest body) {
        return requests.respond(id, body);
    }
}
