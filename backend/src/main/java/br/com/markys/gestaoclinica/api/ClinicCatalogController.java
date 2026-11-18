package br.com.markys.gestaoclinica.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/catalog")
public class ClinicCatalogController {
    private final JdbcTemplate jdbc;

    public ClinicCatalogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<?> catalog(Authentication authentication) {
        if (!canManageCatalog(authentication)) return forbidden();
        UUID clinicId = TenantContext.requireClinicId();
        return ResponseEntity.ok(Map.of(
            "rooms", jdbc.queryForList("""
                SELECT id, name, status FROM clinic_rooms
                 WHERE clinic_id = ? ORDER BY lower(name)
                """, clinicId),
            "serviceTypes", jdbc.queryForList("""
                SELECT id, name, active, sort_order FROM service_types
                 WHERE clinic_id = ? ORDER BY sort_order, lower(name)
                """, clinicId),
            "exams", jdbc.queryForList("""
                SELECT e.id, e.category, e.name, e.room_id, r.name AS room_name,
                       e.active, e.sort_order
                  FROM exam_catalog e
                  JOIN clinic_rooms r ON r.clinic_id = e.clinic_id AND r.id = e.room_id
                 WHERE e.clinic_id = ?
                 ORDER BY e.category, e.sort_order, lower(e.name)
                """, clinicId)
        ));
    }

