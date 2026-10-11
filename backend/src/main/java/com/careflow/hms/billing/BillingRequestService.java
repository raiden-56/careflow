package com.careflow.hms.billing;

import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.BillingDtos.RaiseRequest;
import com.careflow.hms.billing.BillingDtos.RequestView;
import com.careflow.hms.billing.BillingDtos.RespondRequest;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Patient billing queries and disputes: raised from the portal, answered by billing staff. */
@Service
@RequiredArgsConstructor
public class BillingRequestService {

    private static final Map<String, String> SORTS = Map.of("raisedAt", "raisedAt", "status", "status", "respondedAt", "respondedAt");

    private final BillingRequestRepository requests;
    private final InvoiceLineItemRepository lines;
    private final BillingSupport support;
    private final BillingViews views;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    /** Patient / caregiver raises a query on their own invoice (optionally about one line item). */
    @Transactional
    public RequestView raise(UUID invoiceId, RaiseRequest r) {
        Invoice inv = support.requireInvoice(invoiceId);
        BillingSupport.requireNotCancelled(inv);
        if (r.lineItemId() != null) {
            lines.findById(r.lineItemId()).filter(l -> l.getInvoiceId().equals(inv.getId()))
                    .orElseThrow(() -> ApiException.badRequest("INVALID_LINE_ITEM", "The selected charge is not on this invoice."));
        }
        BillingRequest b = new BillingRequest();
        b.setInvoiceId(inv.getId());
        b.setPatientId(inv.getPatientId());
        b.setLineItemId(r.lineItemId());
        b.setRequestType(r.requestType());
        b.setSubject(r.subject().trim());
        b.setDescription(r.description().trim());
        b.setRaisedBy(TenantContext.actorId());
        b.setRaisedAt(BillingClock.now());
        b = requests.saveAndFlush(b);
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("BILLING_QUERY_RAISED").category(TimelineEvent.Category.BILLING)
                .title(BillingSupport.label(b.getRequestType()) + " raised: " + b.getSubject())
                .status("OPEN").visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("BILLING_REQUEST").entityId(b.getId()).occurredAt(BillingClock.now()).build());
        notifications.notifyRole(Role.BILLING, NotificationRequest.builder()
                .category("BILLING").priority(r.requestType() == BillingRequest.RequestType.DISPUTE ? Notification.Priority.HIGH : Notification.Priority.NORMAL)
                .title("New billing " + r.requestType().name().toLowerCase().replace('_', ' ') + " on " + inv.getInvoiceNumber())
                .message(b.getSubject()).link("/billing/requests")
                .entityType("BILLING_REQUEST").entityId(b.getId()).dedupeKey("BILLING_REQUEST:" + b.getId()).build());
        audit.record("BILLING_REQUEST_RAISED", "BILLING_REQUEST", b.getId(), inv.getPatientId(), b.getRequestType() + ": " + b.getSubject());
        return views.requestViews(List.of(b)).get(0);
    }

    /** Billing staff move a request to IN_REVIEW, or close it as RESOLVED / REJECTED with a response. */
    @Transactional
    public RequestView respond(UUID requestId, RespondRequest r) {
        BillingRequest b = requests.findByIdAndOrganizationId(requestId, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Billing request"));
        Invoice inv = support.requireInvoice(b.getInvoiceId());
        if (b.getStatus() == BillingRequest.Status.RESOLVED || b.getStatus() == BillingRequest.Status.REJECTED) {
            throw ApiException.rule("REQUEST_CLOSED", "This request is already closed.");
        }
        if (r.status() == BillingRequest.Status.OPEN) {
            throw ApiException.badRequest("INVALID_STATUS", "Choose in review, resolved or rejected.");
        }
        boolean closing = r.status() == BillingRequest.Status.RESOLVED || r.status() == BillingRequest.Status.REJECTED;
        if (closing && (r.response() == null || r.response().trim().length() < 3)) {
            throw ApiException.badRequest("RESPONSE_REQUIRED", "Write a response for the patient before closing the request.");
        }
        b.setStatus(r.status());
        if (r.response() != null && !r.response().isBlank()) {
            b.setResponse(r.response().trim());
        }
        b.setRespondedBy(TenantContext.actorId());
        b.setRespondedAt(BillingClock.now());
        requests.saveAndFlush(b);
        String statusLabel = switch (r.status()) {
            case IN_REVIEW -> "is being reviewed";
            case RESOLVED -> "has been resolved";
            case REJECTED -> "has been answered (not upheld)";
            default -> "was updated";
        };
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("BILLING_QUERY_" + r.status().name()).category(TimelineEvent.Category.BILLING)
                .title("Your billing query " + statusLabel).detail(b.getSubject())
                .status(r.status().name()).visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("BILLING_REQUEST").entityId(b.getId()).occurredAt(BillingClock.now()).build());
        notifications.notifyPatient(inv.getPatientId(), NotificationRequest.builder()
                .category("BILLING").title("Billing query update")
                .message("\"" + b.getSubject() + "\" " + statusLabel + "." + (b.getResponse() == null ? "" : " " + b.getResponse()))
                .link("/patient/bills/" + inv.getId()).entityType("BILLING_REQUEST").entityId(b.getId())
                .dedupeKey("BILLING_REQUEST_" + r.status() + ":" + b.getId()).build());
        audit.record("BILLING_REQUEST_" + r.status().name(), "BILLING_REQUEST", b.getId(), inv.getPatientId(),
                b.getSubject() + (b.getResponse() == null ? "" : " | " + b.getResponse()));
        return views.requestViews(List.of(b)).get(0);
    }

    @Transactional(readOnly = true)
    public PageResponse<RequestView> search(Collection<BillingRequest.Status> statuses, BillingRequest.RequestType type,
                                            String q, int page, int size, String sort) {
        var pageable = Paging.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "raisedAt"));
        Page<BillingRequest> result = requests.search(TenantContext.requireOrganizationId(),
                statuses == null || statuses.isEmpty() ? EnumSet.allOf(BillingRequest.Status.class) : statuses,
                type, Paging.likePattern(q), pageable);
        return new PageResponse<>(views.requestViews(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
