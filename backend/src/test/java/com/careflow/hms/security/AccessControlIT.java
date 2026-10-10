package com.careflow.hms.security;

import com.careflow.hms.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Workflow G (security): object-level access, role permissions and direct-API bypass attempts. */
class AccessControlIT extends IntegrationTestBase {

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        assertThat(mvc.perform(get("/api/patients")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/admissions/my")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void forgedOrTamperedTokensAreRejected() throws Exception {
        String t = token("ravi.sharma@demo.careflow.local");
        String tampered = t.substring(0, t.length() - 4) + "AAAA";
        int status = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered)).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    @Test
    void patientCannotReadAnotherPatientsRecordsOrEnumerate() throws Exception {
        UUID other = patientId("Lakshmi");
        String ravi = "ravi.sharma@demo.careflow.local";
        assertThat(call(ravi, "GET", "/api/patients/" + other, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(ravi, "GET", "/api/patients/" + other + "/admissions", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(ravi, "GET", "/api/patients/" + other + "/timeline", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(ravi, "GET", "/api/patients", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(ravi, "GET", "/api/patients/" + UUID.randomUUID(), null).getResponse().getStatus()).isEqualTo(404);
        // Own record is accessible.
        assertThat(call(ravi, "GET", "/api/patients/" + patientId("Ravi"), null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void caregiverSeesOnlyAuthorizedDependant() throws Exception {
        String kiran = "kiran.reddy@demo.careflow.local";
        assertThat(call(kiran, "GET", "/api/patients/" + patientId("Aarav"), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(kiran, "GET", "/api/patients/" + patientId("Ravi"), null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void deniedAccessIsAudited() throws Exception {
        Integer before = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE outcome = 'DENIED'", Integer.class);
        call("billing@careflow.local", "GET", "/api/admissions/" + openAdmissionId("Ravi") + "/timeline", null);
        call("nurse.rahul@careflow.local", "GET", "/api/admissions/" + openAdmissionId("Ravi") + "/timeline", null);
        Integer after = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE outcome = 'DENIED'", Integer.class);
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void nurseOutsideWardCannotReadClinicalTimeline() throws Exception {
        UUID ravi = openAdmissionId("Ravi"); // Medical Ward A; nurse Rahul covers Surgical B and ICU
        assertThat(call("nurse.rahul@careflow.local", "GET", "/api/admissions/" + ravi + "/timeline", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("nurse.priya@careflow.local", "GET", "/api/admissions/" + ravi + "/timeline", null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void roleEndpointsRejectUnauthorizedRolesEvenWithValidBodies() throws Exception {
        Map<String, Object> body = Map.of("patientId", patientId("Neha"), "admissionType", "ELECTIVE",
                "admissionReason", "attempted bypass", "departmentId", departmentId("GEN_MED"),
                "attendingDoctorId", userId("dr.mehta@careflow.local"), "requiresIsolation", false);
        assertThat(call("billing@careflow.local", "POST", "/api/admissions", body).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("nurse.priya@careflow.local", "POST", "/api/admissions", body).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("ravi.sharma@demo.careflow.local", "POST", "/api/admissions", body).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("superadmin@careflow.local", "GET", "/api/admissions/my", null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void cookieAuthenticatedWritesRequireTheClientHeader() throws Exception {
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@careflow.local\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        var cookies = login.getResponse().getCookies();
        int withoutHeader = mvc.perform(post("/api/patients").cookie(cookies).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
        assertThat(withoutHeader).isEqualTo(403);
        int withHeader = mvc.perform(post("/api/patients").cookie(cookies).header(CsrfHeaderFilter.HEADER, "web")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
        assertThat(withHeader).isEqualTo(400); // passes CSRF check, then fails validation
    }

    @Test
    void administratorHasNoClinicalAccessByDefault() throws Exception {
        String admin = "admin@careflow.local";
        UUID ravi = openAdmissionId("Ravi");
        var detail = readJson(call(admin, "GET", "/api/admissions/" + ravi, null));
        assertThat(detail.get("clinicalAccess").asBoolean()).isFalse();
        assertThat(detail.get("summary").has("allergies")).isFalse();
    }
}
