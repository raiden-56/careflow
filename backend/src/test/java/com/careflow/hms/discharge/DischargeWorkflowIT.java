package com.careflow.hms.discharge;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Workflow E (coordinated discharge) and its safety rules. */
class DischargeWorkflowIT extends DischargeTestSupport {

    @Autowired
    DischargeJobs jobs;

    @Test
    void workflowE_endToEnd_discharge() throws Exception {
        UUID adm = admitFresh("Esha", "Endtoend");
        UUID patient = patientOf(adm);
        String portalEmail = "esha.e2e." + UUID.randomUUID().toString().substring(0, 6) + "@demo.careflow.local";
        assertThat(call(ADMIN, "POST", "/api/patients/" + patient + "/portal-account", Map.of("email", portalEmail))
                .getResponse().getStatus()).isIn(200, 201);
        UUID dose = addMedications(adm);
        UUID bed = jdbc.queryForObject("SELECT bed_id FROM bed_assignments WHERE admission_id = ? AND released_at IS NULL", UUID.class, adm);

        JsonNode d = initiate(DR_MEHTA, adm);
        String wf = d.get("workflow").get("id").asText();
        assertThat(d.get("admission").get("status").asText()).isEqualTo("DISCHARGE_IN_PROGRESS");
        assertThat(d.get("workflow").get("steps").size()).isEqualTo(13);
        assertThat(step(d, "DISCHARGE_PRESCRIPTIONS").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(step(d, "CLINICAL_REVIEWS").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(step(d, "CLINICAL_DECISION").get("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(d.get("workflow").get("blockers").findValuesAsText("code")).contains("CLINICAL_DECISION", "DOCUMENTATION", "FINAL_BILL");

        // One open workflow per admission.
        MvcResult dup = call(DR_MEHTA, "POST", "/api/discharge/admissions/" + adm + "/workflow", Map.of());
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);

        // Cannot complete without clinical authorization.
        Map<String, Object> complete = Map.of("actualDepartureAt", Instant.now().toString(), "disposition", "HOME");
        MvcResult early = call(NURSE_PRIYA, "POST", "/api/discharge/workflows/" + wf + "/complete", complete);
        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(early).get("code").asText()).isEqualTo("CLINICAL_AUTHORIZATION_REQUIRED");

        // Clinical authorization by the attending doctor.
        MvcResult auth = call(DR_MEHTA, "POST", "/api/discharge/workflows/" + wf + "/clinical-decision",
                Map.of("decision", "AUTHORIZE", "notes", "Afebrile, wound healing, fit for discharge."));
        assertThat(auth.getResponse().getStatus()).as(auth.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode afterAuth = readJson(auth);
        assertThat(afterAuth.get("workflow").get("clinical").get("decision").asText()).isEqualTo("AUTHORIZED");
        assertThat(afterAuth.get("workflow").get("financial").get("clearance")).isNull();
        assertThat(step(afterAuth, "CLINICAL_DECISION").get("status").asText()).isEqualTo("COMPLETED");

        // Remaining steps by their responsible roles.
        assertThat(patchStep(PHARMACIST, adm, "MED_RECONCILIATION", Map.of("status", "COMPLETED")).getResponse().getStatus()).isEqualTo(200);
        assertThat(patchStep(NURSE_PRIYA, adm, "NURSING_EDUCATION", Map.of("status", "COMPLETED", "notes", "Teach-back done")).getResponse().getStatus()).isEqualTo(200);
        MvcResult fu = call(DR_MEHTA, "POST", "/api/discharge/admissions/" + adm + "/follow-ups", Map.of(
                "type", "APPOINTMENT", "title", "Review in OPD", "scheduledAt", Instant.now().plusSeconds(7 * 86400).toString()));
        assertThat(fu.getResponse().getStatus()).as(fu.getResponse().getContentAsString()).isEqualTo(201);
        MvcResult draft = call(DR_MEHTA, "POST", "/api/discharge/summaries/" + adm, null);
        assertThat(draft.getResponse().getStatus()).isEqualTo(201);
        JsonNode sum = readJson(draft).get("summary");
        assertThat(sum.get("presentingComplaint").asText()).isEqualTo("Discharge workflow integration test");
        assertThat(sum.get("dischargeMedications").asText()).contains("Cephalexin").contains("four times a day");
        Map<String, Object> edit = new HashMap<>();
        edit.put("presentingComplaint", sum.get("presentingComplaint").asText());
        edit.put("diagnosesText", "- Test diagnosis");
        edit.put("treatmentSummary", "Treated and improved.");
        edit.put("conditionAtDischarge", "Stable");
        edit.put("dischargeInstructions", "Rest");
        edit.put("version", sum.get("version").asLong());
        MvcResult upd = call(DR_MEHTA, "PUT", "/api/discharge/summaries/" + adm, edit);
        assertThat(upd.getResponse().getStatus()).as(upd.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult fin = call(DR_MEHTA, "POST", "/api/discharge/summaries/" + adm + "/finalize", null);
        assertThat(fin.getResponse().getStatus()).as(fin.getResponse().getContentAsString()).isEqualTo(200);
        finalizeInvoice(adm);

        // Finance: balance 0 -> cleared; the clinical decision is untouched.
        MvcResult cleared = call(BILLING, "POST", "/api/discharge/workflows/" + wf + "/financial-clearance", Map.of("type", "CLEARED"));
        assertThat(cleared.getResponse().getStatus()).as(cleared.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode ready = readJson(cleared);
        assertThat(ready.get("workflow").get("financial").get("clearance").asText()).isEqualTo("CLEARED");
        assertThat(ready.get("workflow").get("clinical").get("decision").asText()).isEqualTo("AUTHORIZED");
        assertThat(ready.get("workflow").get("status").asText()).isEqualTo("READY");
        assertThat(ready.get("workflow").get("readiness").get("canComplete").asBoolean()).isTrue();

        // Complete discharge.
        MvcResult done = call(NURSE_PRIYA, "POST", "/api/discharge/workflows/" + wf + "/complete", complete);
        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode out = readJson(done);
        assertThat(out.get("workflow").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(step(out, "DEPARTURE").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(step(out, "BED_TURNOVER").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM admissions WHERE id = ?", String.class, adm)).isEqualTo("DISCHARGED");
        assertThat(jdbc.queryForObject("SELECT discharge_disposition FROM admissions WHERE id = ?", String.class, adm)).isEqualTo("HOME");
        assertThat(jdbc.queryForObject("SELECT discharged_at IS NOT NULL FROM admissions WHERE id = ?", Boolean.class, adm)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM beds WHERE id = ?", String.class, bed)).isEqualTo("CLEANING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bed_assignments WHERE admission_id = ? AND released_at IS NULL", Integer.class, adm)).isZero();
        assertThat(jdbc.queryForObject("SELECT release_reason FROM bed_assignments WHERE admission_id = ?", String.class, adm)).isEqualTo("DISCHARGE");
        assertThat(jdbc.queryForObject("SELECT status FROM medication_administrations WHERE id = ?", String.class, dose)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notifications n JOIN patients p ON p.user_id = n.recipient_user_id
                WHERE p.id = ? AND n.title = 'You have been discharged'
                """, Integer.class, patient)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timeline_events WHERE admission_id = ? AND event_type = 'PATIENT_DISCHARGED' AND visibility = 'PATIENT'",
                Integer.class, adm)).isEqualTo(1);
        // Closed workflow: no further step changes.
        assertThat(patchStep(NURSE_PRIYA, adm, "PATIENT_ACK", Map.of("status", "COMPLETED")).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void financialExceptionRequiresReasonAndSettingAndNeverAuthorizesClinically() throws Exception {
        UUID adm = admitFresh("Farah", "Finance");
        String wf = initiate(NURSE_PRIYA, adm).get("workflow").get("id").asText();
        postLedgerCharge(adm, "2500.00");
        String url = "/api/discharge/workflows/" + wf + "/financial-clearance";

        MvcResult unpaid = call(BILLING, "POST", url, Map.of("type", "CLEARED"));
        assertThat(unpaid.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(unpaid).get("code").asText()).isEqualTo("BALANCE_OUTSTANDING");

        MvcResult noReason = call(BILLING, "POST", url, Map.of("type", "EXCEPTION"));
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(readJson(noReason).get("code").asText()).isEqualTo("EXCEPTION_REASON_REQUIRED");

        UUID org = orgId();
        jdbc.update("INSERT INTO organization_settings (organization_id, setting_key, setting_value) VALUES (?, 'discharge.allowFinancialException', 'false')", org);
        try {
            MvcResult disallowed = call(BILLING, "POST", url, Map.of("type", "EXCEPTION", "reason", "Insurance settlement pending with the TPA"));
            assertThat(disallowed.getResponse().getStatus()).isEqualTo(422);
            assertThat(readJson(disallowed).get("code").asText()).isEqualTo("EXCEPTION_NOT_ALLOWED");
        } finally {
            jdbc.update("DELETE FROM organization_settings WHERE organization_id = ? AND setting_key = 'discharge.allowFinancialException'", org);
        }

        // Separation of duties: billing cannot authorize clinically, doctors cannot clear financially.
        assertThat(call(BILLING, "POST", "/api/discharge/workflows/" + wf + "/clinical-decision",
                Map.of("decision", "AUTHORIZE", "notes", "x y z")).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(DR_MEHTA, "POST", url, Map.of("type", "CLEARED")).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(NURSE_PRIYA, "POST", url, Map.of("type", "CLEARED")).getResponse().getStatus()).isEqualTo(403);

        MvcResult ok = call(BILLING, "POST", url, Map.of("type", "EXCEPTION", "reason", "Insurance settlement pending with the TPA"));
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode fin = readJson(ok).get("workflow");
        assertThat(fin.get("financial").get("clearance").asText()).isEqualTo("EXCEPTION");
        assertThat(fin.get("financial").get("balanceAtClearance").asText()).isEqualTo("2500.00");
        assertThat(fin.get("clinical").get("decision")).isNull();
        assertThat(step(readJson(ok), "FINANCIAL_CLEARANCE").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(step(readJson(ok), "CLINICAL_DECISION").get("status").asText()).isNotEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND dedupe_key = ?",
                Integer.class, userId(ADMIN), "DISCHARGE_FINANCE:" + wf + ":EXCEPTION")).isEqualTo(1);

        // Still cannot be completed: the clinical decision is a separate, explicit clinician action.
        MvcResult early = call(NURSE_PRIYA, "POST", "/api/discharge/workflows/" + wf + "/complete",
                Map.of("actualDepartureAt", Instant.now().toString(), "disposition", "HOME"));
        assertThat(readJson(early).get("code").asText()).isEqualTo("CLINICAL_AUTHORIZATION_REQUIRED");
    }

    @Test
    void autoCheckStepsCannotBeForceCompletedAndReopenWhenDataChanges() throws Exception {
        UUID adm = admitFresh("Gita", "Autocheck");
        JsonNode d = initiate(DR_MEHTA, adm);
        assertThat(step(d, "RESULTS_REVIEWED").get("status").asText()).isEqualTo("COMPLETED");

        MvcResult decision = patchStep(DR_MEHTA, adm, "CLINICAL_DECISION", Map.of("status", "COMPLETED"));
        assertThat(decision.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(decision).get("code").asText()).isEqualTo("AUTO_CHECK_UNMET");
        MvcResult docs = patchStep(DR_MEHTA, adm, "DOCUMENTATION", Map.of("status", "COMPLETED"));
        assertThat(docs.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(docs).get("message").asText()).contains("discharge summary");
        assertThat(patchStep(DR_MEHTA, adm, "DISCHARGE_PRESCRIPTIONS", Map.of("status", "COMPLETED")).getResponse().getStatus()).isEqualTo(422);

        // A new outstanding result re-opens the step; handing it to a result-review follow-up handles it.
        UUID lab = addLabOrder(adm, "RESULTED");
        JsonNode reopened = detail(DR_MEHTA, adm);
        assertThat(step(reopened, "RESULTS_REVIEWED").get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(reopened.get("workflow").get("blockers").toString()).contains("C-reactive protein");
        // Non-clinical viewers see counts, not clinical detail.
        assertThat(detail(ADMIN, adm).get("workflow").get("blockers").toString()).doesNotContain("C-reactive protein");
        MvcResult handover = call(DR_MEHTA, "POST", "/api/discharge/admissions/" + adm + "/follow-ups", Map.of(
                "type", "RESULT_REVIEW", "title", "Review CRP after discharge", "labOrderId", lab.toString()));
        assertThat(handover.getResponse().getStatus()).as(handover.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(step(detail(DR_MEHTA, adm), "RESULTS_REVIEWED").get("status").asText()).isEqualTo("COMPLETED");
        // The handed-over result shows up in the doctor's post-discharge review list.
        JsonNode pending = readJson(call(DR_MEHTA, "GET", "/api/discharge/follow-ups/pending-results", null));
        assertThat(pending.findValuesAsText("labOrderId")).contains(lab.toString());

        // Discharge medications: a clinician may record "not applicable" only with an explicit justification.
        MvcResult na = patchStep(DR_MEHTA, adm, "DISCHARGE_PRESCRIPTIONS", Map.of("status", "NOT_APPLICABLE", "notApplicableReason", "No new medicines needed"));
        assertThat(readJson(na).get("code").asText()).isEqualTo("JUSTIFICATION_REQUIRED");
        MvcResult naOk = patchStep(DR_MEHTA, adm, "DISCHARGE_PRESCRIPTIONS", Map.of("status", "NOT_APPLICABLE",
                "notApplicableReason", "No new medicines needed", "confirmRequiredNotApplicable", true));
        assertThat(naOk.getResponse().getStatus()).as(naOk.getResponse().getContentAsString()).isEqualTo(200);
        // Other automatic checks cannot be waived.
        MvcResult naAlerts = patchStep(DR_MEHTA, adm, "CLINICAL_DECISION", Map.of("status", "NOT_APPLICABLE",
                "notApplicableReason", "Skipping", "confirmRequiredNotApplicable", true));
        assertThat(readJson(naAlerts).get("code").asText()).isEqualTo("NA_NOT_ALLOWED");
    }

    @Test
    void onlyResponsibleRolesUpdateSteps() throws Exception {
        UUID adm = admitFresh("Hema", "Roles");
        initiate(NURSE_PRIYA, adm);
        assertThat(patchStep(BILLING, adm, "NURSING_EDUCATION", Map.of("status", "COMPLETED")).getResponse().getStatus()).isEqualTo(403);
        assertThat(patchStep(NURSE_PRIYA, adm, "MED_RECONCILIATION", Map.of("status", "COMPLETED")).getResponse().getStatus()).isEqualTo(403);
        assertThat(patchStep(PHARMACIST, adm, "NURSING_EDUCATION", Map.of("status", "COMPLETED")).getResponse().getStatus()).isEqualTo(403);
        // A nurse on another ward fails the object-level check.
        assertThat(patchStep(NURSE_RAHUL, adm, "NURSING_EDUCATION", Map.of("status", "COMPLETED")).getResponse().getStatus()).isEqualTo(403);
        // Administrators coordinate but never complete clinical steps.
        assertThat(patchStep(ADMIN, adm, "FOLLOW_UP", Map.of("status", "NOT_APPLICABLE", "notApplicableReason", "none needed",
                "confirmRequiredNotApplicable", true)).getResponse().getStatus()).isEqualTo(403);
        assertThat(patchStep(ADMIN, adm, "NURSING_EDUCATION", Map.of("priority", "URGENT", "nextAction", "Arrange interpreter")).getResponse().getStatus()).isEqualTo(200);

        MvcResult noReason = patchStep(NURSE_PRIYA, adm, "NURSING_EDUCATION", Map.of("status", "BLOCKED"));
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        MvcResult stale = patchStep(NURSE_PRIYA, adm, "NURSING_EDUCATION", Map.of("status", "IN_PROGRESS", "version", -1));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        MvcResult blocked = patchStep(NURSE_PRIYA, adm, "NURSING_EDUCATION", Map.of("status", "BLOCKED", "blockerReason", "Caregiver not available until tomorrow"));
        assertThat(blocked.getResponse().getStatus()).as(blocked.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode b = readJson(blocked);
        assertThat(b.get("workflow").get("blockers").get(0).get("code").asText()).isEqualTo("NURSING_EDUCATION");
        assertThat(b.get("workflow").get("blockers").get(0).get("explanation").asText()).contains("Caregiver not available");

        // Cancelling returns the admission to ADMITTED.
        String wf = b.get("workflow").get("id").asText();
        MvcResult cancel = call(DR_MEHTA, "POST", "/api/discharge/workflows/" + wf + "/cancel", Map.of("reason", "New fever spike overnight"));
        assertThat(cancel.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM admissions WHERE id = ?", String.class, adm)).isEqualTo("ADMITTED");
    }

    @Test
    void blockedWorkflowEscalationIsDeduplicated() throws Exception {
        UUID adm = admitFresh("Indu", "Escalation");
        initiate(NURSE_PRIYA, adm);
        MvcResult blocked = patchStep(NURSE_PRIYA, adm, "NURSING_EDUCATION", Map.of("status", "BLOCKED", "blockerReason", "Interpreter required"));
        assertThat(blocked.getResponse().getStatus()).isEqualTo(200);
        UUID stepId = UUID.fromString(step(readJson(blocked), "NURSING_EDUCATION").get("id").asText());
        jdbc.update("UPDATE discharge_steps SET updated_at = now() - interval '5 hours' WHERE id = ?", stepId);
        int admins = jdbc.queryForObject("SELECT count(*) FROM users WHERE role_code = 'HOSPITAL_ADMIN' AND status = 'ACTIVE' AND organization_id = ?",
                Integer.class, orgId());

        jobs.escalateBlocked(orgId());
        int first = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE dedupe_key LIKE ?", Integer.class, "DISCHARGE_BLOCKED:" + stepId + ":%");
        assertThat(first).isEqualTo(admins);
        jobs.escalateBlocked(orgId());
        jobs.escalateBlocked(orgId());
        int again = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE dedupe_key LIKE ?", Integer.class, "DISCHARGE_BLOCKED:" + stepId + ":%");
        assertThat(again).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timeline_events WHERE entity_id = ? AND event_type = 'DISCHARGE_BLOCKER_ESCALATED'",
                Integer.class, stepId)).isEqualTo(1);
    }

    @Test
    void clinicalDecisionRestrictedToTreatingDoctorsAndBoardIsRoleScoped() throws Exception {
        UUID adm = admitFresh("Jaya", "Decision");
        String wf = initiate(DR_MEHTA, adm).get("workflow").get("id").asText();
        // Dr Khan (surgery) is not on the care team.
        assertThat(call(DR_KHAN, "POST", "/api/discharge/workflows/" + wf + "/clinical-decision",
                Map.of("decision", "AUTHORIZE", "notes", "Fit")).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(DR_MEHTA, "POST", "/api/discharge/workflows/" + wf + "/clinical-decision",
                Map.of("decision", "AUTHORIZE")).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(DR_MEHTA, "POST", "/api/discharge/workflows/" + wf + "/clinical-decision",
                Map.of("decision", "AUTHORIZE", "notes", "Fit for discharge")).getResponse().getStatus()).isEqualTo(200);
        MvcResult revoke = call(DR_MEHTA, "POST", "/api/discharge/workflows/" + wf + "/clinical-decision",
                Map.of("decision", "REVOKE", "notes", "Spiked a temperature"));
        assertThat(readJson(revoke).get("workflow").get("clinical").get("decision").asText()).isEqualTo("REVOKED");
        assertThat(step(readJson(revoke), "CLINICAL_DECISION").get("status").asText()).isEqualTo("IN_PROGRESS");

        JsonNode board = readJson(call(ADMIN, "GET", "/api/discharge/workflows", null));
        assertThat(board.findValuesAsText("admissionId")).contains(adm.toString());
        // Clinical notes are hidden from administrators.
        assertThat(detail(ADMIN, adm).get("workflow").get("clinical").get("notes")).isNull();
        assertThat(detail(DR_MEHTA, adm).get("workflow").get("clinical").get("notes").asText()).isEqualTo("Spiked a temperature");
        // Billing queue shows the ledger balance; patients cannot use staff endpoints.
        JsonNode queue = readJson(call(BILLING, "GET", "/api/discharge/financial-queue?filter=PENDING", null));
        assertThat(queue.findValuesAsText("admissionId")).contains(adm.toString());
        assertThat(call(RAVI, "GET", "/api/discharge/admissions/" + adm, null).getResponse().getStatus()).isEqualTo(403);
        JsonNode report = readJson(call(ADMIN, "GET", "/api/discharge/reports/delays", null));
        assertThat(report.get("byStage").size()).isGreaterThan(0);
    }

    @Test
    void seededDeepaWorkflowIsInProgressWithSeparatePanels() throws Exception {
        JsonNode d = detail(DR_MEHTA, openAdmissionId("Deepa"));
        JsonNode w = d.get("workflow");
        assertThat(w.get("status").asText()).isIn("IN_PROGRESS", "READY");
        assertThat(w.get("clinical").get("decision").asText()).isEqualTo("AUTHORIZED");
        assertThat(w.get("financial").get("clearance")).isNull();
        assertThat(d.get("summary").get("status").asText()).isEqualTo("DRAFT");
        assertThat(d.get("followUpCount").asInt()).isGreaterThanOrEqualTo(1);
        MvcResult pdf = call(DR_MEHTA, "GET", "/api/discharge/summaries/" + openAdmissionId("Deepa") + "/summary.pdf", null);
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(pdf.getResponse().getContentAsByteArray().length).isGreaterThan(1000);
    }
}
