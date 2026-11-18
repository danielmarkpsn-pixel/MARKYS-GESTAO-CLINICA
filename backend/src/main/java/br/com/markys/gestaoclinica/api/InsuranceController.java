package br.com.markys.gestaoclinica.api;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/insurances")
public class InsuranceController {
    private final JdbcTemplate jdbc;
    public InsuranceController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @GetMapping("/resources")
    public ResponseEntity<?> resources(Authentication auth){
        Access a=access(auth);if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        return ResponseEntity.ok(Map.of("role",a.role(),"items",jdbc.queryForList("""
            SELECT id,name,ans_code FROM clinic_insurances
             WHERE clinic_id=? AND status='ACTIVE' ORDER BY lower(name)
        """,clinic)));
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth,
            @RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size){
        Access a=access(auth);if(a==null)return forbidden();
        if(page<1||size<1||size>100)return badRequest("Paginação inválida.");
        String state=status.trim().toUpperCase(Locale.ROOT);
        if(!List.of("ALL","ACTIVE","INACTIVE").contains(state))return badRequest("Situação de convênio inválida.");
        String term=search==null?"":search.trim();if(term.length()>120)term=term.substring(0,120);
        UUID clinic=TenantContext.requireClinicId();
        String base="""
            FROM clinic_insurances i WHERE i.clinic_id=?
              AND (?='ALL' OR i.status=?)
              AND (?='' OR i.name ILIKE ? OR COALESCE(i.ans_code,'') ILIKE ?
                   OR COALESCE(i.contact_name,'') ILIKE ?)
        """;
        Object[] args={clinic,state,state,term,"%"+term+"%","%"+term+"%","%"+term+"%"};
        Integer total=jdbc.queryForObject("SELECT count(*) "+base,Integer.class,args);
        List<Map<String,Object>> items=jdbc.queryForList("""
            SELECT i.id,i.name,i.ans_code,i.contact_name,i.phone,i.email,i.billing_email,
                   i.payment_terms_days,i.status,i.created_at,i.updated_at,
                   (SELECT count(*) FROM patients p WHERE p.clinic_id=i.clinic_id AND p.insurance_id=i.id) AS patient_count
            """+base+"""
            ORDER BY CASE WHEN i.status='ACTIVE' THEN 0 ELSE 1 END,lower(i.name)
            LIMIT ? OFFSET ?
        """,append(args,size,(page-1)*size));
        Map<String,Object> summary=jdbc.queryForMap("""
            SELECT count(*) AS total,
              count(*) FILTER(WHERE status='ACTIVE') AS active,
              count(*) FILTER(WHERE status='INACTIVE') AS inactive,
              (SELECT count(*) FROM patients p JOIN clinic_insurances i
                 ON i.clinic_id=p.clinic_id AND i.id=p.insurance_id
                WHERE p.clinic_id=? AND i.status='ACTIVE') AS linked_patients
             FROM clinic_insurances WHERE clinic_id=?
        """,clinic,clinic);
        return ResponseEntity.ok(Map.of("items",items,"page",page,"size",size,
            "total",total==null?0:total,"pages",total==null?0:(int)Math.ceil(total/(double)size),"summary",summary));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(Authentication auth,@Valid @RequestBody InsuranceRequest body){
        Access a=access(auth);if(a==null||!canManage(a))return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        String name=body.name().trim(),ans=cleanUpper(body.ansCode());
        if(scalar("SELECT count(*) FROM clinic_insurances WHERE clinic_id=? AND lower(name)=lower(?)",clinic,name)>0)
            return conflict("Já existe um convênio com esse nome nesta clínica.");
        if(ans!=null&&scalar("SELECT count(*) FROM clinic_insurances WHERE clinic_id=? AND upper(ans_code)=upper(?)",clinic,ans)>0)
            return conflict("Já existe um convênio cadastrado com esse código ANS.");
        UUID id=UUID.randomUUID();
        try{
            jdbc.update("""
                INSERT INTO clinic_insurances(id,clinic_id,name,ans_code,contact_name,phone,email,billing_email,
                    payment_terms_days,notes,created_by_membership_id,updated_by_membership_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,clinic,name,ans,clean(body.contactName()),clean(body.phone()),cleanLower(body.email()),
                cleanLower(body.billingEmail()),body.paymentTermsDays(),clean(body.notes()),a.membershipId(),a.membershipId());
        }catch(org.springframework.dao.DuplicateKeyException ex){return conflict("Já existe um convênio com esse nome ou código ANS.");}
        record(clinic,id,"CREATED","Convênio cadastrado",a.membershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"message","Convênio cadastrado."));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(Authentication auth,@PathVariable UUID id,@Valid @RequestBody InsuranceRequest body){
        Access a=access(auth);if(a==null||!canManage(a))return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(scalar("SELECT count(*) FROM clinic_insurances WHERE clinic_id=? AND id=?",clinic,id)==0)
            return ResponseEntity.notFound().build();
        String name=body.name().trim(),ans=cleanUpper(body.ansCode());
        if(scalar("SELECT count(*) FROM clinic_insurances WHERE clinic_id=? AND lower(name)=lower(?) AND id<>?",clinic,name,id)>0)
            return conflict("Já existe outro convênio com esse nome nesta clínica.");
        if(ans!=null&&scalar("SELECT count(*) FROM clinic_insurances WHERE clinic_id=? AND upper(ans_code)=upper(?) AND id<>?",clinic,ans,id)>0)
            return conflict("Outro convênio já utiliza esse código ANS.");
        try{
            jdbc.update("""
                UPDATE clinic_insurances SET name=?,ans_code=?,contact_name=?,phone=?,email=?,billing_email=?,
                    payment_terms_days=?,notes=?,updated_by_membership_id=?,updated_at=now()
                 WHERE clinic_id=? AND id=?
            """,name,ans,clean(body.contactName()),clean(body.phone()),cleanLower(body.email()),
                cleanLower(body.billingEmail()),body.paymentTermsDays(),clean(body.notes()),a.membershipId(),clinic,id);
        }catch(org.springframework.dao.DuplicateKeyException ex){return conflict("Já existe um convênio com esse nome ou código ANS.");}
        record(clinic,id,"UPDATED","Cadastro do convênio atualizado",a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"message","Convênio atualizado."));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> status(Authentication auth,@PathVariable UUID id,@Valid @RequestBody ActiveRequest body){
        Access a=access(auth);if(a==null||!canManage(a))return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList(
            "SELECT status FROM clinic_insurances WHERE clinic_id=? AND id=? FOR UPDATE",clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        String previous=String.valueOf(rows.getFirst().get("status")),next=body.active()?"ACTIVE":"INACTIVE";
        if(previous.equals(next))return ResponseEntity.ok(Map.of("id",id,"status",next,"message","Situação já atualizada."));
        jdbc.update("UPDATE clinic_insurances SET status=?,updated_by_membership_id=?,updated_at=now() WHERE clinic_id=? AND id=?",
            next,a.membershipId(),clinic,id);
        record(clinic,id,body.active()?"ACTIVATED":"DEACTIVATED",
            body.active()?"Convênio reativado":"Convênio inativado; vínculos históricos dos pacientes foram preservados",a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"status",next,"message",body.active()?"Convênio reativado.":"Convênio inativado."));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(Authentication auth,@PathVariable UUID id){
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT i.id,i.name,i.ans_code,i.contact_name,i.phone,i.email,i.billing_email,
                   i.payment_terms_days,i.notes,i.status,i.created_at,i.updated_at,
                   (SELECT count(*) FROM patients p WHERE p.clinic_id=i.clinic_id AND p.insurance_id=i.id) AS patient_count
              FROM clinic_insurances i WHERE i.clinic_id=? AND i.id=?
        """,clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        return ResponseEntity.ok(rows.getFirst());
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> history(Authentication auth,@PathVariable UUID id){
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(scalar("SELECT count(*) FROM clinic_insurances WHERE clinic_id=? AND id=?",clinic,id)==0)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT ev.id,ev.event_type,ev.detail,ev.created_at,u.full_name AS actor_name
              FROM clinic_insurance_events ev JOIN clinic_memberships m
                ON m.clinic_id=ev.clinic_id AND m.id=ev.actor_membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE ev.clinic_id=? AND ev.insurance_id=? ORDER BY ev.created_at DESC LIMIT 100
        """,clinic,id));
    }

    private Access access(Authentication auth){
        if(auth==null||!auth.isAuthenticated())return null;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id,m.role FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
        """,auth.getName(),TenantContext.requireClinicId());
        if(rows.isEmpty())return null;
        String role=String.valueOf(rows.getFirst().get("role"));
        if(!List.of("CLINIC_ADMIN","RECEPTION","PROFESSIONAL").contains(role))return null;
        return new Access((UUID)rows.getFirst().get("id"),role);
    }
    private boolean canManage(Access a){return List.of("CLINIC_ADMIN","RECEPTION").contains(a.role());}
    private int scalar(String sql,Object...args){Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;}
    private void record(UUID clinic,UUID id,String type,String detail,UUID actor){
        jdbc.update("INSERT INTO clinic_insurance_events(clinic_id,insurance_id,event_type,detail,actor_membership_id) VALUES(?,?,?,?,?)",
            clinic,id,type,detail,actor);
    }
    private String clean(String value){if(value==null)return null;String v=value.trim();return v.isEmpty()?null:v;}
    private String cleanUpper(String value){String v=clean(value);return v==null?null:v.toUpperCase(Locale.ROOT);}
    private String cleanLower(String value){String v=clean(value);return v==null?null:v.toLowerCase(Locale.ROOT);}
    private Object[] append(Object[] values,Object...tail){Object[] result=Arrays.copyOf(values,values.length+tail.length);System.arraycopy(tail,0,result,values.length,tail.length);return result;}
    private ResponseEntity<Map<String,String>> forbidden(){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","Seu perfil não possui permissão para esta operação."));}
    private ResponseEntity<Map<String,String>> badRequest(String message){return ResponseEntity.badRequest().body(Map.of("error",message));}
    private ResponseEntity<Map<String,String>> conflict(String message){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",message));}
    private record Access(UUID membershipId,String role){}
    public record InsuranceRequest(@NotBlank @Size(max=180) String name,
        @Size(max=24) String ansCode,@Size(max=180) String contactName,@Size(max=40) String phone,
        @Email @Size(max=254) String email,@Email @Size(max=254) String billingEmail,
        @NotNull @Min(0) @Max(365) Integer paymentTermsDays,@Size(max=1000) String notes){}
    public record ActiveRequest(@NotNull Boolean active){}
}
