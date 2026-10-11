package com.careflow.hms.document;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Secure document upload, release to the patient portal and signed, audited downloads. */
class DocumentIT extends IntegrationTestBase {

    private static final String DOCTOR = "dr.mehta@careflow.local";
    private static final String RAVI = "ravi.sharma@demo.careflow.local";
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    @Autowired
    private DownloadTokenService tokens;

    @Test
    void uploadRejectsDisallowedTypesMismatchedContentAndOversizeFiles() throws Exception {
        UUID ravi = patientId("Ravi");
        assertThat(upload(DOCTOR, ravi, "notes.txt", "text/plain", "plain text".getBytes()).getResponse().getStatus()).isEqualTo(415);
        assertThat(upload(DOCTOR, ravi, "fake.pdf", "application/pdf", "MZ\u0090\u0000 executable".getBytes()).getResponse().getStatus()).isEqualTo(415);
        assertThat(upload(DOCTOR, ravi, "image.jpg", "image/jpeg", PNG).getResponse().getStatus()).isEqualTo(415);
        assertThat(upload(DOCTOR, ravi, "page.html", "text/html", "<html><script>alert(1)</script></html>".getBytes()).getResponse().getStatus()).isEqualTo(415);
        assertThat(upload(DOCTOR, ravi, "empty.pdf", "application/pdf", new byte[0]).getResponse().getStatus()).isEqualTo(400);

        byte[] big = new byte[10 * 1024 * 1024 + 1];
        Arrays.fill(big, (byte) ' ');
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        assertThat(upload(DOCTOR, ravi, "big.pdf", "application/pdf", big).getResponse().getStatus()).isEqualTo(413);

        // Roles without clinical access to the patient, and the patient themselves, cannot upload.
        assertThat(upload("admin@careflow.local", ravi, "a.pdf", "application/pdf", PDF).getResponse().getStatus()).isEqualTo(403);
        assertThat(upload(RAVI, ravi, "a.pdf", "application/pdf", PDF).getResponse().getStatus()).isEqualTo(403);
        assertThat(upload("superadmin@careflow.local", ravi, "a.pdf", "application/pdf", PDF).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void validUploadIsStoredWithSanitizedNameAndHash() throws Exception {
        MvcResult r = upload(DOCTOR, patientId("Ravi"), "../../etc/<scan>\u0007 result.PDF", "application/pdf", PDF);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode d = readJson(r);
        assertThat(d.get("fileName").asText()).doesNotContain("/").doesNotContain("..").doesNotContain("<").endsWith(".pdf");
        assertThat(d.get("sha256").asText()).hasSize(64);
        assertThat(d.get("releasedToPatient").asBoolean()).isFalse();
        String key = jdbc.queryForObject("SELECT storage_key FROM documents WHERE id = ?", String.class,
                UUID.fromString(d.get("id").asText()));
        assertThat(key).doesNotContain("etc").doesNotContain("scan");
    }

    @Test
    void patientSeesOnlyReleasedDocumentsAndDownloadsAreAudited() throws Exception {
        UUID ravi = patientId("Ravi");
        JsonNode doc = readJson(upload(DOCTOR, ravi, "cbc.pdf", "application/pdf", PDF));
        String id = doc.get("id").asText();

        // Not released yet: invisible to the patient, and no link can be issued.
        assertThat(call(RAVI, "GET", "/api/portal/patients/" + ravi + "/documents", null).getResponse().getContentAsString()).doesNotContain(id);
        assertThat(call(RAVI, "POST", "/api/documents/" + id + "/download-token", null).getResponse().getStatus()).isEqualTo(404);
        // Patients can never use the staff listing.
        assertThat(call(RAVI, "GET", "/api/documents?patientId=" + ravi, null).getResponse().getStatus()).isEqualTo(403);

        assertThat(call(DOCTOR, "POST", "/api/documents/" + id + "/release", null).getResponse().getStatus()).isEqualTo(200);
        JsonNode portal = readJson(call(RAVI, "GET", "/api/portal/patients/" + ravi + "/documents", null));
        assertThat(portal.toString()).contains(id);
        portal.forEach(p -> assertThat(p.get("releasedToPatient").asBoolean()).isTrue());

        // Another patient's documents are not found.
        assertThat(call(RAVI, "GET", "/api/portal/patients/" + patientId("Lakshmi") + "/documents", null).getResponse().getStatus()).isEqualTo(404);

        JsonNode link = readJson(call(RAVI, "POST", "/api/documents/" + id + "/download-token", null));
        String url = link.get("url").asText();
        assertThat(url).startsWith("/api/documents/download/");
        int before = count("DOCUMENT_DOWNLOADED", id);
        MvcResult file = mvc.perform(get(url)).andReturn(); // no session: the signed token is the authorization
        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(PDF);
        assertThat(file.getResponse().getHeader("Content-Disposition")).contains("attachment");
        assertThat(count("DOCUMENT_DOWNLOADED", id)).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_events WHERE action = 'DOCUMENT_DOWNLOADED' AND entity_id = ? "
                + "ORDER BY occurred_at DESC LIMIT 1", UUID.class, UUID.fromString(id))).isEqualTo(userId(RAVI));

        // Archiving withdraws the document: hidden from the portal and the outstanding link stops working.
        assertThat(call(DOCTOR, "POST", "/api/documents/" + id + "/archive", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(RAVI, "GET", "/api/portal/patients/" + ravi + "/documents", null).getResponse().getContentAsString()).doesNotContain(id);
        assertThat(mvc.perform(get(url)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE id = ?", Integer.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void tamperedAndExpiredLinksAreRejectedAndAudited() throws Exception {
        JsonNode doc = readJson(upload(DOCTOR, patientId("Ravi"), "xray.png", "image/png", PNG));
        String id = doc.get("id").asText();
        String url = readJson(call(DOCTOR, "POST", "/api/documents/" + id + "/download-token", null)).get("url").asText();
        assertThat(mvc.perform(get(url)).andReturn().getResponse().getStatus()).isEqualTo(200);

        String token = url.substring(url.lastIndexOf('/') + 1);
        String payload = token.substring(0, token.indexOf('.'));
        String signature = token.substring(token.indexOf('.') + 1);
        int rejectedBefore = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'DOCUMENT_DOWNLOAD_REJECTED'", Integer.class);

        // Signature altered.
        char c = signature.charAt(5);
        String flipped = signature.substring(0, 5) + (c == 'A' ? 'B' : 'A') + signature.substring(6);
        assertThat(mvc.perform(get("/api/documents/download/" + payload + "." + flipped)).andReturn().getResponse().getStatus()).isEqualTo(403);
        // Payload pointing at another document with the original signature.
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
                        .replace(id, UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8));
        assertThat(mvc.perform(get("/api/documents/download/" + forged + "." + signature)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/api/documents/download/garbage")).andReturn().getResponse().getStatus()).isEqualTo(403);

        // Correctly signed but expired.
        UUID org = jdbc.queryForObject("SELECT organization_id FROM documents WHERE id = ?", UUID.class, UUID.fromString(id));
        String expired = tokens.issue(UUID.fromString(id), org, userId(DOCTOR), false, Duration.ofSeconds(-5)).token();
        MvcResult gone = mvc.perform(get("/api/documents/download/" + expired)).andReturn();
        assertThat(gone.getResponse().getStatus()).isEqualTo(410);

        int rejectedAfter = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'DOCUMENT_DOWNLOAD_REJECTED'", Integer.class);
        assertThat(rejectedAfter).isGreaterThanOrEqualTo(rejectedBefore + 4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'DOCUMENT_DOWNLOAD_REJECTED' AND entity_id = ? "
                + "AND summary ILIKE '%expired%'", Integer.class, UUID.fromString(id))).isEqualTo(1);
    }

    private int count(String action, String entityId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ? AND entity_id = ?", Integer.class,
                action, UUID.fromString(entityId));
    }

    private MvcResult upload(String email, UUID patientId, String fileName, String contentType, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/documents")
                .file(new MockMultipartFile("file", fileName, contentType, content))
                .param("patientId", patientId.toString())
                .param("documentType", "LAB_REPORT")
                .param("title", "Integration test document")
                .header("Authorization", "Bearer " + token(email))).andReturn();
    }
}
