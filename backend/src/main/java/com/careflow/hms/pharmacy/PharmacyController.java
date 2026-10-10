package com.careflow.hms.pharmacy;

import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.pharmacy.PharmacyDtos.AdjustRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.AlertsView;
import com.careflow.hms.pharmacy.PharmacyDtos.Availability;
import com.careflow.hms.pharmacy.PharmacyDtos.BatchDetail;
import com.careflow.hms.pharmacy.PharmacyDtos.BatchView;
import com.careflow.hms.pharmacy.PharmacyDtos.DispenseRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.DispensationDetail;
import com.careflow.hms.pharmacy.PharmacyDtos.DispensationView;
import com.careflow.hms.pharmacy.PharmacyDtos.MovementView;
import com.careflow.hms.pharmacy.PharmacyDtos.OrderDetail;
import com.careflow.hms.pharmacy.PharmacyDtos.OrderQueryView;
import com.careflow.hms.pharmacy.PharmacyDtos.PharmacyDashboard;
import com.careflow.hms.pharmacy.PharmacyDtos.QueryRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.QueueFilter;
import com.careflow.hms.pharmacy.PharmacyDtos.QueueItem;
import com.careflow.hms.pharmacy.PharmacyDtos.ReasonRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.ReceiveStockRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.ResolveQueryRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.ReturnRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.StockLevel;
import com.careflow.hms.pharmacy.PharmacyDtos.VerifyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Tag(name = "Pharmacy: inventory, prescription review and dispensing")
@RestController
@RequiredArgsConstructor
public class PharmacyController {

    private static final String READ = "hasAnyAuthority('INVENTORY_MANAGE','DISPENSE')";

    private final InventoryService inventory;
    private final PrescriptionQueueService queue;
    private final DispensingService dispensing;
    private final PharmacyDashboardService dashboard;

    // ------------------------------------------------------------------ dashboard & alerts

    @GetMapping("/api/dashboard/pharmacy")
    @PreAuthorize(READ)
    public PharmacyDashboard dashboard(@RequestParam(defaultValue = "90") int nearExpiryDays) {
        return dashboard.dashboard(days(nearExpiryDays));
    }

    @Operation(summary = "Low stock, near-expiry, expired-on-hand and quarantined stock")
    @GetMapping("/api/pharmacy/alerts")
    @PreAuthorize(READ)
    public AlertsView alerts(@RequestParam(defaultValue = "90") int nearExpiryDays) {
        return inventory.alerts(days(nearExpiryDays));
    }

    // ------------------------------------------------------------------ inventory

