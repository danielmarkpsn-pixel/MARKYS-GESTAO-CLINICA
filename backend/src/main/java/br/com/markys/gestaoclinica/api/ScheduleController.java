package br.com.markys.gestaoclinica.api;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/schedule")
public class ScheduleController {
    private static final List<String> STATUSES = List.of(
        "SCHEDULED", "CONFIRMED", "CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"
    );
    private final JdbcTemplate jdbc;

    public ScheduleController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/resources")
    public ResponseEntity<?> resources(Authentication auth) {
        Access access = access(auth);
        if (access == null) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        List<Map<String,Object>> professionals = jdbc.queryForList("""
            SELECT p.id, p.display_name, p.specialty
              FROM professionals p
             WHERE p.clinic_id=? AND p.status='ACTIVE'
               AND (? <> 'PROFESSIONAL' OR p.membership_id=?)
             ORDER BY lower(p.display_name)
            """, clinic, access.role(), access.membershipId());
        List<Map<String,Object>> patients = jdbc.queryForList("""
            SELECT id, full_name FROM patients
             WHERE clinic_id=? AND status='ACTIVE'
             ORDER BY lower(full_name) LIMIT 1000
            """, clinic);
        List<Map<String,Object>> rooms = jdbc.queryForList("""
            SELECT id, name FROM clinic_rooms
             WHERE clinic_id=? AND status='ACTIVE' ORDER BY lower(name)
            """, clinic);
        return ResponseEntity.ok(Map.of("professionals", professionals, "patients", patients, "rooms", rooms));
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth,
            @RequestParam String from, @RequestParam String to,
            @RequestParam(required=false) UUID professionalId,
            @RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="") String search) {
        Access access = access(auth);
        if (access == null) return forbidden();
        LocalDateTime start, end;
        try { start=LocalDate.parse(from).atStartOfDay(); end=LocalDate.parse(to).plusDays(1).atStartOfDay(); }
        catch (RuntimeException ex) { return badRequest("Informe um período válido no formato AAAA-MM-DD."); }
        if (!end.isAfter(start) || ChronoUnit.DAYS.between(start,end)>93)
            return badRequest("O período deve ter no máximo 92 dias.");
        String state=status.trim().toUpperCase(Locale.ROOT);
        if (!state.isEmpty() && !STATUSES.contains(state)) return badRequest("Situação de agendamento inválida.");
        String term=search==null?"":search.trim();
        if(term.length()>100)term=term.substring(0,100);
        UUID clinic=TenantContext.requireClinicId();
        UUID ownProfessional=access.professionalId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT a.id,a.starts_at,a.ends_at,a.status,a.reason,a.cancellation_reason,
                   a.patient_id,p.full_name AS patient_name,
                   a.professional_id,pr.display_name AS professional_name,pr.specialty,
                   a.room_id,r.name AS room_name
              FROM appointments a
              JOIN patients p ON p.clinic_id=a.clinic_id AND p.id=a.patient_id
              JOIN professionals pr ON pr.clinic_id=a.clinic_id AND pr.id=a.professional_id
              LEFT JOIN clinic_rooms r ON r.clinic_id=a.clinic_id AND r.id=a.room_id
             WHERE a.clinic_id=? AND a.starts_at < ?::timestamp AT TIME ZONE 'UTC'
               AND a.ends_at > ?::timestamp AT TIME ZONE 'UTC'
               AND (?='' OR a.status=?) AND (?::uuid IS NULL OR a.professional_id=?)
               AND (?='' OR p.full_name ILIKE ? OR pr.display_name ILIKE ?
                    OR COALESCE(a.reason,'') ILIKE ?)
               AND (?::uuid IS NULL OR a.professional_id=?)
             ORDER BY a.starts_at LIMIT 1500
            """, clinic, end, start, state, state, professionalId, professionalId,
                term, "%"+term+"%", "%"+term+"%", "%"+term+"%", ownProfessional, ownProfessional);
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/rules")
    public ResponseEntity<?> rules(Authentication auth, @RequestParam(required=false) UUID professionalId) {
        Access access=access(auth);
        if(access==null)return forbidden();
        UUID selected=professionalId==null?access.professionalId():professionalId;
        if("PROFESSIONAL".equals(access.role())) selected=access.professionalId();
        if(selected==null)return badRequest("Selecione um profissional.");
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT id,professional_id,weekday,starts_local,ends_local,slot_minutes,active
              FROM professional_schedule_rules
             WHERE clinic_id=? AND professional_id=?
             ORDER BY weekday,starts_local
            """,TenantContext.requireClinicId(),selected));
    }

    @PostMapping("/rules")
    @Transactional
    public ResponseEntity<?> createRule(Authentication auth,@Valid @RequestBody RuleRequest body) {
        Access access=access(auth);
        if(access==null || !"CLINIC_ADMIN".equals(access.role()))return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(!professionalActive(clinic,body.professionalId()))return badRequest("Profissional não encontrado ou inativo.");
        if(!body.endsLocal().isAfter(body.startsLocal()))return badRequest("O horário final deve ser posterior ao inicial.");
        Integer overlap=jdbc.queryForObject("""
            SELECT count(*) FROM professional_schedule_rules
             WHERE clinic_id=? AND professional_id=? AND weekday=? AND active=true
               AND starts_local < ? AND ends_local > ?
            """,Integer.class,clinic,body.professionalId(),body.weekday(),body.endsLocal(),body.startsLocal());
        if(overlap!=null && overlap>0)return conflict("Este horário sobrepõe outro período de atendimento do profissional.");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO professional_schedule_rules
                (id,clinic_id,professional_id,weekday,starts_local,ends_local,slot_minutes)
            VALUES (?,?,?,?,?,?,?)
            """,id,clinic,body.professionalId(),body.weekday(),body.startsLocal(),body.endsLocal(),body.slotMinutes());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"message","Horário semanal cadastrado."));
    }

    @DeleteMapping("/rules/{id}")
    @Transactional
    public ResponseEntity<?> deleteRule(Authentication auth,@PathVariable UUID id) {
        Access access=access(auth);
        if(access==null || !"CLINIC_ADMIN".equals(access.role()))return forbidden();
        int changed=jdbc.update("DELETE FROM professional_schedule_rules WHERE clinic_id=? AND id=?",
            TenantContext.requireClinicId(),id);
        return changed==0?ResponseEntity.notFound().build():ResponseEntity.noContent().build();
    }

    @PostMapping("/blocks")
    @Transactional
    public ResponseEntity<?> createBlock(Authentication auth,@Valid @RequestBody BlockRequest body) {
        Access access=access(auth);
        if(access==null || !"CLINIC_ADMIN".equals(access.role()))return forbidden();
        if(!body.endsAt().isAfter(body.startsAt()))return badRequest("O fim do bloqueio deve ser posterior ao início.");
        if(body.professionalId()==null && body.roomId()==null)return badRequest("Selecione um profissional ou consultório para bloquear.");
        UUID clinic=TenantContext.requireClinicId();
        if(body.professionalId()!=null && !professionalActive(clinic,body.professionalId()))return badRequest("Profissional inválido ou inativo.");
        if(body.roomId()!=null && jdbc.queryForObject("SELECT count(*) FROM clinic_rooms WHERE clinic_id=? AND id=? AND status='ACTIVE'",Integer.class,clinic,body.roomId())==0)return badRequest("Consultório inválido ou inativo.");
        if(body.professionalId()!=null) lockResource(clinic,"P:"+body.professionalId(),body.startsAt().toLocalDate());
        if(body.roomId()!=null) lockResource(clinic,"R:"+body.roomId(),body.startsAt().toLocalDate());
        if(body.professionalId()!=null && countConflict(clinic,body.professionalId(),null,body.startsAt(),body.endsAt())>0)return conflict("Há agendamento no período deste profissional.");
        if(body.roomId()!=null && countConflict(clinic,null,body.roomId(),body.startsAt(),body.endsAt())>0)return conflict("Há agendamento no período deste consultório.");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO schedule_blocks(id,clinic_id,professional_id,room_id,starts_at,ends_at,reason,created_by_membership_id)
            VALUES(?,?,?,?,?,?,?,?)
            """,id,clinic,body.professionalId(),body.roomId(),body.startsAt(),body.endsAt(),body.reason().trim(),access.membershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"message","Bloqueio cadastrado."));
    }

    @GetMapping("/blocks")
    public ResponseEntity<?> blocks(Authentication auth,@RequestParam String from,@RequestParam String to) {
        Access access=access(auth);
        if(access==null)return forbidden();
        LocalDateTime start,end;
        try{start=LocalDate.parse(from).atStartOfDay();end=LocalDate.parse(to).plusDays(1).atStartOfDay();}
        catch(RuntimeException ex){return badRequest("Período inválido.");}
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT b.id,b.starts_at,b.ends_at,b.reason,b.professional_id,p.display_name AS professional_name,
                   b.room_id,r.name AS room_name
              FROM schedule_blocks b
              LEFT JOIN professionals p ON p.clinic_id=b.clinic_id AND p.id=b.professional_id
              LEFT JOIN clinic_rooms r ON r.clinic_id=b.clinic_id AND r.id=b.room_id
             WHERE b.clinic_id=? AND b.starts_at < ?::timestamp AT TIME ZONE 'UTC'
               AND b.ends_at > ?::timestamp AT TIME ZONE 'UTC'
             ORDER BY b.starts_at
            """,TenantContext.requireClinicId(),end,start));
    }

    @DeleteMapping("/blocks/{id}")
    @Transactional
    public ResponseEntity<?> deleteBlock(Authentication auth,@PathVariable UUID id) {
        Access access=access(auth);
        if(access==null || !"CLINIC_ADMIN".equals(access.role()))return forbidden();
        int changed=jdbc.update("DELETE FROM schedule_blocks WHERE clinic_id=? AND id=?",TenantContext.requireClinicId(),id);
        return changed==0?ResponseEntity.notFound().build():ResponseEntity.noContent().build();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(Authentication auth,@Valid @RequestBody AppointmentRequest body) {
        Access access=access(auth);
        if(access==null || !List.of("CLINIC_ADMIN","RECEPTION").contains(access.role()))return forbidden();
        return saveAppointment(access,null,body);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(Authentication auth,@PathVariable UUID id,@Valid @RequestBody AppointmentRequest body) {
        Access access=access(auth);
        if(access==null || !List.of("CLINIC_ADMIN","RECEPTION").contains(access.role()))return forbidden();
        return saveAppointment(access,id,body);
    }

    private ResponseEntity<?> saveAppointment(Access access,UUID id,AppointmentRequest body) {
        if(!body.endsAt().isAfter(body.startsAt()))return badRequest("O horário final deve ser posterior ao inicial.");
        long minutes=Duration.between(body.startsAt(),body.endsAt()).toMinutes();
        if(minutes<5 || minutes>480)return badRequest("A duração deve ficar entre 5 minutos e 8 horas.");
        if(body.startsAt().isBefore(OffsetDateTime.now().minusMinutes(1)))return badRequest("Não é permitido criar ou remarcar agendamento no passado.");
        UUID clinic=TenantContext.requireClinicId();
        if(!professionalActive(clinic,body.professionalId()))return badRequest("Selecione um profissional ativo.");
        if(jdbc.queryForObject("SELECT count(*) FROM patients WHERE clinic_id=? AND id=? AND status='ACTIVE'",Integer.class,clinic,body.patientId())==0)return badRequest("Selecione um paciente ativo.");
        if(body.roomId()!=null && jdbc.queryForObject("SELECT count(*) FROM clinic_rooms WHERE clinic_id=? AND id=? AND status='ACTIVE'",Integer.class,clinic,body.roomId())==0)return badRequest("Selecione um consultório ativo.");
        if(id!=null && jdbc.queryForObject("SELECT count(*) FROM appointments WHERE clinic_id=? AND id=?",Integer.class,clinic,id)==0)return ResponseEntity.notFound().build();

        lockResource(clinic,"P:"+body.professionalId(),body.startsAt().toLocalDate());
        if(body.roomId()!=null)lockResource(clinic,"R:"+body.roomId(),body.startsAt().toLocalDate());
        String timezone=jdbc.queryForObject("SELECT timezone FROM clinics WHERE id=?",String.class,clinic);
        ZoneId zone;
        try{zone=ZoneId.of(timezone);}catch(RuntimeException ex){zone=ZoneId.of("America/Manaus");}
        ZonedDateTime localStart=body.startsAt().atZoneSameInstant(zone), localEnd=body.endsAt().atZoneSameInstant(zone);
        if(!localStart.toLocalDate().equals(localEnd.toLocalDate()))return badRequest("O agendamento deve terminar no mesmo dia local.");
        int weekday=localStart.getDayOfWeek().getValue();
        List<Map<String,Object>> rules=jdbc.queryForList("""
            SELECT starts_local,ends_local,slot_minutes FROM professional_schedule_rules
             WHERE clinic_id=? AND professional_id=? AND weekday=? AND active=true
               AND starts_local<=? AND ends_local>=?
            """,clinic,body.professionalId(),weekday,localStart.toLocalTime(),localEnd.toLocalTime());
        if(rules.isEmpty())return conflict("Não há horário de atendimento cadastrado para este profissional neste dia e período.");
        boolean aligned=rules.stream().anyMatch(rule->{
            LocalTime start=((java.sql.Time)rule.get("starts_local")).toLocalTime();
            int slot=((Number)rule.get("slot_minutes")).intValue();
            long delta=ChronoUnit.MINUTES.between(start,localStart.toLocalTime());
            return delta>=0 && delta%slot==0 && minutes%slot==0;
        });
        if(!aligned)return badRequest("O horário não respeita os intervalos definidos na agenda do profissional.");

        if(countConflict(clinic,body.professionalId(),null,body.startsAt(),body.endsAt(),id)>0)return conflict("O profissional já possui agendamento neste intervalo.");
        if(body.roomId()!=null && countConflict(clinic,null,body.roomId(),body.startsAt(),body.endsAt(),id)>0)return conflict("O consultório já possui agendamento neste intervalo.");
        if(body.professionalId()!=null && blockConflict(clinic,body.professionalId(),null,body.startsAt(),body.endsAt())>0)return conflict("O profissional possui bloqueio neste intervalo.");
        if(body.roomId()!=null && blockConflict(clinic,null,body.roomId(),body.startsAt(),body.endsAt())>0)return conflict("O consultório possui bloqueio neste intervalo.");

        if(id==null){
            UUID appointmentId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO appointments(id,clinic_id,patient_id,professional_id,room_id,starts_at,ends_at,reason,created_by_membership_id,updated_by_membership_id)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,appointmentId,clinic,body.patientId(),body.professionalId(),body.roomId(),body.startsAt(),body.endsAt(),clean(body.reason()),access.membershipId(),access.membershipId());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",appointmentId,"status","SCHEDULED","message","Agendamento criado com sucesso."));
        }
        int changed=jdbc.update("""
            UPDATE appointments SET patient_id=?,professional_id=?,room_id=?,starts_at=?,ends_at=?,reason=?,
                updated_by_membership_id=?,updated_at=now()
             WHERE clinic_id=? AND id=? AND status IN ('SCHEDULED','CONFIRMED')
            """,body.patientId(),body.professionalId(),body.roomId(),body.startsAt(),body.endsAt(),clean(body.reason()),access.membershipId(),clinic,id);
        if(changed==0)return conflict("Este agendamento não pode mais ser editado. Atualize a agenda.");
        return ResponseEntity.ok(Map.of("id",id,"message","Agendamento atualizado."));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> status(Authentication auth,@PathVariable UUID id,@Valid @RequestBody StatusRequest body) {
        Access access=access(auth);
        if(access==null)return forbidden();
        String next=body.status().trim().toUpperCase(Locale.ROOT);
        if(!STATUSES.contains(next))return badRequest("Situação inválida.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT status,professional_id FROM appointments WHERE clinic_id=? AND id=?",clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        String previous=String.valueOf(rows.getFirst().get("status"));
        UUID professional=(UUID)rows.getFirst().get("professional_id");
        if("PROFESSIONAL".equals(access.role()) && !Objects.equals(professional,access.professionalId()))return forbidden();
        if(!List.of("CLINIC_ADMIN","RECEPTION","PROFESSIONAL").contains(access.role()))return forbidden();
        boolean allowed=switch(previous){
            case "SCHEDULED" -> List.of("CONFIRMED","CHECKED_IN","CANCELLED","NO_SHOW").contains(next);
            case "CONFIRMED" -> List.of("CHECKED_IN","CANCELLED","NO_SHOW").contains(next);
            case "CHECKED_IN" -> List.of("COMPLETED","CANCELLED").contains(next);
            default -> false;
        };
        if(!allowed)return badRequest("Não é possível alterar de "+previous+" para "+next+".");
        if("CANCELLED".equals(next) && (body.cancellationReason()==null || body.cancellationReason().isBlank()))return badRequest("Informe o motivo do cancelamento.");
        int changed=jdbc.update("""
            UPDATE appointments SET status=?,cancellation_reason=?,updated_by_membership_id=?,updated_at=now()
             WHERE clinic_id=? AND id=? AND status=?
            """,next,"CANCELLED".equals(next)?body.cancellationReason().trim():null,access.membershipId(),clinic,id,previous);
        if(changed==0)return conflict("O agendamento foi atualizado por outro usuário.");
        return ResponseEntity.ok(Map.of("id",id,"status",next,"message","Situação atualizada."));
    }

    private int countConflict(UUID clinic,UUID professional,UUID room,OffsetDateTime start,OffsetDateTime end) {
        return countConflict(clinic,professional,room,start,end,null);
    }
    private int countConflict(UUID clinic,UUID professional,UUID room,OffsetDateTime start,OffsetDateTime end,UUID exclude) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM appointments
             WHERE clinic_id=? AND status NOT IN ('CANCELLED','NO_SHOW')
               AND starts_at < ? AND ends_at > ? AND (?::uuid IS NULL OR id<>?)
               AND (?::uuid IS NULL OR professional_id=?)
               AND (?::uuid IS NULL OR room_id=?)
            """,Integer.class,clinic,end,start,exclude,exclude,professional,professional,room,room);
    }
    private int blockConflict(UUID clinic,UUID professional,UUID room,OffsetDateTime start,OffsetDateTime end) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM schedule_blocks WHERE clinic_id=?
              AND starts_at < ? AND ends_at > ?
              AND (?::uuid IS NULL OR professional_id=?)
              AND (?::uuid IS NULL OR room_id=?)
            """,Integer.class,clinic,end,start,professional,professional,room,room);
    }
    private void lockResource(UUID clinic,String resource,LocalDate date) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,
            clinic+":"+resource+":"+date);
    }
    private boolean professionalActive(UUID clinic,UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM professionals WHERE clinic_id=? AND id=? AND status='ACTIVE'",Integer.class,clinic,id)>0;
    }
    private Access access(Authentication auth) {
        if(auth==null || !auth.isAuthenticated())return null;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id,m.role,p.id AS professional_id
              FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
              LEFT JOIN professionals p ON p.clinic_id=m.clinic_id AND p.membership_id=m.id AND p.status='ACTIVE'
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
            """,auth.getName(),TenantContext.requireClinicId());
        if(rows.isEmpty())return null;
        Map<String,Object> row=rows.getFirst();
        return new Access((UUID)row.get("id"),String.valueOf(row.get("role")),(UUID)row.get("professional_id"));
    }
    private static String clean(String value){return value==null||value.isBlank()?null:value.trim();}
    private ResponseEntity<Map<String,String>> forbidden(){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","Seu perfil não possui permissão para esta operação."));}
    private ResponseEntity<Map<String,String>> badRequest(String message){return ResponseEntity.badRequest().body(Map.of("error",message));}
    private ResponseEntity<Map<String,String>> conflict(String message){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",message));}
    private record Access(UUID membershipId,String role,UUID professionalId){}
    public record RuleRequest(@NotNull UUID professionalId,@Min(1) @Max(7) int weekday,
        @NotNull LocalTime startsLocal,@NotNull LocalTime endsLocal,@Min(5) @Max(240) int slotMinutes){}
    public record BlockRequest(UUID professionalId,UUID roomId,@NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt,@NotBlank @Size(max=180) String reason){}
    public record AppointmentRequest(@NotNull UUID patientId,@NotNull UUID professionalId,UUID roomId,
        @NotNull OffsetDateTime startsAt,@NotNull OffsetDateTime endsAt,@Size(max=180) String reason){}
    public record StatusRequest(@NotBlank String status,@Size(max=240) String cancellationReason){}
}
