package br.com.markys.gestaoclinica.api;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

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
@RequestMapping("/api/v1/clinic/professionals")
public class ProfessionalController {
    private static final List<String> COUNCILS = List.of(
        "CRM", "CRP", "CRO", "COREN", "CREFITO", "CREF", "CRN", "CRFa", "CRBM", "CRBio", "OUTRO"
    );

    private final JdbcTemplate jdbc;
    private final PatientDataProtection protection;

    public ProfessionalController(JdbcTemplate jdbc, PatientDataProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "ACTIVE") String status) {
        if (!canRead(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        String state = normalizeStatus(status);
        if (state == null && !"ALL".equalsIgnoreCase(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Filtro de situação inválido."));
        }
        String term = search == null ? "" : search.trim();
        if (term.length() > 120) term = term.substring(0, 120);
        String pattern = "%" + term + "%";
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT p.id, p.membership_id, p.display_name, p.council_type, p.specialty,
                   p.status, p.created_at, p.updated_at, u.email AS account_email,
                   (SELECT count(*) FROM encounters e
                     WHERE e.clinic_id=p.clinic_id AND e.professional_id=p.id) AS encounter_count,
                   (SELECT count(*) FROM queue_entries q
                     WHERE q.clinic_id=p.clinic_id AND q.assigned_professional_id=p.id
                       AND q.status IN ('WAITING','CALLED','IN_SERVICE')) AS active_queue_count
              FROM professionals p
              JOIN clinic_memberships m ON m.clinic_id=p.clinic_id AND m.id=p.membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE p.clinic_id=?
               AND (?='ALL' OR p.status=?)
               AND (?='' OR p.display_name ILIKE ? OR COALESCE(p.specialty,'') ILIKE ?
                    OR COALESCE(p.council_type,'') ILIKE ? OR u.email ILIKE ?)
             ORDER BY lower(p.display_name), p.created_at DESC
             LIMIT 500
            """, clinic, "ALL".equalsIgnoreCase(status) ? "ALL" : state,
            "ALL".equalsIgnoreCase(status) ? "ALL" : state, term, pattern, pattern, pattern, pattern));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(Authentication auth, @PathVariable UUID id) {
        if (!canRead(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT p.id, p.membership_id, p.display_name, p.council_type,
                   p.council_number_ciphertext, p.specialty, p.status,
                   m.role AS account_role, u.full_name AS account_name, u.email AS account_email
              FROM professionals p
              JOIN clinic_memberships m ON m.clinic_id=p.clinic_id AND m.id=p.membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE p.clinic_id=? AND p.id=?
            """, clinic, id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String,Object> result = new LinkedHashMap<>(rows.getFirst());
        byte[] encrypted = (byte[]) result.remove("council_number_ciphertext");
        result.put("councilNumber", encrypted == null ? "" : protection.decrypt(encrypted));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/eligible-accounts")
    public ResponseEntity<?> eligibleAccounts(Authentication auth) {
        if (!canManage(auth)) return forbidden();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT m.id AS membership_id, u.full_name, u.email
              FROM clinic_memberships m
              JOIN app_users u ON u.id=m.user_id
             WHERE m.clinic_id=? AND m.role='PROFESSIONAL'
               AND m.status='ACTIVE' AND u.status='ACTIVE'
               AND NOT EXISTS (
                   SELECT 1 FROM professionals p
                    WHERE p.clinic_id=m.clinic_id AND p.membership_id=m.id
               )
             ORDER BY lower(u.full_name)
            """, TenantContext.requireClinicId()));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(Authentication auth, @Valid @RequestBody ProfessionalRequest body) {
        if (!canManage(auth)) return forbidden();
        String council = normalizeCouncil(body.councilType());
        if (council == null) return badRequest("Selecione um conselho profissional válido.");
        String registration = clean(body.councilNumber());
        if (registration == null) return badRequest("Informe o número de registro profissional.");
        UUID clinic = TenantContext.requireClinicId();
        UUID id = UUID.randomUUID();
        try {
            int inserted = jdbc.update("""
                INSERT INTO professionals
                    (id, clinic_id, membership_id, display_name, council_type,
                     council_number_ciphertext, specialty)
                SELECT ?, m.clinic_id, m.id, ?, ?, ?, ?
                  FROM clinic_memberships m
                  JOIN app_users u ON u.id=m.user_id
                 WHERE m.clinic_id=? AND m.id=? AND m.role='PROFESSIONAL'
                   AND m.status='ACTIVE' AND u.status='ACTIVE'
                """, id, body.displayName().trim(), council, protection.encrypt(registration),
                clean(body.specialty()), clinic, body.membershipId());
            if (inserted != 1) return badRequest("A conta selecionada não é um usuário profissional ativo desta clínica.");
        } catch (DuplicateKeyException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Esta conta já possui um cadastro profissional nesta clínica."));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", id, "message", "Cadastro profissional criado com sucesso."
        ));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(Authentication auth, @PathVariable UUID id,
                                    @Valid @RequestBody ProfessionalUpdateRequest body) {
        if (!canManage(auth)) return forbidden();
        String council = normalizeCouncil(body.councilType());
        if (council == null) return badRequest("Selecione um conselho profissional válido.");
        String registration = clean(body.councilNumber());
        if (registration == null) return badRequest("Informe o número de registro profissional.");
        int changed = jdbc.update("""
            UPDATE professionals
               SET display_name=?, council_type=?, council_number_ciphertext=?,
                   specialty=?, updated_at=now()
             WHERE clinic_id=? AND id=? AND status IN ('ACTIVE','INACTIVE')
            """, body.displayName().trim(), council, protection.encrypt(registration),
            clean(body.specialty()), TenantContext.requireClinicId(), id);
        if (changed == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("id", id, "message", "Cadastro profissional atualizado."));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> updateStatus(Authentication auth, @PathVariable UUID id,
                                          @Valid @RequestBody StatusRequest body) {
        if (!canManage(auth)) return forbidden();
        String status = normalizeStatus(body.status());
        if (status == null) return badRequest("Situação deve ser ACTIVE ou INACTIVE.");
        int changed = jdbc.update("""
            UPDATE professionals SET status=?, updated_at=now()
             WHERE clinic_id=? AND id=? AND status IN ('ACTIVE','INACTIVE')
            """, status, TenantContext.requireClinicId(), id);
        if (changed == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("id", id, "status", status,
            "message", status.equals("ACTIVE") ? "Profissional reativado." : "Profissional inativado."));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> history(Authentication auth, @PathVariable UUID id) {
        if (!canRead(auth)) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        if (jdbc.queryForObject("SELECT count(*) FROM professionals WHERE clinic_id=? AND id=?",
                Integer.class, clinic, id) == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
            "encounters", jdbc.queryForList("""
                SELECT e.id, e.started_at, e.signed_at, e.status, p.full_name AS patient_name
                  FROM encounters e
                  JOIN patients p ON p.clinic_id=e.clinic_id AND p.id=e.patient_id
                 WHERE e.clinic_id=? AND e.professional_id=?
                 ORDER BY e.started_at DESC LIMIT 100
                """, clinic, id),
            "queue", jdbc.queryForList("""
                SELECT q.id, q.arrived_at, q.called_at, q.completed_at, q.status,
                       q.service_label, p.full_name AS patient_name, r.name AS room_name
                  FROM queue_entries q
                  JOIN patients p ON p.clinic_id=q.clinic_id AND p.id=q.patient_id
                  LEFT JOIN clinic_rooms r ON r.clinic_id=q.clinic_id AND r.id=q.room_id
                 WHERE q.clinic_id=? AND q.assigned_professional_id=?
                 ORDER BY q.arrived_at DESC LIMIT 100
                """, clinic, id)
        ));
    }

    private boolean canRead(Authentication auth) {
        return hasRole(auth, List.of("CLINIC_ADMIN", "RECEPTION", "PROFESSIONAL"));
    }

    private boolean canManage(Authentication auth) {
        return hasRole(auth, List.of("CLINIC_ADMIN"));
    }

    private boolean hasRole(Authentication auth, List<String> roles) {
        if (auth == null || !auth.isAuthenticated()) return false;
        return jdbc.queryForList("""
            SELECT m.role FROM clinic_memberships m
              JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=?
               AND m.status='ACTIVE' AND u.status='ACTIVE'
            """, String.class, auth.getName(), TenantContext.requireClinicId())
            .stream().anyMatch(roles::contains);
    }

    private static String normalizeCouncil(String value) {
        if (value == null) return null;
        String council = value.trim().toUpperCase(Locale.ROOT);
        return COUNCILS.contains(council) ? council : null;
    }

    private static String normalizeStatus(String value) {
        if (value == null) return null;
        String status = value.trim().toUpperCase(Locale.ROOT);
        return List.of("ACTIVE", "INACTIVE").contains(status) ? status : null;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseEntity<Map<String,String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "Seu perfil não possui permissão para esta operação."));
    }

    private ResponseEntity<Map<String,String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    public record ProfessionalRequest(
        @NotNull UUID membershipId,
        @NotBlank @Size(max=180) String displayName,
        @NotBlank @Size(max=30) String councilType,
        @NotBlank @Size(max=80) String councilNumber,
        @Size(max=120) String specialty
    ) {}

    public record ProfessionalUpdateRequest(
        @NotBlank @Size(max=180) String displayName,
        @NotBlank @Size(max=30) String councilType,
        @NotBlank @Size(max=80) String councilNumber,
        @Size(max=120) String specialty
    ) {}

    public record StatusRequest(@NotBlank @Size(max=20) String status) {}
}