    @Operation(summary = "Stock position per medication (usable = active, unexpired batches)")
    @GetMapping("/api/pharmacy/stock")
    @PreAuthorize(READ)
    public PageResponse<StockLevel> stock(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) Availability availability,
                                          @RequestParam(required = false) Boolean active,
                                          @RequestParam(defaultValue = "90") int nearExpiryDays,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) String sort) {
        return inventory.stockLevelPage(q, availability, active, days(nearExpiryDays), page, size, sort);
    }

    @GetMapping("/api/pharmacy/stock/export")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public ResponseEntity<byte[]> exportStock(@RequestParam(defaultValue = "90") int nearExpiryDays) {
        byte[] body = inventory.stockCsv(days(nearExpiryDays)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"stock-report-" + PharmacyClock.today() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @PostMapping("/api/pharmacy/batches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public BatchView receive(@Valid @RequestBody ReceiveStockRequest body) {
        return inventory.receive(body);
    }

    @GetMapping("/api/pharmacy/batches")
    @PreAuthorize(READ)
    public PageResponse<BatchView> batches(@RequestParam(required = false) UUID medicationId,
                                           @RequestParam(required = false) Set<InventoryBatch.Status> status,
                                           @RequestParam(required = false) Integer expiringWithinDays,
                                           @RequestParam(defaultValue = "false") boolean includeEmpty,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(required = false) String sort) {
        return inventory.searchBatches(medicationId, status, expiringWithinDays, includeEmpty, q, page, size, sort);
    }

    @GetMapping("/api/pharmacy/batches/{id}")
    @PreAuthorize(READ)
    public BatchDetail batch(@PathVariable UUID id) {
        return inventory.batchDetail(id);
    }

    @PostMapping("/api/pharmacy/batches/{id}/quarantine")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public BatchView quarantine(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return inventory.quarantine(id, body.reason().trim());
    }

    @PostMapping("/api/pharmacy/batches/{id}/release")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public BatchView release(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return inventory.release(id, body.reason().trim());
    }

    @PostMapping("/api/pharmacy/batches/{id}/write-off")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public BatchView writeOff(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return inventory.writeOff(id, body.reason().trim());
    }

    @PostMapping("/api/pharmacy/batches/{id}/adjust")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public BatchView adjust(@PathVariable UUID id, @Valid @RequestBody AdjustRequest body) {
        return inventory.adjust(id, body);
    }

    @Operation(summary = "Append-only stock movement ledger")
    @GetMapping("/api/pharmacy/movements")
    @PreAuthorize(READ)
    public PageResponse<MovementView> movements(@RequestParam(required = false) UUID batchId,
                                                @RequestParam(required = false) UUID medicationId,
                                                @RequestParam(required = false) Set<StockMovement.MovementType> type,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return inventory.movements(batchId, medicationId, type, from, to, page, size);
    }

    // ------------------------------------------------------------------ prescription review

    @Operation(summary = "Prescription queue: active orders needing verification or supply")
    @GetMapping("/api/pharmacy/queue")
    @PreAuthorize("hasAuthority('DISPENSE')")
    public PageResponse<QueueItem> queue(@RequestParam(required = false) QueueFilter filter,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) UUID admissionId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return queue.queue(filter, q, admissionId, page, size);
    }

    @GetMapping("/api/pharmacy/orders/{orderId}")
    @PreAuthorize("hasAuthority('DISPENSE')")
    public OrderDetail order(@PathVariable UUID orderId) {
        return queue.detail(orderId);
    }

    @PostMapping("/api/pharmacy/orders/{orderId}/verify")
    @PreAuthorize("hasAuthority('DISPENSE')")
    public QueueItem verify(@PathVariable UUID orderId, @Valid @RequestBody VerifyRequest body) {
        return queue.verify(orderId, body);
    }

    @PostMapping("/api/pharmacy/orders/{orderId}/queries")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DISPENSE')")
    public OrderQueryView raiseQuery(@PathVariable UUID orderId, @Valid @RequestBody QueryRequest body) {
        return queue.raiseQuery(orderId, body.message());
    }

    @PostMapping("/api/pharmacy/queries/{queryId}/resolve")
    @PreAuthorize("hasAuthority('DISPENSE')")
    public OrderQueryView resolveQuery(@PathVariable UUID queryId, @Valid @RequestBody ResolveQueryRequest body) {
        return queue.resolveQuery(queryId, body.resolution());
    }

    // ------------------------------------------------------------------ dispensing

    @PostMapping("/api/pharmacy/dispensations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DISPENSE')")
    public DispensationView dispense(@Valid @RequestBody DispenseRequest body) {
        return dispensing.dispense(body);
    }

    @GetMapping("/api/pharmacy/dispensations")
    @PreAuthorize("hasAuthority('DISPENSE')")
    public PageResponse<DispensationView> history(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(required = false) UUID patientId,
                                                  @RequestParam(required = false) UUID medicationId,
                                                  @RequestParam(required = false) UUID admissionId,
                                                  @RequestParam(required = false) Set<Dispensation.Status> status,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String sort) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("INVALID_RANGE", "The end date must not be before the start date.");
        }
        return dispensing.history(from, to, patientId, medicationId, admissionId, status, q, page, size, sort);
    }

    @GetMapping("/api/pharmacy/dispensations/{id}")
    @PreAuthorize("hasAuthority('DISPENSE')")
    public DispensationDetail dispensation(@PathVariable UUID id) {
        return dispensing.detail(id);
    }

    @PostMapping("/api/pharmacy/dispensations/{id}/returns")
    @PreAuthorize("hasAuthority('DISPENSE')")
    public DispensationView returnStock(@PathVariable UUID id, @Valid @RequestBody ReturnRequest body) {
        return dispensing.returnStock(id, body);
    }

    private static int days(int requested) {
        if (requested < 1 || requested > 730) {
            throw ApiException.badRequest("INVALID_DAYS", "nearExpiryDays must be between 1 and 730.");
        }
        return requested;
    }
}