    @PostMapping("/rooms")
    @Transactional
    public ResponseEntity<?> createRoom(
        Authentication authentication, @Valid @RequestBody NameRequest body
    ) {
        if (!canManageCatalog(authentication)) return forbidden();
        UUID clinicId = TenantContext.requireClinicId();
        if (jdbc.queryForObject("SELECT count(*) FROM clinic_rooms WHERE clinic_id=? AND lower(name)=lower(?)",
                Integer.class, clinicId, body.name().trim()) > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Já existe uma sala com esse nome."));
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO clinic_rooms (id, clinic_id, name) VALUES (?, ?, ?)",
                id, clinicId, body.name().trim());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Já existe uma sala com esse nome."));
        }
        jdbc.update("""
            INSERT INTO clinic_room_events(clinic_id,room_id,event_type,detail,actor_membership_id)
            VALUES(?,?,'CREATED','Consultório cadastrado pela configuração da recepção',?)
        """, clinicId, id, currentMembershipId(authentication, clinicId));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "name", body.name().trim()));
    }

    @PostMapping("/service-types")
    @Transactional
    public ResponseEntity<?> createServiceType(
        Authentication authentication, @Valid @RequestBody NameRequest body
    ) {
        if (!canManageCatalog(authentication)) return forbidden();
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO service_types (id, clinic_id, name) VALUES (?, ?, ?)",
                id, TenantContext.requireClinicId(), body.name().trim());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Esse tipo de atendimento já está cadastrado."));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "name", body.name().trim()));
    }

    @PostMapping("/exams")
    @Transactional
    public ResponseEntity<?> createExam(
        Authentication authentication, @Valid @RequestBody ExamRequest body
    ) {
        if (!canManageCatalog(authentication)) return forbidden();
        UUID clinicId = TenantContext.requireClinicId();
        String category = body.category().trim().toUpperCase(Locale.ROOT);
        if (!List.of("RAIO_X", "LABORATORIO", "AUDIOMETRIA", "COMPLEMENTAR", "OUTROS").contains(category)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Categoria de exame inválida."));
        }
        UUID id = UUID.randomUUID();
        try {
            int inserted = jdbc.update("""
                INSERT INTO exam_catalog (id, clinic_id, category, name, room_id)
                SELECT ?, ?, ?, ?, r.id
                  FROM clinic_rooms r
                 WHERE r.clinic_id = ? AND r.id = ? AND r.status = 'ACTIVE'
                """, id, clinicId, category, body.name().trim(), clinicId, body.roomId());
            if (inserted == 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Selecione uma sala ativa desta clínica."));
            }
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Esse exame já está cadastrado nessa categoria."));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", id, "category", category, "name", body.name().trim(), "roomId", body.roomId()
        ));
    }

    @PatchMapping("/rooms/{id}/status")
    @Transactional
    public ResponseEntity<?> roomStatus(
        Authentication authentication, @PathVariable UUID id, @Valid @RequestBody StatusRequest body
    ) {
        if (!canManageCatalog(authentication)) return forbidden();
        String status = normalizedStatus(body.status());
        if (status == null) return badStatus();
        UUID clinicId = TenantContext.requireClinicId();
        List<Map<String,Object>> current = jdbc.queryForList(
            "SELECT status FROM clinic_rooms WHERE clinic_id=? AND id=? FOR UPDATE", clinicId, id);
        if (current.isEmpty()) return ResponseEntity.notFound().build();
        String previous = String.valueOf(current.getFirst().get("status"));
        if ("INACTIVE".equals(status)) {
            if (jdbc.queryForObject("SELECT count(*) FROM exam_catalog WHERE clinic_id=? AND room_id=? AND active=true", Integer.class, clinicId, id) > 0)
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Desative primeiro os exames do catálogo vinculados a esta sala."));
            if (jdbc.queryForObject("SELECT count(*) FROM queue_entries WHERE clinic_id=? AND room_id=? AND status IN ('WAITING','CALLED','IN_SERVICE')", Integer.class, clinicId, id) > 0)
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Há atendimentos ativos nesta sala."));
            if (jdbc.queryForObject("SELECT count(*) FROM appointments WHERE clinic_id=? AND room_id=? AND status IN ('SCHEDULED','CONFIRMED','CHECKED_IN') AND ends_at>now()", Integer.class, clinicId, id) > 0)
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Há agendamentos futuros ou em andamento nesta sala."));
            if (jdbc.queryForObject("SELECT count(*) FROM exam_orders WHERE clinic_id=? AND room_id=? AND status IN ('REQUESTED','SCHEDULED','IN_PROGRESS')", Integer.class, clinicId, id) > 0)
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Há exames pendentes, agendados ou em execução nesta sala."));
        }
        if (!previous.equals(status)) {
            jdbc.update("UPDATE clinic_rooms SET status=?,updated_at=now() WHERE clinic_id=? AND id=?", status, clinicId, id);
            jdbc.update("""
                INSERT INTO clinic_room_events(clinic_id,room_id,event_type,detail,actor_membership_id)
                VALUES(?,?,?,?,?)
            """, clinicId, id, "ACTIVE".equals(status) ? "ACTIVATED" : "DEACTIVATED",
                "Situação alterada pela configuração da recepção", currentMembershipId(authentication, clinicId));
        }
        return ResponseEntity.ok(Map.of("id", id, "status", status));
    }

    @PatchMapping("/service-types/{id}/status")
    @Transactional
    public ResponseEntity<?> serviceTypeStatus(
        Authentication authentication, @PathVariable UUID id, @Valid @RequestBody ActiveRequest body
    ) {
        if (!canManageCatalog(authentication)) return forbidden();
        int count = jdbc.update("""
            UPDATE service_types SET active = ?, updated_at = now()
             WHERE clinic_id = ? AND id = ?
            """, body.active(), TenantContext.requireClinicId(), id);
        return count == 0 ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(Map.of("id", id, "active", body.active()));
    }

    @PatchMapping("/exams/{id}/status")
    @Transactional
    public ResponseEntity<?> examStatus(
        Authentication authentication, @PathVariable UUID id, @Valid @RequestBody ActiveRequest body
    ) {
        if (!canManageCatalog(authentication)) return forbidden();
        int count = jdbc.update("""
            UPDATE exam_catalog SET active = ?, updated_at = now()
             WHERE clinic_id = ? AND id = ?
            """, body.active(), TenantContext.requireClinicId(), id);
        return count == 0 ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(Map.of("id", id, "active", body.active()));
    }

    private UUID currentMembershipId(Authentication authentication, UUID clinicId) {
        return jdbc.queryForObject("""
            SELECT m.id FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
        """, UUID.class, authentication.getName(), clinicId);
    }

    private boolean canManageCatalog(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        return jdbc.queryForList("""
            SELECT m.role
              FROM clinic_memberships m
              JOIN app_users u ON u.id = m.user_id
             WHERE lower(u.email) = lower(?) AND m.clinic_id = ?
               AND m.status = 'ACTIVE' AND u.status = 'ACTIVE'
            """, String.class, authentication.getName(), TenantContext.requireClinicId())
            .stream().anyMatch(role -> List.of("CLINIC_ADMIN", "RECEPTION").contains(role));
    }

    private ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "Seu perfil não pode administrar os cadastros da clínica."));
    }

    private ResponseEntity<Map<String, String>> badStatus() {
        return ResponseEntity.badRequest().body(Map.of("error", "Status deve ser ACTIVE ou INACTIVE."));
    }

    private String normalizedStatus(String value) {
        String status = value.trim().toUpperCase(Locale.ROOT);
        return List.of("ACTIVE", "INACTIVE").contains(status) ? status : null;
    }

    public record NameRequest(@NotBlank @Size(max = 140) String name) {}
    public record ExamRequest(
        @NotBlank @Size(max = 60) String category,
        @NotBlank @Size(max = 140) String name,
        @NotNull UUID roomId
    ) {}
    public record StatusRequest(@NotBlank @Size(max = 20) String status) {}
    public record ActiveRequest(@NotNull Boolean active) {}
}
