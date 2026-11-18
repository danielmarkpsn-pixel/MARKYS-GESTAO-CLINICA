package br.com.markys.gestaoclinica.api;

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
@RequestMapping("/api/v1/clinic/rooms")
public class RoomController {
    private static final List<String> TYPES=List.of("CONSULTORIO","EXAMES","COLETA","AUDIOMETRIA","RAIO_X","MULTIUSO");
    private final JdbcTemplate jdbc;
    public RoomController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @GetMapping
    public ResponseEntity<?> list(Authentication auth,
            @RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="ALL") String type,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size){
        Access a=access(auth);if(a==null)return forbidden();
        if(page<1||size<1||size>100)return badRequest("Paginação inválida.");
        String state=status.trim().toUpperCase(Locale.ROOT);
        if(!List.of("ALL","ACTIVE","INACTIVE").contains(state))return badRequest("Situação de consultório inválida.");
        String roomType=type.trim().toUpperCase(Locale.ROOT);
        if(!"ALL".equals(roomType)&&!TYPES.contains(roomType))return badRequest("Tipo de consultório inválido.");
        String term=search==null?"":search.trim();if(term.length()>100)term=term.substring(0,100);
        UUID clinic=TenantContext.requireClinicId();
        String base="""
            FROM clinic_rooms r
            WHERE r.clinic_id=? AND (?='ALL' OR r.status=?)
              AND (?='ALL' OR r.room_type=?)
              AND (?='' OR r.name ILIKE ? OR COALESCE(r.floor_label,'') ILIKE ?
                   OR COALESCE(r.description,'') ILIKE ?)
        """;
        Object[] args={clinic,state,state,roomType,roomType,term,"%"+term+"%","%"+term+"%","%"+term+"%"};
        Integer total=jdbc.queryForObject("SELECT count(*) "+base,Integer.class,args);
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT r.id,r.name,r.room_type,r.floor_label,r.capacity,r.description,r.status,r.created_at,r.updated_at,
              (SELECT count(*) FROM queue_entries q WHERE q.clinic_id=r.clinic_id AND q.room_id=r.id
                 AND q.status IN ('WAITING','CALLED','IN_SERVICE')) AS active_queue,
              (SELECT count(*) FROM appointments a WHERE a.clinic_id=r.clinic_id AND a.room_id=r.id
                 AND a.status IN ('SCHEDULED','CONFIRMED','CHECKED_IN') AND a.ends_at>now()) AS upcoming_appointments,
              (SELECT count(*) FROM exam_catalog e WHERE e.clinic_id=r.clinic_id AND e.room_id=r.id AND e.active=true) AS active_exams,
              (SELECT count(*) FROM exam_orders o WHERE o.clinic_id=r.clinic_id AND o.room_id=r.id
                 AND o.status IN ('SCHEDULED','IN_PROGRESS')) AS active_exam_orders
            """+base+"""
            ORDER BY CASE WHEN r.status='ACTIVE' THEN 0 ELSE 1 END,lower(r.name)
            LIMIT ? OFFSET ?
        """,append(args,size,(page-1)*size));
        Map<String,Object> summary=jdbc.queryForMap("""
            SELECT count(*) AS total,
              count(*) FILTER(WHERE status='ACTIVE') AS active,
              count(*) FILTER(WHERE status='INACTIVE') AS inactive,
              (SELECT count(DISTINCT room_id) FROM queue_entries
                WHERE clinic_id=? AND status IN ('WAITING','CALLED','IN_SERVICE') AND room_id IS NOT NULL) AS occupied
             FROM clinic_rooms WHERE clinic_id=?
        """,clinic,clinic);
        return ResponseEntity.ok(Map.of("items",rows,"page",page,"size",size,"total",total==null?0:total,
            "pages",total==null?0:(int)Math.ceil(total/(double)size),"summary",summary));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(Authentication auth,@Valid @RequestBody RoomRequest body){
        Access a=access(auth);if(a==null||!canManage(a))return forbidden();
        String type=body.roomType().trim().toUpperCase(Locale.ROOT);
        if(!TYPES.contains(type))return badRequest("Tipo de consultório inválido.");
        UUID clinic=TenantContext.requireClinicId(),id=UUID.randomUUID();
        if(scalar("SELECT count(*) FROM clinic_rooms WHERE clinic_id=? AND lower(name)=lower(?)",clinic,body.name().trim())>0)
            return conflict("Já existe um consultório com esse nome nesta clínica.");
        try{
            jdbc.update("""
                INSERT INTO clinic_rooms(id,clinic_id,name,room_type,floor_label,capacity,description)
                VALUES(?,?,?,?,?,?,?)
            """,id,clinic,body.name().trim(),type,clean(body.floorLabel()),body.capacity(),clean(body.description()));
        }catch(DuplicateKeyException ex){return conflict("Já existe um consultório com esse nome nesta clínica.");}
        record(clinic,id,"CREATED","Consultório cadastrado",a.membershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"message","Consultório cadastrado."));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(Authentication auth,@PathVariable UUID id,@Valid @RequestBody RoomRequest body){
        Access a=access(auth);if(a==null||!canManage(a))return forbidden();
        String type=body.roomType().trim().toUpperCase(Locale.ROOT);
        if(!TYPES.contains(type))return badRequest("Tipo de consultório inválido.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> current=jdbc.queryForList(
            "SELECT status FROM clinic_rooms WHERE clinic_id=? AND id=? FOR UPDATE",clinic,id);
        if(current.isEmpty())return ResponseEntity.notFound().build();
        if(scalar("SELECT count(*) FROM clinic_rooms WHERE clinic_id=? AND lower(name)=lower(?) AND id<>?",clinic,body.name().trim(),id)>0)
            return conflict("Já existe outro consultório com esse nome nesta clínica.");
        try{
            jdbc.update("""
                UPDATE clinic_rooms SET name=?,room_type=?,floor_label=?,capacity=?,description=?,updated_at=now()
                 WHERE clinic_id=? AND id=?
            """,body.name().trim(),type,clean(body.floorLabel()),body.capacity(),clean(body.description()),clinic,id);
        }catch(DuplicateKeyException ex){return conflict("Já existe outro consultório com esse nome nesta clínica.");}
        record(clinic,id,"UPDATED","Cadastro do consultório atualizado",a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"message","Consultório atualizado."));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> status(Authentication auth,@PathVariable UUID id,@Valid @RequestBody ActiveRequest body){
        Access a=access(auth);if(a==null||!canManage(a))return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList(
            "SELECT name,status FROM clinic_rooms WHERE clinic_id=? AND id=? FOR UPDATE",clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        String previous=String.valueOf(rows.getFirst().get("status"));
        String next=body.active()?"ACTIVE":"INACTIVE";
        if(previous.equals(next))return ResponseEntity.ok(Map.of("id",id,"status",next,"message","Situação já estava atualizada."));
        if(!body.active()){
            int activeCatalog=scalar("SELECT count(*) FROM exam_catalog WHERE clinic_id=? AND room_id=? AND active=true",clinic,id);
            int activeQueue=scalar("SELECT count(*) FROM queue_entries WHERE clinic_id=? AND room_id=? AND status IN ('WAITING','CALLED','IN_SERVICE')",clinic,id);
            int futureAppointments=scalar("SELECT count(*) FROM appointments WHERE clinic_id=? AND room_id=? AND status IN ('SCHEDULED','CONFIRMED','CHECKED_IN') AND ends_at>now()",clinic,id);
            int activeOrders=scalar("SELECT count(*) FROM exam_orders WHERE clinic_id=? AND room_id=? AND status IN ('REQUESTED','SCHEDULED','IN_PROGRESS')",clinic,id);
            if(activeCatalog>0)return conflict("Desative primeiro os exames do catálogo vinculados a este consultório.");
            if(activeQueue>0)return conflict("Não é possível inativar: há atendimentos ativos nesta sala.");
            if(futureAppointments>0)return conflict("Não é possível inativar: há agendamentos futuros ou em andamento nesta sala.");
            if(activeOrders>0)return conflict("Não é possível inativar: há exames agendados ou em execução nesta sala.");
        }
        jdbc.update("UPDATE clinic_rooms SET status=?,updated_at=now() WHERE clinic_id=? AND id=?",next,clinic,id);
        record(clinic,id,body.active()?"ACTIVATED":"DEACTIVATED",
            body.active()?"Consultório reativado":"Consultório inativado",a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"status",next,"message",body.active()?"Consultório reativado.":"Consultório inativado."));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(Authentication auth,@PathVariable UUID id){
        Access a=access(auth);if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rooms=jdbc.queryForList("""
            SELECT id,name,room_type,floor_label,capacity,description,status,created_at,updated_at
              FROM clinic_rooms WHERE clinic_id=? AND id=?
        """,clinic,id);
        if(rooms.isEmpty())return ResponseEntity.notFound().build();
        Map<String,Object> result=new LinkedHashMap<>();result.put("room",rooms.getFirst());
        result.put("activeQueue",jdbc.queryForList("""
            SELECT q.id,q.arrived_at,q.status,q.priority,p.full_name AS patient_name,
                   pr.display_name AS professional_name
              FROM queue_entries q JOIN patients p ON p.clinic_id=q.clinic_id AND p.id=q.patient_id
              LEFT JOIN professionals pr ON pr.clinic_id=q.clinic_id AND pr.id=q.assigned_professional_id
             WHERE q.clinic_id=? AND q.room_id=? AND q.status IN ('WAITING','CALLED','IN_SERVICE')
               AND (?::uuid IS NULL OR q.assigned_professional_id=?)
             ORDER BY q.arrived_at LIMIT 100
        """,clinic,id,a.professionalId(),a.professionalId()));
        result.put("upcomingAppointments",jdbc.queryForList("""
            SELECT a.id,a.starts_at,a.ends_at,a.status,p.full_name AS patient_name,pr.display_name AS professional_name
              FROM appointments a JOIN patients p ON p.clinic_id=a.clinic_id AND p.id=a.patient_id
              JOIN professionals pr ON pr.clinic_id=a.clinic_id AND pr.id=a.professional_id
             WHERE a.clinic_id=? AND a.room_id=? AND a.status IN ('SCHEDULED','CONFIRMED','CHECKED_IN')
               AND a.ends_at>now() AND (?::uuid IS NULL OR a.professional_id=?)
             ORDER BY a.starts_at LIMIT 50
        """,clinic,id,a.professionalId(),a.professionalId()));
        result.put("upcomingExams",jdbc.queryForList("""
            SELECT id,exam_name_snapshot AS exam_name,patient_id,professional_id,status,scheduled_at
              FROM exam_orders WHERE clinic_id=? AND room_id=? AND status IN ('SCHEDULED','IN_PROGRESS')
               AND (?::uuid IS NULL OR professional_id=?)
             ORDER BY scheduled_at NULLS LAST LIMIT 50
        """,clinic,id,a.professionalId(),a.professionalId()));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> history(Authentication auth,@PathVariable UUID id){
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(scalar("SELECT count(*) FROM clinic_rooms WHERE clinic_id=? AND id=?",clinic,id)==0)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT ev.id,ev.event_type,ev.detail,ev.created_at,u.full_name AS actor_name
              FROM clinic_room_events ev JOIN clinic_memberships m
                ON m.clinic_id=ev.clinic_id AND m.id=ev.actor_membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE ev.clinic_id=? AND ev.room_id=? ORDER BY ev.created_at DESC LIMIT 100
        """,clinic,id));
    }

    private Access access(Authentication auth){
        if(auth==null||!auth.isAuthenticated())return null;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id,m.role,p.id AS professional_id FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
              LEFT JOIN professionals p ON p.clinic_id=m.clinic_id AND p.membership_id=m.id AND p.status='ACTIVE'
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
        """,auth.getName(),TenantContext.requireClinicId());
        if(rows.isEmpty())return null;
        String role=String.valueOf(rows.getFirst().get("role"));
        if(!List.of("CLINIC_ADMIN","RECEPTION","PROFESSIONAL").contains(role))return null;
        UUID professional=(UUID)rows.getFirst().get("professional_id");
        if("PROFESSIONAL".equals(role)&&professional==null)return null;
        return new Access((UUID)rows.getFirst().get("id"),role,professional);
    }
    private boolean canManage(Access a){return List.of("CLINIC_ADMIN","RECEPTION").contains(a.role());}
    private int scalar(String sql,Object... args){Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;}
    private void record(UUID clinic,UUID id,String type,String detail,UUID actor){
        jdbc.update("INSERT INTO clinic_room_events(clinic_id,room_id,event_type,detail,actor_membership_id) VALUES(?,?,?,?,?)",
            clinic,id,type,detail,actor);
    }
    private String clean(String value){if(value==null)return null;String v=value.trim();return v.isEmpty()?null:v;}
    private Object[] append(Object[] values,Object... tail){Object[] result=Arrays.copyOf(values,values.length+tail.length);System.arraycopy(tail,0,result,values.length,tail.length);return result;}
    private ResponseEntity<Map<String,String>> forbidden(){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","Seu perfil não possui permissão para esta operação."));}
    private ResponseEntity<Map<String,String>> badRequest(String message){return ResponseEntity.badRequest().body(Map.of("error",message));}
    private ResponseEntity<Map<String,String>> conflict(String message){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",message));}
    private record Access(UUID membershipId,String role,UUID professionalId){}
    public record RoomRequest(@NotBlank @Size(max=100) String name,
        @NotBlank @Size(max=30) String roomType,@Size(max=80) String floorLabel,
        @NotNull @Min(1) @Max(100) Integer capacity,@Size(max=500) String description){}
    public record ActiveRequest(@NotNull Boolean active){}
}
