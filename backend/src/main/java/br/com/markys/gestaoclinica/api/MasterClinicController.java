package br.com.markys.gestaoclinica.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/master")
public class MasterClinicController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public MasterClinicController(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @GetMapping("/clinics")
    public List<Map<String, Object>> clinics() {
        return jdbc.queryForList("""
            SELECT c.id, c.name, c.status, c.created_at,
                   count(DISTINCT m.user_id) FILTER (WHERE m.status = 'ACTIVE') AS active_users,
                   count(DISTINCT m.user_id) FILTER (WHERE m.role = 'CLINIC_ADMIN' AND m.status = 'ACTIVE') AS administrators
              FROM clinics c
              LEFT JOIN clinic_memberships m ON m.clinic_id = c.id
             GROUP BY c.id
             ORDER BY c.created_at DESC
            """);
    }

    @PostMapping("/clinics")
    @Transactional
    public ResponseEntity<?> createClinic(@Valid @RequestBody CreateClinicRequest body) {
        if (body.adminPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.badRequest().body(Map.of("error", "A senha inicial excede o limite seguro do BCrypt."));
        }
        String email = body.adminEmail().trim().toLowerCase(Locale.ROOT);
        Long duplicate = jdbc.queryForObject(
            "SELECT count(*) FROM app_users WHERE lower(email) = lower(?)", Long.class, email
        );
        if (duplicate != null && duplicate > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Já existe uma conta com este e-mail."));
        }

        UUID clinicId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        jdbc.update("INSERT INTO clinics (id, name, status) VALUES (?, ?, 'ACTIVE')",
            clinicId, body.clinicName().trim());
        jdbc.update("""
            INSERT INTO app_users (id, full_name, email, password_hash, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """, userId, body.adminName().trim(), email, encoder.encode(body.adminPassword()));
        jdbc.update("""
            INSERT INTO clinic_memberships (id, clinic_id, user_id, role, status)
            VALUES (?, ?, ?, 'CLINIC_ADMIN', 'ACTIVE')
            """, membershipId, clinicId, userId);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "clinicId", clinicId,
            "clinicName", body.clinicName().trim(),
            "adminEmail", email,
            "message", "Clínica e administrador local criados."
        ));
    }

    @PatchMapping("/clinics/{clinicId}/status")
    public ResponseEntity<?> updateStatus(
        @PathVariable UUID clinicId,
        @Valid @RequestBody ClinicStatusRequest body
    ) {
        String status = body.status().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "SUSPENDED", "INACTIVE").contains(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Status inválido."));
        }
        int updated = jdbc.update(
            "UPDATE clinics SET status = ?, updated_at = now() WHERE id = ?", status, clinicId
        );
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("clinicId", clinicId, "status", status));
    }

    public record CreateClinicRequest(
        @NotBlank @Size(max = 180) String clinicName,
        @NotBlank @Size(max = 180) String adminName,
        @NotBlank @Email @Size(max = 254) String adminEmail,
        @NotBlank @Size(min = 12, max = 72) String adminPassword
    ) {}

    public record ClinicStatusRequest(@NotBlank @Size(max = 20) String status) {}
}
