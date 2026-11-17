package br.com.markys.gestaoclinica.api;

import java.time.LocalDate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import br.com.markys.gestaoclinica.security.PatientDataProtection;
import br.com.markys.gestaoclinica.security.PatientDocumentStorage;
import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/patients")
public class PatientController {
    private static final Pattern CPF = Pattern.compile("\\d{11}");
    private final JdbcTemplate jdbc;
    private final PatientDataProtection protection;
    private final PatientDocumentStorage documentStorage;

    public PatientController(JdbcTemplate jdbc, PatientDataProtection protection,
                             PatientDocumentStorage documentStorage) {
        this.jdbc = jdbc;
        this.protection = protection;
        this.documentStorage = documentStorage;
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "ACTIVE") String status) {
        if (!canAccess(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        String normalizedStatus = status == null ? "ACTIVE" : status.trim().toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE", "ALL").contains(normalizedStatus)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Filtro de situação inválido."));
        }
        String term = search == null ? "" : search.trim();
        if (term.length() > 120) term = term.substring(0, 120);
        String pattern = "%" + term + "%";
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT p.id, p.full_name, p.birth_date, p.sex, p.insurance_name, p.job_title,
                   p.status, p.created_at,
                   (SELECT count(*) FROM patient_documents d WHERE d.clinic_id=p.clinic_id AND d.patient_id=p.id) AS document_count,
                   (SELECT count(*) FROM queue_entries q WHERE q.clinic_id=p.clinic_id
                     AND q.patient_id=p.id AND q.status IN ('WAITING','CALLED','IN_SERVICE')) AS active_queue_entries,
                   (SELECT max(q.arrived_at) FROM queue_entries q
                     WHERE q.clinic_id=p.clinic_id AND q.patient_id=p.id) AS last_attendance
              FROM patients p
             WHERE p.clinic_id = ?
               AND (? = 'ALL' OR p.status = ?)
               AND (? = '' OR p.full_name ILIKE ? OR COALESCE(p.insurance_name,'') ILIKE ?
                    OR COALESCE(p.job_title,'') ILIKE ?)
             ORDER BY lower(p.full_name), p.created_at DESC
             LIMIT 300
            """, clinic, normalizedStatus, normalizedStatus, term,
            pattern, pattern, pattern));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(Authentication auth, @PathVariable UUID id) {
        if (!canAccess(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT id, full_name, birth_date, sex, insurance_name, job_title, status, created_at
              FROM patients WHERE clinic_id=? AND id=?
            """, clinic, id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String,Object> result = new java.util.LinkedHashMap<>(rows.getFirst());
        List<Map<String,Object>> queue = jdbc.queryForList("""
            SELECT id, service_type_id FROM queue_entries
             WHERE clinic_id=? AND patient_id=? AND status IN ('WAITING','CALLED')
             ORDER BY arrived_at DESC LIMIT 1
            """, clinic, id);
        if (!queue.isEmpty()) {
            result.put("serviceTypeId", queue.getFirst().get("service_type_id"));
            result.put("examIds", jdbc.queryForList("""
                SELECT exam_id FROM queue_entry_exams
                 WHERE clinic_id=? AND queue_entry_id=? ORDER BY created_at
                """, UUID.class, clinic, queue.getFirst().get("id")));
        } else {
            result.put("serviceTypeId", null);
            result.put("examIds", List.of());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(Authentication auth, @Valid @RequestBody PatientRequest body) {
        if (!canManage(auth)) return forbidden();
        if (!protection.isConfigured()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", "Cadastro de CPF indisponível. O servidor precisa configurar as chaves de proteção."));
        String cpf = normalizeCpf(body.cpf());
        if (!validCpf(cpf)) return ResponseEntity.badRequest().body(Map.of("error", "CPF inválido."));
        if (body.birthDate() != null && body.birthDate().isAfter(LocalDate.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "A data de nascimento não pode estar no futuro."));
        }
        UUID clinic = TenantContext.requireClinicId();
        UUID membership = currentMembership(auth, clinic);
        UUID patientId = UUID.randomUUID();
        UUID queueId = UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO patients (id, clinic_id, full_name, birth_date, sex, insurance_name,
                    job_title, document_type, document_fingerprint, document_ciphertext,
                    created_by_membership_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'CPF', ?, ?, ?)
                """, patientId, clinic, body.fullName().trim(), body.birthDate(), clean(body.sex()),
                clean(body.insuranceName()), clean(body.jobTitle()), protection.fingerprint(cpf),
                protection.encrypt(cpf), membership);

            int serviceTypeCount = jdbc.update("""
                UPDATE service_types SET updated_at=now()
                 WHERE clinic_id=? AND id=? AND active=true
                """, clinic, body.serviceTypeId());
            if (serviceTypeCount != 1) throw new InvalidRequest("Selecione um tipo de atendimento ativo.");

            jdbc.update("""
                INSERT INTO queue_entries (id, clinic_id, patient_id, service_type_id, service_label,
                    status, created_by_membership_id)
                SELECT ?, ?, ?, st.id, st.name, 'WAITING', ?
                  FROM service_types st
                 WHERE st.clinic_id=? AND st.id=? AND st.active=true
                """, queueId, clinic, patientId, membership, clinic, body.serviceTypeId());

            if (body.examIds() != null) {
                for (UUID examId : body.examIds().stream().distinct().toList()) {
                    int added = jdbc.update("""
                        INSERT INTO queue_entry_exams
                            (clinic_id, queue_entry_id, exam_id, exam_name_snapshot, room_id, room_name_snapshot)
                        SELECT ?, ?, e.id, e.name, r.id, r.name
                          FROM exam_catalog e
                          JOIN clinic_rooms r ON r.clinic_id=e.clinic_id AND r.id=e.room_id
                         WHERE e.clinic_id=? AND e.id=? AND e.active=true AND r.status='ACTIVE'
                        """, clinic, queueId, clinic, examId);
                    if (added != 1) throw new InvalidRequest("Um dos exames selecionados não está ativo ou não possui sala válida.");
                }
            }
        } catch (DuplicateKeyException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Já existe paciente cadastrado com este CPF nesta clínica."));
        } catch (InvalidRequest ex) {
            throw ex;
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "patientId", patientId, "queueEntryId", queueId,
            "message", "Paciente cadastrado e encaminhado para a fila."
        ));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(Authentication auth, @PathVariable UUID id,
            @Valid @RequestBody PatientStatusRequest body) {
        if (!canManage(auth)) return forbidden();
        String next = body.status() == null ? "" : body.status().trim().toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(next)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Situação de paciente inválida."));
        }
        UUID clinic = TenantContext.requireClinicId();
        int changed = jdbc.update("""
            UPDATE patients SET status=?, updated_at=now()
             WHERE clinic_id=? AND id=? AND status IN ('ACTIVE','INACTIVE')
            """, next, clinic, id);
        if (changed == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("id", id, "status", next, "message",
            next.equals("ACTIVE") ? "Paciente reativado." : "Paciente inativado."));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> history(Authentication auth, @PathVariable UUID id) {
        if (!canAccess(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        if (jdbc.queryForObject("SELECT count(*) FROM patients WHERE clinic_id=? AND id=?",
                Integer.class, clinic, id) == 0) return ResponseEntity.notFound().build();
        List<Map<String,Object>> queue = jdbc.queryForList("""
            SELECT q.id, q.arrived_at, q.called_at, q.completed_at, q.status,
                   q.service_label, q.priority, r.name AS room_name,
                   pr.display_name AS professional_name
              FROM queue_entries q
              LEFT JOIN clinic_rooms r ON r.clinic_id=q.clinic_id AND r.id=q.room_id
              LEFT JOIN professionals pr ON pr.clinic_id=q.clinic_id AND pr.id=q.assigned_professional_id
             WHERE q.clinic_id=? AND q.patient_id=?
             ORDER BY q.arrived_at DESC LIMIT 100
            """, clinic, id);
        List<Map<String,Object>> encounters = jdbc.queryForList("""
            SELECT e.id, e.started_at, e.signed_at, e.status, pr.display_name AS professional_name
              FROM encounters e
              JOIN professionals pr ON pr.clinic_id=e.clinic_id AND pr.id=e.professional_id
             WHERE e.clinic_id=? AND e.patient_id=?
             ORDER BY e.started_at DESC LIMIT 100
            """, clinic, id);
        return ResponseEntity.ok(Map.of("attendances", queue, "encounters", encounters));
    }

    @GetMapping("/{id}/documents")
    public ResponseEntity<?> documents(Authentication auth, @PathVariable UUID id) {
        if (!canAccess(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        if (jdbc.queryForObject("SELECT count(*) FROM patients WHERE clinic_id=? AND id=?", Integer.class, clinic, id) == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT id, original_file_name, file_size, created_at
              FROM patient_documents WHERE clinic_id=? AND patient_id=?
             ORDER BY created_at DESC
            """, clinic, id));
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<?> uploadDocument(Authentication auth, @PathVariable UUID id,
                                             @RequestParam("file") MultipartFile file) {
        if (!canManage(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        if (jdbc.queryForObject("SELECT count(*) FROM patients WHERE clinic_id=? AND id=? AND status='ACTIVE'",
                Integer.class, clinic, id) == 0) return ResponseEntity.notFound().build();
        String key = null;
        try {
            key = documentStorage.store(file);
            UUID membership = currentMembership(auth, clinic);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(file.getBytes());
            String originalName = file.getOriginalFilename() == null ? "documento.pdf"
                : file.getOriginalFilename().replaceAll("[\\r\\n\\\"/]", "_");
            if (originalName.length() > 180) originalName = originalName.substring(originalName.length() - 180);
            UUID documentId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO patient_documents
                    (id, clinic_id, patient_id, storage_key, original_file_name, content_type,
                     file_size, sha256, uploaded_by_membership_id)
                VALUES (?, ?, ?, ?, ?, 'application/pdf', ?, ?, ?)
                """, documentId, clinic, id, key, originalName, file.getSize(), digest, membership);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", documentId, "fileName", originalName, "message", "PDF anexado com segurança."
            ));
        } catch (IllegalArgumentException ex) {
            if (key != null) documentStorage.delete(key);
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            if (key != null) documentStorage.delete(key);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Não foi possível armazenar o PDF."));
        } catch (RuntimeException ex) {
            if (key != null) documentStorage.delete(key);
            throw ex;
        }
    }

    @GetMapping("/{id}/documents/{documentId}")
    public ResponseEntity<?> downloadDocument(Authentication auth, @PathVariable UUID id,
                                               @PathVariable UUID documentId) {
        if (!canAccess(auth)) return forbidden();
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT storage_key FROM patient_documents
             WHERE clinic_id=? AND patient_id=? AND id=?
            """, TenantContext.requireClinicId(), id, documentId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        try {
            byte[] content = documentStorage.read((String) rows.getFirst().get("storage_key"));
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"documento.pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "O arquivo não está disponível no armazenamento privado."));
        }
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(Authentication auth, @PathVariable UUID id,
                                    @Valid @RequestBody PatientUpdateRequest body) {
        if (!canManage(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        if (body.birthDate() != null && body.birthDate().isAfter(LocalDate.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "A data de nascimento não pode estar no futuro."));
        }
        int changed = jdbc.update("""
            UPDATE patients SET full_name=?, birth_date=?, sex=?, insurance_name=?, job_title=?, updated_at=now()
             WHERE clinic_id=? AND id=? AND status='ACTIVE'
            """, body.fullName().trim(), body.birthDate(), clean(body.sex()), clean(body.insuranceName()),
            clean(body.jobTitle()), clinic, id);
        if (changed == 0) return ResponseEntity.notFound().build();

        if (body.serviceTypeId() != null) {
            List<Map<String,Object>> activeQueue = jdbc.queryForList("""
                SELECT id FROM queue_entries WHERE clinic_id=? AND patient_id=?
                  AND status IN ('WAITING','CALLED') ORDER BY arrived_at DESC LIMIT 1
                """, clinic, id);
            if (!activeQueue.isEmpty()) {
                UUID queueId = (UUID) activeQueue.getFirst().get("id");
                int updated = jdbc.update("""
                    UPDATE queue_entries q SET service_type_id=st.id, service_label=st.name
                      FROM service_types st
                     WHERE q.clinic_id=? AND q.id=? AND st.clinic_id=q.clinic_id
                       AND st.id=? AND st.active=true
                    """, clinic, queueId, body.serviceTypeId());
                if (updated != 1) throw new InvalidRequest("Selecione um tipo de atendimento ativo.");
                jdbc.update("DELETE FROM queue_entry_exams WHERE clinic_id=? AND queue_entry_id=?", clinic, queueId);
                if (body.examIds() != null) {
                    for (UUID examId : body.examIds().stream().distinct().toList()) {
                        int added = jdbc.update("""
                            INSERT INTO queue_entry_exams
                                (clinic_id, queue_entry_id, exam_id, exam_name_snapshot, room_id, room_name_snapshot)
                            SELECT ?, ?, e.id, e.name, r.id, r.name
                              FROM exam_catalog e
                              JOIN clinic_rooms r ON r.clinic_id=e.clinic_id AND r.id=e.room_id
                             WHERE e.clinic_id=? AND e.id=? AND e.active=true AND r.status='ACTIVE'
                            """, clinic, queueId, clinic, examId);
                        if (added != 1) throw new InvalidRequest("Um dos exames selecionados não está ativo ou não possui sala válida.");
                    }
                }
            }
        }
        return ResponseEntity.ok(Map.of("id", id, "message", "Cadastro atualizado."));
    }

    private UUID currentMembership(Authentication auth, UUID clinic) {
        return jdbc.queryForObject("""
            SELECT m.id FROM clinic_memberships m
              JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
            """, UUID.class, auth.getName(), clinic);
    }

    private boolean canAccess(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        return jdbc.queryForList("""
            SELECT m.role FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
            """, String.class, auth.getName(), TenantContext.requireClinicId())
            .stream().anyMatch(role -> List.of("CLINIC_ADMIN", "RECEPTION", "PROFESSIONAL").contains(role));
    }

    private boolean canManage(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        return jdbc.queryForList("""
            SELECT m.role FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
            """, String.class, auth.getName(), TenantContext.requireClinicId())
            .stream().anyMatch(role -> List.of("CLINIC_ADMIN", "RECEPTION").contains(role));
    }

    private ResponseEntity<Map<String,String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "Seu perfil não pode acessar o cadastro de pacientes."));
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeCpf(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static boolean validCpf(String cpf) {
        if (!CPF.matcher(cpf).matches() || cpf.chars().distinct().count() == 1) return false;
        int sum=0;
        for (int i=0;i<9;i++) sum += (cpf.charAt(i)-'0')*(10-i);
        int first=(sum*10)%11; if(first==10) first=0;
        if(first != cpf.charAt(9)-'0') return false;
        sum=0;
        for(int i=0;i<10;i++) sum += (cpf.charAt(i)-'0')*(11-i);
        int second=(sum*10)%11; if(second==10) second=0;
        return second == cpf.charAt(10)-'0';
    }

    public record PatientRequest(
        @NotBlank @Size(max=180) String fullName,
        @NotBlank @Size(max=20) String cpf,
        LocalDate birthDate,
        @Size(max=30) String sex,
        @Size(max=180) String insuranceName,
        @Size(max=160) String jobTitle,
        @NotNull UUID serviceTypeId,
        List<UUID> examIds
    ) {}
    public record PatientStatusRequest(@NotBlank @Size(max=20) String status) {}
    public record PatientUpdateRequest(
        @NotBlank @Size(max=180) String fullName,
        LocalDate birthDate,
        @Size(max=30) String sex,
        @Size(max=180) String insuranceName,
        @Size(max=160) String jobTitle,
        UUID serviceTypeId,
        List<UUID> examIds
    ) {}
    @ExceptionHandler(InvalidRequest.class)
    public ResponseEntity<Map<String,String>> invalidRequest(InvalidRequest ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    private static final class InvalidRequest extends RuntimeException {
        InvalidRequest(String message) { super(message); }
    }
}
