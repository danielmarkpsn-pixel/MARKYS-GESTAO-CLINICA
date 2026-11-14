package br.com.markys.gestaoclinica.api;

import java.time.LocalDate;
import java.time.Period;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import br.com.markys.gestaoclinica.security.PatientDataProtection;
import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/patients")
public class PatientController {
    private static final Pattern CPF = Pattern.compile("\\d{11}");
    private final JdbcTemplate jdbc;
    private final PatientDataProtection protection;

    public PatientController(JdbcTemplate jdbc, PatientDataProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        if (!canAccess(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT p.id, p.full_name, p.birth_date, p.sex, p.insurance_name, p.job_title,
                   p.status, p.created_at,
                   (SELECT count(*) FROM queue_entries q WHERE q.clinic_id=p.clinic_id
                     AND q.patient_id=p.id AND q.status IN ('WAITING','CALLED','IN_SERVICE')) AS active_queue_entries
              FROM patients p
             WHERE p.clinic_id = ? AND p.status = 'ACTIVE'
             ORDER BY p.created_at DESC
             LIMIT 250
            """, clinic));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(Authentication auth, @PathVariable UUID id) {
        if (!canAccess(auth)) return forbidden();
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT id, full_name, birth_date, sex, insurance_name, job_title, status, created_at
              FROM patients WHERE clinic_id=? AND id=?
            """, TenantContext.requireClinicId(), id);
        return rows.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(rows.getFirst());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(Authentication auth, @Valid @RequestBody PatientRequest body) {
        if (!canAccess(auth)) return forbidden();
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

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(Authentication auth, @PathVariable UUID id,
                                    @Valid @RequestBody PatientUpdateRequest body) {
        if (!canAccess(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        int changed = jdbc.update("""
            UPDATE patients SET full_name=?, birth_date=?, sex=?, insurance_name=?, job_title=?, updated_at=now()
             WHERE clinic_id=? AND id=? AND status='ACTIVE'
            """, body.fullName().trim(), body.birthDate(), clean(body.sex()), clean(body.insuranceName()),
            clean(body.jobTitle()), clinic, id);
        return changed == 0 ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(Map.of("id", id, "message", "Cadastro atualizado."));
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
    public record PatientUpdateRequest(
        @NotBlank @Size(max=180) String fullName,
        LocalDate birthDate,
        @Size(max=30) String sex,
        @Size(max=180) String insuranceName,
        @Size(max=160) String jobTitle
    ) {}
    @ExceptionHandler(InvalidRequest.class)
    public ResponseEntity<Map<String,String>> invalidRequest(InvalidRequest ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    private static final class InvalidRequest extends RuntimeException {
        InvalidRequest(String message) { super(message); }
    }
}
