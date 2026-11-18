package br.com.markys.gestaoclinica.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import br.com.markys.gestaoclinica.security.PatientDataProtection;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/queue")
public class QueueController {
    private static final List<String> STATUSES = List.of(
        "WAITING", "CALLED", "IN_SERVICE", "COMPLETED", "RELEASED", "CANCELLED"
    );
    private static final Pattern CPF_DIGITS = Pattern.compile("\\d{11}");
    private final JdbcTemplate jdbc;
    private final PatientDataProtection protection;

    public QueueController(JdbcTemplate jdbc, PatientDataProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @GetMapping("/resources")
    public ResponseEntity<?> resources(Authentication auth) {
        Access access = access(auth);
        if (access == null) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        List<Map<String,Object>> professionals = jdbc.queryForList("""
            SELECT id, display_name, specialty
              FROM professionals
             WHERE clinic_id=? AND status='ACTIVE'
               AND (? <> 'PROFESSIONAL' OR id=?)
             ORDER BY lower(display_name)
            """, clinic, access.role(), access.professionalId());
        List<Map<String,Object>> rooms = jdbc.queryForList("""
            SELECT id, name, room_type
              FROM clinic_rooms
             WHERE clinic_id=? AND status='ACTIVE'
             ORDER BY lower(name)
            """, clinic);
        return ResponseEntity.ok(Map.of("professionals", professionals, "rooms", rooms));
    }

    @GetMapping
    public ResponseEntity<?> list(
            Authentication auth,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "true") boolean today,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String priority,
            @RequestParam(defaultValue = "") String professionalId,
            @RequestParam(defaultValue = "false") boolean mine) {
        Access access = access(auth);
        if (access == null) return forbidden();

        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        if (!normalizedStatus.isEmpty() && !STATUSES.contains(normalizedStatus))
            return ResponseEntity.badRequest().body(Map.of("error", "Filtro de situação inválido."));

        String normalizedPriority = priority == null ? "" : priority.trim().toUpperCase();
        if (!normalizedPriority.isEmpty() && !List.of("NORMAL","PRIORITY","URGENT").contains(normalizedPriority))
            return ResponseEntity.badRequest().body(Map.of("error", "Filtro de prioridade inválido."));
        String term = search == null ? "" : search.trim();
        if (term.length() > 120) term = term.substring(0, 120);
        String pattern = "%" + term + "%";
        String digits = term.replaceAll("\\D", "");
        byte[] cpfFingerprint = CPF_DIGITS.matcher(digits).matches() && protection.isConfigured()
            ? protection.fingerprint(digits) : null;
        String dateFrom = from == null ? "" : from.trim();
        String dateTo = to == null ? "" : to.trim();
        try {
            if (!dateFrom.isEmpty()) java.time.LocalDate.parse(dateFrom);
            if (!dateTo.isEmpty()) java.time.LocalDate.parse(dateTo);
        } catch (java.time.DateTimeException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Período de datas inválido."));
        }
        if (!dateFrom.isEmpty() && !dateTo.isEmpty() && dateFrom.compareTo(dateTo) > 0)
            return ResponseEntity.badRequest().body(Map.of("error", "A data inicial não pode ser posterior à data final."));
        String professional = professionalId == null ? "" : professionalId.trim();
        if (professional.length() > 36) return ResponseEntity.badRequest().body(Map.of("error", "Profissional inválido."));
        UUID clinic = TenantContext.requireClinicId();
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT q.id, q.arrived_at, q.called_at, q.completed_at, q.status,
                   q.priority, q.service_label, q.room_id, q.assigned_professional_id,
                   p.id AS patient_id, p.full_name AS patient_name, p.birth_date,
                   r.name AS room_name, pr.display_name AS professional_name
              FROM queue_entries q
              JOIN patients p ON p.clinic_id=q.clinic_id AND p.id=q.patient_id
              LEFT JOIN clinic_rooms r ON r.clinic_id=q.clinic_id AND r.id=q.room_id
              LEFT JOIN professionals pr
                ON pr.clinic_id=q.clinic_id AND pr.id=q.assigned_professional_id
             WHERE q.clinic_id=?
               AND (? = '' OR q.status=?)
               AND (? = '' OR p.full_name ILIKE ? OR COALESCE(q.service_label,'') ILIKE ?
                    OR COALESCE(pr.display_name,'') ILIKE ? OR COALESCE(r.name,'') ILIKE ?
                    OR (CAST(? AS bytea) IS NOT NULL AND p.document_fingerprint=CAST(? AS bytea)))
               AND (
                    (? = '' AND ? = '' AND
                       ((? = TRUE AND q.arrived_at >= CURRENT_DATE AND q.arrived_at < CURRENT_DATE + INTERVAL '1 day')
                        OR (? = FALSE AND q.arrived_at >= CURRENT_DATE - INTERVAL '30 days')))
                    OR ((? <> '' OR ? <> '')
                        AND (? = '' OR q.arrived_at >= CAST(? AS date))
                        AND (? = '' OR q.arrived_at < CAST(? AS date) + INTERVAL '1 day'))
               )
               AND (? = '' OR q.priority=?)
               AND (? = '' OR q.assigned_professional_id::text=?)
               AND (? = FALSE OR (CAST(? AS uuid) IS NOT NULL AND q.assigned_professional_id=CAST(? AS uuid)))
               AND (? <> 'PROFESSIONAL' OR q.assigned_professional_id=?)
             ORDER BY CASE q.status
                 WHEN 'IN_SERVICE' THEN 0 WHEN 'CALLED' THEN 1 WHEN 'WAITING' THEN 2
                 WHEN 'COMPLETED' THEN 3 WHEN 'RELEASED' THEN 4 ELSE 5 END,
                 CASE q.priority WHEN 'URGENT' THEN 0 WHEN 'PRIORITY' THEN 1 ELSE 2 END,
                 q.arrived_at ASC
             LIMIT 300
            """, clinic, normalizedStatus, normalizedStatus, term, pattern, pattern, pattern, pattern,
            cpfFingerprint, cpfFingerprint, dateFrom, dateTo, today, today, dateFrom, dateTo, dateFrom, dateFrom, dateTo, dateTo,
            normalizedPriority, normalizedPriority, professional, professional,
            mine, access.professionalId(), access.professionalId(), access.role(), access.professionalId());
        return ResponseEntity.ok(rows);
    }

    @PatchMapping("/{id}/assignment")
    @Transactional
    public ResponseEntity<?> assign(Authentication auth, @PathVariable UUID id,
                                    @RequestBody AssignmentRequest body) {
        Access access = access(auth);
        if (access == null || !List.of("CLINIC_ADMIN", "RECEPTION").contains(access.role()))
            return forbidden();
        if (body == null || (body.roomId() == null && body.professionalId() == null))
            return badRequest("Selecione ao menos um consultório ou profissional.");

        UUID clinic = TenantContext.requireClinicId();
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT status, room_id, assigned_professional_id
              FROM queue_entries WHERE clinic_id=? AND id=? FOR UPDATE
            """, clinic, id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String,Object> current = rows.getFirst();
        String currentStatus = String.valueOf(current.get("status"));
        if (!List.of("WAITING", "CALLED").contains(currentStatus))
            return badRequest("Atribuição só pode ser alterada enquanto o atendimento aguarda ou está chamado.");

        if (body.roomId() != null && count("""
            SELECT count(*) FROM clinic_rooms WHERE clinic_id=? AND id=? AND status='ACTIVE'
            """, clinic, body.roomId()) == 0)
            return badRequest("Consultório não encontrado ou inativo.");
        if (body.professionalId() != null && count("""
            SELECT count(*) FROM professionals WHERE clinic_id=? AND id=? AND status='ACTIVE'
            """, clinic, body.professionalId()) == 0)
            return badRequest("Profissional não encontrado ou inativo.");

        UUID oldRoom = (UUID) current.get("room_id");
        UUID oldProfessional = (UUID) current.get("assigned_professional_id");
        int changed = jdbc.update("""
            UPDATE queue_entries SET room_id=?, assigned_professional_id=?
             WHERE clinic_id=? AND id=? AND status=?
            """, body.roomId(), body.professionalId(), clinic, id, currentStatus);
        if (changed != 1) return conflict("O atendimento foi alterado por outro usuário. Atualize a lista.");

        if (!java.util.Objects.equals(oldRoom, body.roomId())
                || !java.util.Objects.equals(oldProfessional, body.professionalId())) {
            audit(id, "ASSIGNED", currentStatus, currentStatus, oldRoom, body.roomId(),
                oldProfessional, body.professionalId(), "Consultório/profissional atualizado", access.membershipId());
        }
        return ResponseEntity.ok(Map.of("id", id, "roomId", body.roomId() == null ? "" : body.roomId(),
            "professionalId", body.professionalId() == null ? "" : body.professionalId(),
            "message", "Encaminhamento atualizado."));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> updateStatus(Authentication auth, @PathVariable UUID id,
                                          @RequestBody StatusRequest body) {
        Access access = access(auth);
        if (access == null) return forbidden();
        String next = body == null || body.status() == null ? "" : body.status().trim().toUpperCase();
        if (!STATUSES.contains(next)) return badRequest("Situação de atendimento inválida.");

        UUID clinic = TenantContext.requireClinicId();
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT status, room_id, assigned_professional_id
              FROM queue_entries WHERE clinic_id=? AND id=? FOR UPDATE
            """, clinic, id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String,Object> current = rows.getFirst();
        String previous = String.valueOf(current.get("status"));
        boolean allowed = switch (previous) {
            case "WAITING" -> List.of("CALLED", "CANCELLED").contains(next);
            case "CALLED" -> List.of("WAITING", "IN_SERVICE", "RELEASED", "CANCELLED").contains(next);
            case "IN_SERVICE" -> List.of("COMPLETED", "RELEASED", "WAITING").contains(next);
            default -> false;
        };
        if (!allowed) return badRequest("Não é possível alterar de " + previous + " para " + next + ".");

        if ("PROFESSIONAL".equals(access.role())) {
            Object assigned = current.get("assigned_professional_id");
            if (assigned == null || !java.util.Objects.equals(access.professionalId(), assigned))
                return forbidden("Você só pode atualizar atendimentos atribuídos a você.");
        } else if (!List.of("CLINIC_ADMIN", "RECEPTION").contains(access.role())) return forbidden();

        int changed = jdbc.update("""
            UPDATE queue_entries
               SET status=?,
                   called_at=CASE WHEN ?='CALLED' THEN now() ELSE called_at END,
                   completed_at=CASE WHEN ? IN ('COMPLETED','RELEASED','CANCELLED') THEN now()
                                     WHEN ?='WAITING' THEN NULL ELSE completed_at END
             WHERE clinic_id=? AND id=? AND status=?
            """, next, next, next, next, clinic, id, previous);
        if (changed != 1) return conflict("O atendimento foi atualizado por outro usuário. Atualize a lista.");

        String event = switch (next) {
            case "CALLED" -> "CALLED";
            case "IN_SERVICE" -> "STARTED";
            case "COMPLETED" -> "COMPLETED";
            case "RELEASED" -> "RELEASED";
            case "CANCELLED" -> "CANCELLED";
            default -> "RETURNED";
        };
        audit(id, event, previous, next, (UUID) current.get("room_id"), (UUID) current.get("room_id"),
            (UUID) current.get("assigned_professional_id"), (UUID) current.get("assigned_professional_id"),
            "Situação alterada de " + previous + " para " + next, access.membershipId());
        return ResponseEntity.ok(Map.of("id", id, "status", next, "message", "Situação do atendimento atualizada."));
    }

    @PostMapping("/{id}/recall")
    @Transactional
    public ResponseEntity<?> recall(Authentication auth, @PathVariable UUID id) {
        Access access = access(auth);
        if (access == null || !List.of("CLINIC_ADMIN", "RECEPTION").contains(access.role()))
            return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT status, room_id, assigned_professional_id
              FROM queue_entries WHERE clinic_id=? AND id=? FOR UPDATE
            """, clinic, id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String,Object> current = rows.getFirst();
        if (!"CALLED".equals(String.valueOf(current.get("status"))))
            return badRequest("Somente um paciente já chamado pode ter a chamada repetida.");
        audit(id, "RECALLED", "CALLED", "CALLED", (UUID) current.get("room_id"),
            (UUID) current.get("room_id"), (UUID) current.get("assigned_professional_id"),
            (UUID) current.get("assigned_professional_id"), "Chamada repetida pela recepção", access.membershipId());
        return ResponseEntity.ok(Map.of("id", id, "message", "Chamada registrada novamente."));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> history(Authentication auth, @PathVariable UUID id) {
        Access access = access(auth);
        if (access == null) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        if (count("SELECT count(*) FROM queue_entries WHERE clinic_id=? AND id=?", clinic, id) == 0)
            return ResponseEntity.notFound().build();
        if ("PROFESSIONAL".equals(access.role())
                && count("SELECT count(*) FROM queue_entries WHERE clinic_id=? AND id=? AND assigned_professional_id=?",
                    clinic, id, access.professionalId()) == 0) return forbidden();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT e.id,e.event_type,e.previous_status,e.new_status,
                   old_room.name AS previous_room_name,new_room.name AS new_room_name,
                   old_prof.display_name AS previous_professional_name,
                   new_prof.display_name AS new_professional_name,
                   e.detail,e.created_at,u.full_name AS actor_name
              FROM queue_entry_events e
              JOIN clinic_memberships m ON m.clinic_id=e.clinic_id AND m.id=e.actor_membership_id
              JOIN app_users u ON u.id=m.user_id
              LEFT JOIN clinic_rooms old_room ON old_room.clinic_id=e.clinic_id AND old_room.id=e.previous_room_id
              LEFT JOIN clinic_rooms new_room ON new_room.clinic_id=e.clinic_id AND new_room.id=e.new_room_id
              LEFT JOIN professionals old_prof ON old_prof.clinic_id=e.clinic_id AND old_prof.id=e.previous_professional_id
              LEFT JOIN professionals new_prof ON new_prof.clinic_id=e.clinic_id AND new_prof.id=e.new_professional_id
             WHERE e.clinic_id=? AND e.queue_entry_id=?
             ORDER BY e.created_at DESC LIMIT 100
            """, clinic, id));
    }

    private void audit(UUID queueId, String event, String previous, String next,
                       UUID oldRoom, UUID newRoom, UUID oldProfessional, UUID newProfessional,
                       String detail, UUID actor) {
        jdbc.update("""
            INSERT INTO queue_entry_events
                (clinic_id,queue_entry_id,event_type,previous_status,new_status,
                 previous_room_id,new_room_id,previous_professional_id,new_professional_id,detail,actor_membership_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, TenantContext.requireClinicId(), queueId, event, previous, next,
            oldRoom, newRoom, oldProfessional, newProfessional, detail, actor);
    }

    private Access access(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT m.id AS membership_id,m.role,p.id AS professional_id
              FROM clinic_memberships m
              JOIN app_users u ON u.id=m.user_id
              LEFT JOIN professionals p ON p.clinic_id=m.clinic_id AND p.membership_id=m.id AND p.status='ACTIVE'
             WHERE lower(u.email)=lower(?) AND m.clinic_id=?
               AND m.status='ACTIVE' AND u.status='ACTIVE'
            """, auth.getName(), TenantContext.requireClinicId());
        if (rows.isEmpty()) return null;
        Map<String,Object> row = rows.getFirst();
        String role = String.valueOf(row.get("role"));
        if (!List.of("CLINIC_ADMIN", "RECEPTION", "PROFESSIONAL").contains(role)) return null;
        return new Access((UUID) row.get("membership_id"), role, (UUID) row.get("professional_id"));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private ResponseEntity<Map<String,String>> forbidden() {
        return forbidden("Seu perfil não tem permissão para esta operação.");
    }
    private ResponseEntity<Map<String,String>> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", message));
    }
    private ResponseEntity<Map<String,String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
    private ResponseEntity<Map<String,String>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message));
    }

    public record StatusRequest(String status) {}
    public record AssignmentRequest(UUID roomId, UUID professionalId) {}
    private record Access(UUID membershipId, String role, UUID professionalId) {}
}
