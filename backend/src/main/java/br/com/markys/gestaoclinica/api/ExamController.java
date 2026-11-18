package br.com.markys.gestaoclinica.api;

import java.time.*;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import br.com.markys.gestaoclinica.security.PatientDataProtection;
import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/exams")
public class ExamController {
    private static final List<String> STATUSES = List.of("REQUESTED","SCHEDULED","IN_PROGRESS","COMPLETED","CANCELLED");
    private final JdbcTemplate jdbc;
    private final PatientDataProtection protection;

    public ExamController(JdbcTemplate jdbc, PatientDataProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @GetMapping("/resources")
    public ResponseEntity<?> resources(Authentication auth) {
        Access a=access(auth);
        if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if("PROFESSIONAL".equals(a.role())) {
            return ResponseEntity.ok(Map.of(
                "role",a.role(),
                "professionals",List.of(),
                "patients",List.of(),
                "exams",List.of(),
                "appointments",List.of()
            ));
        }
        return ResponseEntity.ok(Map.of(
            "role",a.role(),
            "patients",jdbc.queryForList("""
                SELECT id,full_name FROM patients WHERE clinic_id=? AND status='ACTIVE'
                ORDER BY lower(full_name) LIMIT 1000
            """,clinic),
            "exams",jdbc.queryForList("""
                SELECT e.id,e.category,e.name,e.room_id,r.name AS room_name
                  FROM exam_catalog e JOIN clinic_rooms r ON r.clinic_id=e.clinic_id AND r.id=e.room_id
                 WHERE e.clinic_id=? AND e.active=true AND r.status='ACTIVE'
                 ORDER BY e.category,e.sort_order,lower(e.name)
            """,clinic),
            "professionals",jdbc.queryForList("""
                SELECT p.id,p.display_name,p.specialty FROM professionals p
                 JOIN clinic_memberships m ON m.clinic_id=p.clinic_id AND m.id=p.membership_id
                 JOIN app_users u ON u.id=m.user_id
                 WHERE p.clinic_id=? AND p.status='ACTIVE' AND m.status='ACTIVE' AND u.status='ACTIVE'
                 ORDER BY lower(p.display_name)
            """,clinic),
            "appointments",jdbc.queryForList("""
                SELECT a.id,a.patient_id,a.professional_id,a.starts_at,p.full_name AS patient_name,
                       pr.display_name AS professional_name
                  FROM appointments a JOIN patients p ON p.clinic_id=a.clinic_id AND p.id=a.patient_id
                  JOIN professionals pr ON pr.clinic_id=a.clinic_id AND pr.id=a.professional_id
                 WHERE a.clinic_id=? AND a.status IN ('SCHEDULED','CONFIRMED','CHECKED_IN')
                   AND a.starts_at >= now()-interval '30 days'
                 ORDER BY a.starts_at DESC LIMIT 500
            """,clinic)
        ));
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth,
            @RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="") String category,
            @RequestParam(defaultValue="") String from,
            @RequestParam(defaultValue="") String to,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        Access a=access(auth);
        if(a==null)return forbidden();
        if(page<1||size<1||size>100)return badRequest("Paginação inválida.");
        String state=status.trim().toUpperCase(Locale.ROOT);
        if(!"ALL".equals(state)&&!STATUSES.contains(state))return badRequest("Situação de exame inválida.");
        String cat=category.trim().toUpperCase(Locale.ROOT);
        if(!cat.isEmpty()&&!List.of("RAIO_X","LABORATORIO","AUDIOMETRIA","COMPLEMENTAR","OUTROS").contains(cat))
            return badRequest("Categoria de exame inválida.");
        LocalDate start=null,end=null;
        try { if(!from.isBlank())start=LocalDate.parse(from); if(!to.isBlank())end=LocalDate.parse(to); }
        catch(RuntimeException ex){return badRequest("Informe um período válido.");}
        if(start!=null&&end!=null&&(end.isBefore(start)||end.toEpochDay()-start.toEpochDay()>366))
            return badRequest("O período deve ter no máximo 366 dias.");
        String term=search==null?"":search.trim();
        if(term.length()>120)term=term.substring(0,120);
        UUID clinic=TenantContext.requireClinicId();
        String base="""
            FROM exam_orders o
            JOIN patients p ON p.clinic_id=o.clinic_id AND p.id=o.patient_id
            LEFT JOIN professionals pr ON pr.clinic_id=o.clinic_id AND pr.id=o.professional_id
            WHERE o.clinic_id=?
              AND (?='ALL' OR o.status=?)
              AND (?='' OR o.exam_category_snapshot=?)
              AND (?='' OR p.full_name ILIKE ? OR o.exam_name_snapshot ILIKE ?
                   OR COALESCE(pr.display_name,'') ILIKE ?)
              AND (?::date IS NULL OR o.requested_at::date >= ?)
              AND (?::date IS NULL OR o.requested_at::date <= ?)
              AND (?::uuid IS NULL OR o.professional_id=?)
            """;
        Object[] args={clinic,state,state,cat,cat,term,"%"+term+"%","%"+term+"%","%"+term+"%",
            start,start,end,end,a.professionalId(),a.professionalId()};
        Integer total=jdbc.queryForObject("SELECT count(*) "+base,Integer.class,args);
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT o.id,o.exam_id,o.exam_name_snapshot AS exam_name,o.exam_category_snapshot AS category,
                   o.room_name_snapshot AS room_name,o.patient_id,p.full_name AS patient_name,
                   o.professional_id,pr.display_name AS professional_name,o.appointment_id,o.queue_entry_id,
                   o.priority,o.status,o.scheduled_at,o.requested_at,o.started_at,o.completed_at
            """+base+"""
            ORDER BY CASE o.status WHEN 'IN_PROGRESS' THEN 0 WHEN 'REQUESTED' THEN 1
                 WHEN 'SCHEDULED' THEN 2 WHEN 'COMPLETED' THEN 3 ELSE 4 END,
                 COALESCE(o.scheduled_at,o.requested_at) DESC
            LIMIT ? OFFSET ?
            """,append(args,size,(page-1)*size));
        return ResponseEntity.ok(Map.of("items",rows,"page",page,"size",size,
            "total",total==null?0:total,"pages",total==null?0:(int)Math.ceil(total/(double)size)));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(Authentication auth,@Valid @RequestBody CreateRequest body) {
        Access a=access(auth);
        if(a==null||"PROFESSIONAL".equals(a.role()))return forbidden();
        String priority=body.priority().trim().toUpperCase(Locale.ROOT);
        if(!List.of("NORMAL","URGENT").contains(priority))return badRequest("Prioridade de exame inválida.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> exam=jdbc.queryForList("""
            SELECT e.id,e.name,e.category,e.room_id,r.name AS room_name
              FROM exam_catalog e JOIN clinic_rooms r ON r.clinic_id=e.clinic_id AND r.id=e.room_id
             WHERE e.clinic_id=? AND e.id=? AND e.active=true AND r.status='ACTIVE'
        """,clinic,body.examId());
        if(exam.isEmpty())return badRequest("Exame não encontrado ou inativo no catálogo desta clínica.");
        if(!activePatient(clinic,body.patientId()))return badRequest("Paciente não encontrado ou inativo.");
        UUID professional=body.professionalId();
        if(professional!=null&&!activeProfessional(clinic,professional))return badRequest("Profissional não encontrado ou inativo.");
        if(body.appointmentId()!=null){
            List<Map<String,Object>> ap=jdbc.queryForList("""
                SELECT patient_id,professional_id,status FROM appointments WHERE clinic_id=? AND id=?
            """,clinic,body.appointmentId());
            if(ap.isEmpty()||!body.patientId().equals(ap.getFirst().get("patient_id"))
                ||List.of("CANCELLED","NO_SHOW").contains(String.valueOf(ap.getFirst().get("status"))))
                return badRequest("O agendamento não pertence ao paciente ou não está disponível.");
            UUID appointmentProfessional=(UUID)ap.getFirst().get("professional_id");
            if(professional!=null&&!professional.equals(appointmentProfessional))
                return badRequest("O profissional informado difere do agendamento.");
            professional=appointmentProfessional;
        }
        if(body.queueEntryId()!=null){
            Integer q=jdbc.queryForObject("""
                SELECT count(*) FROM queue_entries WHERE clinic_id=? AND id=? AND patient_id=?
            """,Integer.class,clinic,body.queueEntryId(),body.patientId());
            if(q==null||q==0)return badRequest("O atendimento não pertence ao paciente selecionado.");
            Map<String,Object> e=exam.getFirst();
            jdbc.update("""
                INSERT INTO queue_entry_exams(clinic_id,queue_entry_id,exam_id,exam_name_snapshot,room_id,room_name_snapshot)
                VALUES(?,?,?,?,?,?) ON CONFLICT (clinic_id,queue_entry_id,exam_id) DO NOTHING
            """,clinic,body.queueEntryId(),body.examId(),e.get("name"),e.get("room_id"),e.get("room_name"));
        }
        if(body.scheduledAt()!=null&&body.scheduledAt().isBefore(OffsetDateTime.now().minusMinutes(5)))
            return badRequest("O horário agendado não pode estar no passado.");
        Map<String,Object> e=exam.getFirst();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO exam_orders(id,clinic_id,patient_id,exam_id,exam_name_snapshot,exam_category_snapshot,
                room_id,room_name_snapshot,appointment_id,queue_entry_id,professional_id,priority,status,
                scheduled_at,notes_ciphertext,requested_by_membership_id,updated_by_membership_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?, 'REQUESTED',?,?,?,?)
        """,id,clinic,body.patientId(),body.examId(),e.get("name"),e.get("category"),e.get("room_id"),
            e.get("room_name"),body.appointmentId(),body.queueEntryId(),professional,
            priority,body.scheduledAt(),
            encryptOptional(body.notes()),a.membershipId(),a.membershipId());
        record(clinic,id,"CREATED",null,"REQUESTED","Solicitação de exame criada",a.membershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"status","REQUESTED","message","Exame solicitado."));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(Authentication auth,@PathVariable UUID id) {
        Access a=access(auth);
        if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT o.*,p.full_name AS patient_name,pr.display_name AS professional_name,
                   u.full_name AS requester_name
              FROM exam_orders o JOIN patients p ON p.clinic_id=o.clinic_id AND p.id=o.patient_id
              LEFT JOIN professionals pr ON pr.clinic_id=o.clinic_id AND pr.id=o.professional_id
              JOIN clinic_memberships m ON m.clinic_id=o.clinic_id AND m.id=o.requested_by_membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE o.clinic_id=? AND o.id=?
        """,clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        Map<String,Object> row=new LinkedHashMap<>(rows.getFirst());
        boolean canReadClinical="CLINIC_ADMIN".equals(a.role())
            ||("PROFESSIONAL".equals(a.role())&&Objects.equals(row.get("professional_id"),a.professionalId()));
        if("PROFESSIONAL".equals(a.role())&&!canReadClinical)return forbidden();
        byte[] notes=(byte[])row.remove("notes_ciphertext");
        byte[] result=(byte[])row.remove("result_ciphertext");
        row.remove("result_recorded_by_membership_id");
        row.put("notes",canReadClinical?decryptOptional(notes):null);
        row.put("result",canReadClinical?decryptOptional(result):null);
        if(!canReadClinical){row.remove("notes");row.remove("result");}
        return ResponseEntity.ok(row);
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> updateStatus(Authentication auth,@PathVariable UUID id,@Valid @RequestBody StatusRequest body) {
        Access a=access(auth);
        if(a==null)return forbidden();
        String next=body.status().trim().toUpperCase(Locale.ROOT);
        if(!STATUSES.contains(next))return badRequest("Situação de exame inválida.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT status,professional_id FROM exam_orders WHERE clinic_id=? AND id=? FOR UPDATE
        """,clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        String previous=String.valueOf(rows.getFirst().get("status"));
        UUID assigned=(UUID)rows.getFirst().get("professional_id");
        boolean professional="PROFESSIONAL".equals(a.role());
        if(professional&&(!Objects.equals(assigned,a.professionalId())
            ||!List.of("IN_PROGRESS","COMPLETED").contains(next)))return forbidden();
        if("RECEPTION".equals(a.role())&&!List.of("SCHEDULED","CANCELLED").contains(next))return forbidden();
        if(!professional&&!List.of("CLINIC_ADMIN","RECEPTION").contains(a.role()))return forbidden();
        boolean allowed=switch(previous){
            case "REQUESTED"->List.of("SCHEDULED","IN_PROGRESS","CANCELLED").contains(next);
            case "SCHEDULED"->List.of("IN_PROGRESS","CANCELLED").contains(next);
            case "IN_PROGRESS"->List.of("COMPLETED","CANCELLED").contains(next);
            default->false;
        };
        if(!allowed)return conflict("Não é possível alterar um exame de "+previous+" para "+next+".");
        if("COMPLETED".equals(next)){
            if(body.result()==null||body.result().trim().length()<3)
                return badRequest("Registre o resultado do exame antes de concluir.");
            if(body.result().length()>12000)return badRequest("O resultado excede o limite de 12.000 caracteres.");
            if(!protection.isConfigured())return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error","Proteção do resultado indisponível. Configure as chaves de proteção no servidor."));
        }
        if(body.scheduledAt()!=null&&body.scheduledAt().isBefore(OffsetDateTime.now().minusMinutes(5)))
            return badRequest("O horário agendado não pode estar no passado.");
        int changed=jdbc.update("""
            UPDATE exam_orders SET status=?,scheduled_at=COALESCE(?,scheduled_at),
                started_at=CASE WHEN ?='IN_PROGRESS' AND started_at IS NULL THEN now() ELSE started_at END,
                completed_at=CASE WHEN ?='COMPLETED' THEN now() ELSE completed_at END,
                cancelled_at=CASE WHEN ?='CANCELLED' THEN now() ELSE cancelled_at END,
                result_ciphertext=CASE WHEN ?='COMPLETED' THEN ? ELSE result_ciphertext END,
                result_recorded_by_membership_id=CASE WHEN ?='COMPLETED' THEN ? ELSE result_recorded_by_membership_id END,
                updated_by_membership_id=?,updated_at=now()
             WHERE clinic_id=? AND id=? AND status=?
        """,next,body.scheduledAt(),next,next,next,next,"COMPLETED".equals(next)?protection.encrypt(body.result().trim()):null,
            next,a.membershipId(),a.membershipId(),clinic,id,previous);
        if(changed!=1)return conflict("O exame foi atualizado por outro usuário. Atualize a lista.");
        String event=switch(next){case "SCHEDULED"->"SCHEDULED";case "IN_PROGRESS"->"STARTED";case "COMPLETED"->"COMPLETED";case "CANCELLED"->"CANCELLED";default->"UPDATED";};
        record(clinic,id,event,previous,next,"Situação alterada para "+next,a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"status",next,"message","Situação do exame atualizada."));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> history(Authentication auth,@PathVariable UUID id) {
        Access a=access(auth);
        if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT o.professional_id FROM exam_orders o WHERE o.clinic_id=? AND o.id=?
        """,clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        UUID assigned=(UUID)rows.getFirst().get("professional_id");
        if("PROFESSIONAL".equals(a.role())&&!Objects.equals(assigned,a.professionalId()))return forbidden();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT ev.id,ev.event_type,ev.previous_status,ev.new_status,ev.detail,ev.created_at,
                   u.full_name AS actor_name
              FROM exam_order_events ev JOIN clinic_memberships m
                ON m.clinic_id=ev.clinic_id AND m.id=ev.actor_membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE ev.clinic_id=? AND ev.exam_order_id=? ORDER BY ev.created_at DESC LIMIT 100
        """,clinic,id));
    }

    private Access access(Authentication auth){
        if(auth==null||!auth.isAuthenticated())return null;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id,m.role,p.id AS professional_id FROM clinic_memberships m
              JOIN app_users u ON u.id=m.user_id
              LEFT JOIN professionals p ON p.clinic_id=m.clinic_id AND p.membership_id=m.id AND p.status='ACTIVE'
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
        """,auth.getName(),TenantContext.requireClinicId());
        if(rows.isEmpty())return null;
        Map<String,Object> row=rows.getFirst();
        String role=String.valueOf(row.get("role"));
        if(!List.of("CLINIC_ADMIN","RECEPTION","PROFESSIONAL").contains(role))return null;
        UUID professional=(UUID)row.get("professional_id");
        if("PROFESSIONAL".equals(role)&&professional==null)return null;
        return new Access((UUID)row.get("id"),role,professional);
    }
    private boolean activePatient(UUID clinic,UUID id){return jdbc.queryForObject(
        "SELECT count(*) FROM patients WHERE clinic_id=? AND id=? AND status='ACTIVE'",Integer.class,clinic,id)>0;}
    private boolean activeProfessional(UUID clinic,UUID id){return jdbc.queryForObject("""
        SELECT count(*) FROM professionals p JOIN clinic_memberships m ON m.clinic_id=p.clinic_id AND m.id=p.membership_id
          JOIN app_users u ON u.id=m.user_id WHERE p.clinic_id=? AND p.id=? AND p.status='ACTIVE'
            AND m.status='ACTIVE' AND u.status='ACTIVE'
    """,Integer.class,clinic,id)>0;}
    private byte[] encryptOptional(String value){return value==null||value.isBlank()?null:protection.encrypt(value.trim());}
    private String decryptOptional(byte[] value){return value==null?null:protection.decrypt(value);}
    private void record(UUID clinic,UUID id,String type,String previous,String next,String detail,UUID actor){
        jdbc.update("""
            INSERT INTO exam_order_events(clinic_id,exam_order_id,event_type,previous_status,new_status,detail,actor_membership_id)
            VALUES(?,?,?,?,?,?,?)
        """,clinic,id,type,previous,next,detail,actor);
    }
    private Object[] append(Object[] values,Object... tail){
        Object[] result=Arrays.copyOf(values,values.length+tail.length);
        System.arraycopy(tail,0,result,values.length,tail.length);return result;
    }
    private ResponseEntity<Map<String,String>> forbidden(){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","Seu perfil não possui permissão para esta operação."));}
    private ResponseEntity<Map<String,String>> badRequest(String message){return ResponseEntity.badRequest().body(Map.of("error",message));}
    private ResponseEntity<Map<String,String>> conflict(String message){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",message));}

    private record Access(UUID membershipId,String role,UUID professionalId){}
    public record CreateRequest(@NotNull UUID patientId,@NotNull UUID examId,UUID appointmentId,
        UUID queueEntryId,UUID professionalId,@NotBlank @Size(max=16) String priority,
        OffsetDateTime scheduledAt,@Size(max=3000) String notes){}
    public record StatusRequest(@NotBlank @Size(max=20) String status,OffsetDateTime scheduledAt,
        @Size(max=12000) String result){}
}
