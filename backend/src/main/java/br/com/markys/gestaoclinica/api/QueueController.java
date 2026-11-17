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
               AND (? = '' OR p.full_name ILIKE ? ESCAPE '\\'
                    OR COALESCE(q.service_label,'') ILIKE ? ESCAPE '\\'
                    OR COALESCE(pr.display_name,'') ILIKE ? ESCAPE '\\'
                    OR COALESCE(r.name,'') ILIKE ? ESCAPE '\\')
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
