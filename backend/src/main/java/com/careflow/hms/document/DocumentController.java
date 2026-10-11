package com.careflow.hms.document;

import com.careflow.hms.common.ApiException;
import com.careflow.hms.document.DocumentService.DocumentView;
import com.careflow.hms.document.DocumentService.DownloadLink;
import com.careflow.hms.document.DocumentService.FileContent;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Patient documents")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DOCUMENT_UPLOAD') and !hasRole('PATIENT') and !hasRole('SUPER_ADMIN')")
    public DocumentView upload(@RequestPart("file") MultipartFile file,
                               @RequestParam UUID patientId,
                               @RequestParam(required = false) UUID admissionId,
                               @RequestParam PatientDocument.DocumentType documentType,
                               @RequestParam String title) throws IOException {
        if (file.getSize() > DocumentService.MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Files must be 10 MB or smaller.");
        }
        return service.upload(patientId, admissionId, documentType, title, file.getOriginalFilename(), file.getContentType(),
                file.getBytes());
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('CLINICAL_READ') and !hasRole('PATIENT')")
    public List<DocumentView> list(@RequestParam(required = false) UUID patientId,
                                   @RequestParam(required = false) UUID admissionId,
                                   @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(patientId, admissionId, includeArchived);
    }

    @PostMapping("/documents/{id}/release")
    @PreAuthorize("hasAuthority('RELEASE_TO_PATIENT')")
    public DocumentView release(@PathVariable UUID id) {
        return service.release(id);
    }

    @PostMapping("/documents/{id}/archive")
    @PreAuthorize("hasAuthority('DOCUMENT_UPLOAD') and !hasRole('PATIENT')")
    public DocumentView archive(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        if (reason != null && reason.length() > 500) {
            throw ApiException.badRequest("REASON_TOO_LONG", "Keep the reason under 500 characters.");
        }
        return service.archive(id, reason);
    }

    @PostMapping("/documents/{id}/download-token")
    @PreAuthorize("(hasAuthority('CLINICAL_READ') or hasAuthority('PORTAL_ACCESS')) and !hasRole('SUPER_ADMIN')")
    public DownloadLink downloadToken(@PathVariable UUID id) {
        return service.downloadLink(id);
    }

    @GetMapping("/portal/patients/{patientId}/documents")
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public List<DocumentView> portalDocuments(@PathVariable UUID patientId) {
        return service.portalDocuments(patientId);
    }

    /** Public endpoint (permitted in SecurityConfig): the signed, short-lived token is the authorization. */
    @GetMapping("/documents/download/{token}")
    public ResponseEntity<byte[]> download(@PathVariable String token) {
        FileContent f = service.download(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.bytes());
    }
}
