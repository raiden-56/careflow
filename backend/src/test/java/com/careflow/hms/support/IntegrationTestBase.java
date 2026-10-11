package com.careflow.hms.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Full-stack integration tests against a real PostgreSQL (Testcontainers) seeded with the
 * fictional demo hospital. The Spring context (and seeded database) is shared by all test
 * classes, so tests must create their own records or tolerate data created by other tests.
 *
 * Demo accounts: admin@careflow.local, dr.mehta@careflow.local, dr.iyer@careflow.local,
 * dr.khan@careflow.local, nurse.priya@careflow.local, nurse.rahul@careflow.local,
 * billing@careflow.local, pharmacy@careflow.local, ravi.sharma@demo.careflow.local,
 * sunita.sharma@demo.careflow.local, kiran.reddy@demo.careflow.local, superadmin@careflow.local.
 */
@SpringBootTest(properties = {
        "careflow.demo.enabled=true",
        "careflow.dev.outbox-enabled=true",
        "careflow.security.login.rate-limit-per-minute=10000",
        "careflow.security.login.max-failed-attempts=1000"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTestBase {

    public static final String PASSWORD = "CareFlow#Demo2026";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("careflow_test")
            .withUsername("careflow")
            .withPassword("careflow");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    /** Bearer token for a demo user (cached per JVM). */
    protected String token(String email) {
        return TOKENS.computeIfAbsent(email, e -> {
            try {
                MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", e, "password", PASSWORD)))).andReturn();
                if (r.getResponse().getStatus() != 200) {
                    throw new IllegalStateException("Login failed for " + e + ": " + r.getResponse().getContentAsString());
                }
                return json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    protected MockHttpServletRequestBuilder as(String email, MockHttpServletRequestBuilder req) {
        return req.header("Authorization", "Bearer " + token(email)).contentType(MediaType.APPLICATION_JSON);
    }

    protected MvcResult call(String email, String method, String url, Object body) throws Exception {
        MockHttpServletRequestBuilder req = switch (method) {
            case "GET" -> get(url);
            case "POST" -> post(url);
            case "PUT" -> put(url);
            case "PATCH" -> patch(url);
            case "DELETE" -> delete(url);
            default -> throw new IllegalArgumentException(method);
        };
        req = as(email, req);
        if (body != null) {
            req = req.content(json.writeValueAsString(body));
        }
        return mvc.perform(req).andReturn();
    }

    protected JsonNode readJson(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isEmpty() ? json.nullNode() : json.readTree(s);
    }

    /** id of a seeded patient by first name (demo data is fictional and stable). */
    protected UUID patientId(String firstName) {
        return jdbc.queryForObject("SELECT id FROM patients WHERE first_name = ? LIMIT 1", UUID.class, firstName);
    }

    /** id of the open admission of a seeded patient. */
    protected UUID openAdmissionId(String firstName) {
        return jdbc.queryForObject("""
                SELECT a.id FROM admissions a JOIN patients p ON p.id = a.patient_id
                WHERE p.first_name = ? AND a.status IN ('PENDING_BED','ADMITTED','DISCHARGE_IN_PROGRESS')
                """, UUID.class, firstName);
    }

    protected UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE lower(email) = lower(?)", UUID.class, email);
    }

    protected UUID departmentId(String code) {
        return jdbc.queryForObject("SELECT id FROM departments WHERE code = ?", UUID.class, code);
    }

    /**
     * An AVAILABLE bed in a ward (by ward code). Test classes share one seeded database and keep admitting
     * patients, so when the ward is full a test bed is added alongside an existing one (same room and bed type)
     * rather than making later tests depend on execution order.
     */
    protected UUID availableBed(String wardCode) {
        List<UUID> free = jdbc.queryForList("""
                SELECT b.id FROM beds b JOIN wards w ON w.id = b.ward_id
                WHERE w.code = ? AND b.status = 'AVAILABLE' AND b.active ORDER BY b.bed_number LIMIT 1
                """, UUID.class, wardCode);
        if (!free.isEmpty()) {
            return free.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO beds (organization_id, ward_id, room_id, bed_number, bed_type, isolation_capable, oxygen_supported)
                SELECT b.organization_id, b.ward_id, b.room_id, 'T' || substr(md5(random()::text), 1, 8), b.bed_type,
                       b.isolation_capable, b.oxygen_supported
                FROM beds b JOIN wards w ON w.id = b.ward_id
                WHERE w.code = ? AND b.active ORDER BY b.bed_number LIMIT 1
                RETURNING id
                """, UUID.class, wardCode);
    }

    /** Registers a fresh patient through the API and returns its id. */
    protected UUID registerPatient(String first, String last) throws Exception {
        MvcResult r = call("admin@careflow.local", "POST", "/api/patients", Map.of(
                "firstName", first, "lastName", last, "dateOfBirth", "1980-01-15", "gender", "FEMALE",
                "phone", "+91 90000 " + (10000 + (int) (Math.random() * 89999)), "confirmNotDuplicate", true));
        if (r.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Register failed: " + r.getResponse().getContentAsString());
        }
        return UUID.fromString(readJson(r).get("id").asText());
    }
}
