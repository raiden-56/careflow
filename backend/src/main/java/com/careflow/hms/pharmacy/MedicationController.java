package com.careflow.hms.pharmacy;

import com.careflow.hms.common.PageResponse;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.pharmacy.PharmacyDtos.Availability;
import com.careflow.hms.pharmacy.PharmacyDtos.MedicationRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.MedicationView;
import com.careflow.hms.pharmacy.PharmacyDtos.ReasonRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Medication catalogue")
@RestController
@RequestMapping("/api/medications")
@RequiredArgsConstructor
public class MedicationController {

    private final MedicationCatalogueService catalogue;

    @Operation(summary = "Search the catalogue with live stock availability (prescribers and pharmacy)")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('PRESCRIBE','DISPENSE','MEDICATION_CATALOGUE_MANAGE','INVENTORY_MANAGE')")
    public PageResponse<MedicationView> search(@RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "false") boolean activeOnly,
                                               @RequestParam(required = false) Boolean active,
                                               @RequestParam(required = false) Medication.Form form,
                                               @RequestParam(required = false) String drugClass,
                                               @RequestParam(required = false) Boolean highAlert,
                                               @RequestParam(required = false) Boolean controlled,
                                               @RequestParam(required = false) Availability availability,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String sort) {
        return catalogue.search(q, activeOnly ? Boolean.TRUE : active, form, drugClass, highAlert, controlled,
                availability, page, size, sort);
    }

    @GetMapping("/classes")
    @PreAuthorize("hasAnyAuthority('PRESCRIBE','DISPENSE','MEDICATION_CATALOGUE_MANAGE','INVENTORY_MANAGE')")
    public List<String> drugClasses() {
        return catalogue.drugClasses();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PRESCRIBE','DISPENSE','MEDICATION_CATALOGUE_MANAGE','INVENTORY_MANAGE')")
    public MedicationView get(@PathVariable UUID id) {
        return catalogue.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MEDICATION_CATALOGUE_MANAGE')")
    public MedicationView create(@Valid @RequestBody MedicationRequest body) {
        return catalogue.create(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MEDICATION_CATALOGUE_MANAGE')")
    public MedicationView update(@PathVariable UUID id, @Valid @RequestBody MedicationRequest body) {
        return catalogue.update(id, body);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('MEDICATION_CATALOGUE_MANAGE')")
    public MedicationView deactivate(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return catalogue.setActive(id, false, body.reason());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('MEDICATION_CATALOGUE_MANAGE')")
    public MedicationView activate(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return catalogue.setActive(id, true, body.reason());
    }
}
