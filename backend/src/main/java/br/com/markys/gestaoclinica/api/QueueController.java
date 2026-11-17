package br.com.markys.gestaoclinica.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/queue")
public class QueueController {
    private final JdbcTemplate jdbc;

    public QueueController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<?> list(
            Authentication auth,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "true") boolean today) {
        if (!canAccess(auth)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Seu perfil não pode acessar a lista de atendimentos."));
        }

        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        if (!normalizedStatus.isEmpty() && !List.of(
                "WAITING", "CALLED", "IN_SERVICE", "COMPLETED", "RELEASED", "CANCELLED"
        ).contains(normalizedStatus)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Filtro de situação inválido."));
        }

        String term = search == null ? "" : search.trim();
        if (term.length() > 120) term = term.substring(0, 120);
        String pattern = "%" + term + "%";

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT q.id,
                   q.arrived_at,
                   q.called_at,
                   q.completed_at,
                   q.status,
                   q.priority,
                   q.service_label,
                   p.id AS patient_id,
                   p.full_name AS patient_name,
                   p.birth_date,
                   r.name AS room_name,
                   pr.display_name AS professional_name
              FROM queue_entries q
              JOIN patients p ON p.clinic_id=q.clinic_id AND p.id=q.patient_id
              LEFT JOIN clinic_rooms r ON r.clinic_id=q.clinic_id AND r.id=q.room_id
              LEFT JOIN professionals pr
                ON pr.clinic_id=q.clinic_id AND pr.id=q.assigned_professional_id
             WHERE q.clinic_id=?
               AND (? = '' OR q.status=?)
               AND (? = '' OR p.full_name ILIKE ?
                    OR COALESCE(q.service_label,'') ILIKE ?
                    OR COALESCE(pr.display_name,'') ILIKE ?
                    OR COALESCE(r.name,'') ILIKE ?)
               AND ((? = TRUE AND q.arrived_at >= CURRENT_DATE
                                      AND q.arrived_at < CURRENT_DATE + INTERVAL '1 day')
                    OR (? = FALSE AND q.arrived_at >= CURRENT_DATE - INTERVAL '30 days'))
             ORDER BY
               CASE q.status
                 WHEN 'IN_SERVICE' THEN 0
                 WHEN 'CALLED' THEN 1
                 WHEN 'WAITING' THEN 2
                 WHEN 'COMPLETED' THEN 3
                 WHEN 'RELEASED' THEN 4
                 ELSE 5
               END,
               q.arrived_at ASC
             LIMIT 300
            """, TenantContext.requireClinicId(),
            normalizedStatus, normalizedStatus,
            term, pattern, pattern, pattern, pattern,
            today, today);

        return ResponseEntity.ok(rows);
    }


    @org.springframework.web.bind.annotation.PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            Authentication auth,
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
            @org.springframework.web.bind.annotation.RequestBody StatusRequest body) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sessão expirada."));
        }
        String next = body == null || body.status() == null ? "" : body.status().trim().toUpperCase();
        if (!List.of("CALLED", "IN_SERVICE", "COMPLETED", "RELEASED", "CANCELLED", "WAITING").contains(next)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Situação de atendimento inválida."));
        }
        java.util.UUID clinic = TenantContext.requireClinicId();
        List<Map<String, Object>> memberships = jdbc.queryForList("""
            SELECT m.id, m.role
              FROM clinic_memberships m
              JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=?
               AND m.status='ACTIVE' AND u.status='ACTIVE'
            """, auth.getName(), clinic);
        if (memberships.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Acesso não permitido."));
        }
        Map<String, Object> membership = memberships.getFirst();
        String role = String.valueOf(membership.get("role"));
        List<Map<String, Object>> currentRows = jdbc.queryForList("""
            SELECT id, status, assigned_professional_id
              FROM queue_entries WHERE clinic_id=? AND id=?
            """, clinic, id);
        if (currentRows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> current = currentRows.getFirst();
        String previous = String.valueOf(current.get("status"));
        boolean allowed = switch (previous) {
            case "WAITING" -> List.of("CALLED", "CANCELLED").contains(next);
            case "CALLED" -> List.of("WAITING", "IN_SERVICE", "RELEASED", "CANCELLED").contains(next);
            case "IN_SERVICE" -> List.of("COMPLETED", "RELEASED", "WAITING").contains(next);
            default -> false;
        };
        if (!allowed) {
            return ResponseEntity.badRequest().body(Map.of("error", "Não é possível alterar de " + previous + " para " + next + "."));
        }
        if ("PROFESSIONAL".equals(role)) {
            Object assigned = current.get("assigned_professional_id");
            Integer owns = jdbc.queryForObject("""
                SELECT count(*) FROM professionals
                 WHERE clinic_id=? AND membership_id=? AND id=?
                """, Integer.class, clinic, membership.get("id"), assigned);
            if (assigned == null || owns == null || owns == 0) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Você só pode atualizar atendimentos atribuídos a você."));
            }
        } else if (!List.of("CLINIC_ADMIN", "RECEPTION").contains(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Seu perfil não pode alterar atendimentos."));
        }
        int changed = jdbc.update("""
            UPDATE queue_entries
               SET status=?,
                   called_at=CASE WHEN ?='CALLED' THEN now() ELSE called_at END,
                   completed_at=CASE WHEN ? IN ('COMPLETED','RELEASED','CANCELLED') THEN now()
                                     WHEN ?='WAITING' THEN NULL ELSE completed_at END
             WHERE clinic_id=? AND id=? AND status=?
            """, next, next, next, next, clinic, id, previous);
        if (changed != 1) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "O atendimento foi atualizado por outro usuário. Atualize a lista."));
        }
        return ResponseEntity.ok(Map.of("id", id, "status", next, "message", "Situação do atendimento atualizada."));
    }

    public record StatusRequest(String status) {}

    private boolean canAccess(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        return jdbc.queryForList("""
            SELECT m.role
              FROM clinic_memberships m
              JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?)
               AND m.clinic_id=?
               AND m.status='ACTIVE'
               AND u.status='ACTIVE'
            """, String.class, auth.getName(), TenantContext.requireClinicId())
            .stream().anyMatch(role -> List.of("CLINIC_ADMIN", "RECEPTION", "PROFESSIONAL").contains(role));
    }
}
