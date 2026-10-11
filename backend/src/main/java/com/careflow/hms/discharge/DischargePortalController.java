package com.careflow.hms.discharge;

import com.careflow.hms.discharge.DischargeDtos.AcknowledgeRequest;
import com.careflow.hms.discharge.DischargeDtos.PortalDischargeEnvelope;
import com.careflow.hms.discharge.DischargeDtos.PortalFollowUps;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Patient portal - discharge and follow-up")
@RestController
@RequestMapping("/api/portal/patients/{patientId}")
@RequiredArgsConstructor
public class DischargePortalController {

    private final DischargePortalService portal;

    @Operation(summary = "Discharge progress in plain language, released summary, take-home medicines and warning signs")
    @GetMapping("/discharge")
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public PortalDischargeEnvelope discharge(@PathVariable UUID patientId) {
        return portal.discharge(patientId);
    }

    @Operation(summary = "Record that the patient / caregiver understood the discharge instructions")
    @PostMapping("/discharge/acknowledge")
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public PortalDischargeEnvelope acknowledge(@PathVariable UUID patientId, @Valid @RequestBody AcknowledgeRequest body) {
        return portal.acknowledge(patientId, body);
    }

    @GetMapping("/follow-ups")
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public PortalFollowUps followUps(@PathVariable UUID patientId) {
        return portal.followUps(patientId);
    }
}
