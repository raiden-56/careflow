package com.careflow.hms.billing;

import com.careflow.hms.billing.BillingDtos.PortalBillCard;
import com.careflow.hms.billing.BillingDtos.PortalInvoice;
import com.careflow.hms.billing.BillingDtos.PortalPayment;
import com.careflow.hms.billing.BillingDtos.RaiseRequest;
import com.careflow.hms.billing.BillingDtos.RequestView;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Patient portal billing (own records and caregivers with FULL or BILLING_ONLY scope). Read-only except raising queries. */
@Tag(name = "Patient portal - bills and payments")
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PORTAL_ACCESS')")
public class PortalBillingController {

    private final PortalBillingService portal;
    private final BillingRequestService requests;

    @GetMapping("/patients/{patientId}/bills")
    public List<PortalBillCard> bills(@PathVariable UUID patientId) {
        return portal.bills(patientId);
    }

    @Operation(summary = "Itemized bill with a plain-language balance explanation")
    @GetMapping("/invoices/{id}")
    public PortalInvoice invoice(@PathVariable UUID id) {
        return portal.invoice(id);
    }

    @GetMapping("/patients/{patientId}/payments")
    public List<PortalPayment> payments(@PathVariable UUID patientId) {
        return portal.payments(patientId);
    }

    @Operation(summary = "Raise a billing query or dispute on an invoice (optionally about one charge)")
    @PostMapping("/invoices/{id}/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView raise(@PathVariable UUID id, @Valid @RequestBody RaiseRequest body) {
        return requests.raise(id, body);
    }
}
