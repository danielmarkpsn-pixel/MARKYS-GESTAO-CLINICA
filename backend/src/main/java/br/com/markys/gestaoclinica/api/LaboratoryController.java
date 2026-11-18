package br.com.markys.gestaoclinica.api;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import br.com.markys.gestaoclinica.security.PatientDataProtection;
import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/laboratory")
public class LaboratoryController {
    private static final List<String> STATUSES=List.of("PENDING_COLLECTION","COLLECTED","RECEIVED",
        "PROCESSING","RESULT_READY","RELEASED","REJECTED","CANCELLED");
    private final JdbcTemplate jdbc;
    private final PatientDataProtection protection;
    public LaboratoryController(JdbcTemplate jdbc,PatientDataProtection protection){
        this.jdbc=jdbc;this.protection=protection;
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
        if(!"ALL".equals(state)&&!STATUSES.contains(state))return badRequest("Situação laboratorial inválida.");
        String term=search==null?"":search.trim();if(term.length()>120)term=term.substring(0,120);
        UUID clinic=TenantContext.requireClinicId();
        String base="""
            FROM lab_specimens s
            JOIN exam_orders o ON o.clinic_id=s.clinic_id AND o.id=s.exam_order_id
            JOIN patients p ON p.clinic_id=o.clinic_id AND p.id=o.patient_id
            LEFT JOIN professionals pr ON pr.clinic_id=o.clinic_id AND pr.id=o.professional_id
            WHERE s.clinic_id=? AND (?='ALL' OR s.collection_status=?)
              AND (?='' OR p.full_name ILIKE ? OR o.exam_name_snapshot ILIKE ?
                   OR COALESCE(s.lab_reference,'') ILIKE ?)
        """;
        Object[] args={clinic,state,state,term,"%"+term+"%","%"+term+"%","%"+term+"%"};
        Integer total=jdbc.queryForObject("SELECT count(*) "+base,Integer.class,args);
        List<Map<String,Object>> items=jdbc.queryForList("""
            SELECT s.id AS specimen_id,o.id AS exam_order_id,p.id AS patient_id,p.full_name AS patient_name,
                   o.exam_name_snapshot AS exam_name,o.room_name_snapshot AS room_name,
                   pr.display_name AS professional_name,o.priority,o.status AS order_status,
                   s.specimen_type,s.lab_reference,s.collection_status,s.created_at AS requested_at,
                   s.collected_at,s.received_at,s.processing_started_at,s.result_ready_at,s.released_at
            """+base+"""
            ORDER BY CASE s.collection_status WHEN 'PENDING_COLLECTION' THEN 0 WHEN 'COLLECTED' THEN 1
                 WHEN 'RECEIVED' THEN 2 WHEN 'PROCESSING' THEN 3 WHEN 'RESULT_READY' THEN 4
                 WHEN 'REJECTED' THEN 5 WHEN 'RELEASED' THEN 6 ELSE 7 END,s.created_at
            LIMIT ? OFFSET ?
        """,append(args,size,(page-1)*size));
        Map<String,Object> summary=jdbc.queryForMap("""
            SELECT count(*) AS total,
              count(*) FILTER(WHERE collection_status='PENDING_COLLECTION') AS pending_collection,
              count(*) FILTER(WHERE collection_status IN ('COLLECTED','RECEIVED')) AS collected,
              count(*) FILTER(WHERE collection_status='PROCESSING') AS processing,
              count(*) FILTER(WHERE collection_status='RESULT_READY') AS result_ready,
              count(*) FILTER(WHERE collection_status='RELEASED') AS released
             FROM lab_specimens WHERE clinic_id=?
        """,clinic);
        return ResponseEntity.ok(Map.of("items",items,"page",page,"size",size,"total",total==null?0:total,
            "pages",total==null?0:(int)Math.ceil(total/(double)size),"summary",summary));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(Authentication auth,@PathVariable UUID id){
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT s.*,o.id AS exam_order_id,o.exam_name_snapshot AS exam_name,o.exam_category_snapshot AS category,
                   o.room_name_snapshot AS room_name,o.patient_id,p.full_name AS patient_name,
                   o.professional_id,pr.display_name AS professional_name,o.priority,o.status AS order_status,
                   o.requested_at,o.scheduled_at,o.notes_ciphertext
              FROM lab_specimens s
              JOIN exam_orders o ON o.clinic_id=s.clinic_id AND o.id=s.exam_order_id
              JOIN patients p ON p.clinic_id=o.clinic_id AND p.id=o.patient_id
              LEFT JOIN professionals pr ON pr.clinic_id=o.clinic_id AND pr.id=o.professional_id
             WHERE s.clinic_id=? AND s.id=?
        """,clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        Map<String,Object> row=new LinkedHashMap<>(rows.getFirst());
        byte[] notes=(byte[])row.remove("collection_notes_ciphertext");
        byte[] examNotes=(byte[])row.remove("notes_ciphertext");
        byte[] rejection=(byte[])row.remove("rejection_reason_ciphertext");
        byte[] result=(byte[])row.remove("result_ciphertext");
        row.remove("result_entered_by_membership_id");
        if((notes!=null||examNotes!=null||rejection!=null||result!=null)&&!protection.isConfigured())
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error","Proteção de dados indisponível no servidor."));
        row.put("collection_notes",notes==null?null:protection.decrypt(notes));
        row.put("exam_notes",examNotes==null?null:protection.decrypt(examNotes));
        row.put("rejection_reason",rejection==null?null:protection.decrypt(rejection));
        row.put("result",result==null?null:protection.decrypt(result));
        return ResponseEntity.ok(row);
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> history(Authentication auth,@PathVariable UUID id){
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(scalar("SELECT count(*) FROM lab_specimens WHERE clinic_id=? AND id=?",clinic,id)==0)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT ev.id,ev.event_type,ev.previous_status,ev.new_status,ev.detail,ev.created_at,
                   u.full_name AS actor_name
              FROM lab_specimen_events ev JOIN clinic_memberships m
                ON m.clinic_id=ev.clinic_id AND m.id=ev.actor_membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE ev.clinic_id=? AND ev.specimen_id=? ORDER BY ev.created_at DESC LIMIT 100
        """,clinic,id));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> updateStatus(Authentication auth,@PathVariable UUID id,@Valid @RequestBody StatusRequest body){
        Access a=access(auth);if(a==null)return forbidden();
        String next=body.status().trim().toUpperCase(Locale.ROOT);
        if(!STATUSES.contains(next))return badRequest("Situação laboratorial inválida.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT s.collection_status,s.result_ciphertext,s.result_entered_by_membership_id,s.lab_reference,o.id AS exam_order_id,
                   o.status AS order_status
              FROM lab_specimens s JOIN exam_orders o ON o.clinic_id=s.clinic_id AND o.id=s.exam_order_id
             WHERE s.clinic_id=? AND s.id=? FOR UPDATE OF s,o
        """,clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        Map<String,Object> current=rows.getFirst();
        String previous=String.valueOf(current.get("collection_status"));
        String orderStatus=String.valueOf(current.get("order_status"));
        UUID orderId=(UUID)current.get("exam_order_id");
        if("COMPLETED".equals(orderStatus)||"CANCELLED".equals(orderStatus))
            return conflict("A solicitação laboratorial já foi concluída ou cancelada.");
        boolean allowed=switch(previous){
            case "PENDING_COLLECTION"->List.of("COLLECTED","CANCELLED").contains(next);
            case "COLLECTED"->List.of("RECEIVED","REJECTED","CANCELLED").contains(next);
            case "RECEIVED"->List.of("PROCESSING","REJECTED","CANCELLED").contains(next);
            case "PROCESSING"->List.of("RESULT_READY","REJECTED","CANCELLED").contains(next);
            case "RESULT_READY"->List.of("RELEASED","CANCELLED").contains(next);
            case "REJECTED"->List.of("PENDING_COLLECTION","CANCELLED").contains(next);
            default->false;
        };
        if(!allowed)return conflict("Não é possível alterar a amostra de "+previous+" para "+next+".");
        if("RESULT_READY".equals(next)){
            if(body.result()==null||body.result().trim().length()<3)return badRequest("Informe o resultado antes de disponibilizá-lo.");
            if(body.result().length()>12000)return badRequest("O resultado excede o limite de 12.000 caracteres.");
            if(!protection.isConfigured())return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error","Proteção do resultado indisponível. Configure as chaves no servidor."));
        }
        if("REJECTED".equals(next)&&(body.rejectionReason()==null||body.rejectionReason().trim().length()<3))
            return badRequest("Informe o motivo da rejeição da amostra.");
        String reference=body.labReference()==null?null:body.labReference().trim();
        if(reference!=null&&!reference.isBlank()&&scalar("""
            SELECT count(*) FROM lab_specimens WHERE clinic_id=? AND upper(lab_reference)=upper(?) AND id<>?
        """,clinic,reference,id)>0)return conflict("Já existe uma amostra com esse número de protocolo.");
        boolean hasSensitiveInput=(body.collectionNotes()!=null&&!body.collectionNotes().isBlank())
            ||(body.rejectionReason()!=null&&!body.rejectionReason().isBlank())
            ||"RESULT_READY".equals(next);
        if(hasSensitiveInput&&!protection.isConfigured())return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error","Proteção de dados indisponível. Configure as chaves no servidor."));
        byte[] notes=body.collectionNotes()==null||body.collectionNotes().isBlank()?null:protection.encrypt(body.collectionNotes().trim());
        byte[] rejection=body.rejectionReason()==null||body.rejectionReason().isBlank()?null:protection.encrypt(body.rejectionReason().trim());
        byte[] result="RESULT_READY".equals(next)?protection.encrypt(body.result().trim()):null;
        byte[] storedResult=(byte[])current.get("result_ciphertext");
        UUID resultAuthor=(UUID)current.get("result_entered_by_membership_id");
        if("RELEASED".equals(next)&&(storedResult==null||resultAuthor==null))
            return conflict("Registre o resultado antes de liberar a amostra.");
        if("PROCESSING".equals(next)&&("REQUESTED".equals(orderStatus)||"SCHEDULED".equals(orderStatus))){
            int changed=jdbc.update("""
                UPDATE exam_orders SET status='IN_PROGRESS',started_at=COALESCE(started_at,now()),
                    updated_by_membership_id=?,updated_at=now() WHERE clinic_id=? AND id=?
            """,a.membershipId(),clinic,orderId);
            if(changed!=1)return conflict("A solicitação não está disponível para processamento.");
            recordOrder(clinic,orderId,"STARTED",orderStatus,"IN_PROGRESS","Processamento laboratorial iniciado",a.membershipId());
        }
        if("RELEASED".equals(next)){
            int changed=jdbc.update("""
                UPDATE exam_orders SET status='COMPLETED',completed_at=now(),result_ciphertext=?,
                    result_recorded_by_membership_id=?,updated_by_membership_id=?,updated_at=now()
                 WHERE clinic_id=? AND id=? AND status IN ('REQUESTED','SCHEDULED','IN_PROGRESS')
            """,storedResult,resultAuthor,a.membershipId(),clinic,orderId);
            if(changed!=1)return conflict("A solicitação foi alterada. Atualize o laboratório.");
            recordOrder(clinic,orderId,"COMPLETED",orderStatus,"COMPLETED","Resultado laboratorial liberado",a.membershipId());
        }
        if("CANCELLED".equals(next)){
            int changed=jdbc.update("""
                UPDATE exam_orders SET status='CANCELLED',cancelled_at=now(),
                    updated_by_membership_id=?,updated_at=now()
                 WHERE clinic_id=? AND id=? AND status IN ('REQUESTED','SCHEDULED','IN_PROGRESS')
            """,a.membershipId(),clinic,orderId);
            if(changed!=1)return conflict("A solicitação não está disponível para cancelamento.");
            recordOrder(clinic,orderId,"CANCELLED",orderStatus,"CANCELLED","Solicitação laboratorial cancelada",a.membershipId());
        }

        jdbc.update("""
            UPDATE lab_specimens SET collection_status=?,lab_reference=COALESCE(?,lab_reference),
                collection_notes_ciphertext=COALESCE(?,collection_notes_ciphertext),
                updated_by_membership_id=?,updated_at=now()
             WHERE clinic_id=? AND id=?
        """,next,reference,notes,a.membershipId(),clinic,id);
        if("COLLECTED".equals(next))jdbc.update("""
            UPDATE lab_specimens SET collected_at=now(),collected_by_membership_id=? WHERE clinic_id=? AND id=?
        """,a.membershipId(),clinic,id);
        if("RECEIVED".equals(next))jdbc.update("""
            UPDATE lab_specimens SET received_at=now(),received_by_membership_id=? WHERE clinic_id=? AND id=?
        """,a.membershipId(),clinic,id);
        if("PROCESSING".equals(next))jdbc.update("""
            UPDATE lab_specimens SET processing_started_at=now(),processing_by_membership_id=?
             WHERE clinic_id=? AND id=?
        """,a.membershipId(),clinic,id);
        if("RESULT_READY".equals(next))jdbc.update("""
            UPDATE lab_specimens SET result_ciphertext=?,result_entered_by_membership_id=?,result_ready_at=now()
             WHERE clinic_id=? AND id=?
        """,result,a.membershipId(),clinic,id);
        if("REJECTED".equals(next))jdbc.update("""
            UPDATE lab_specimens SET rejection_reason_ciphertext=?,rejected_at=now() WHERE clinic_id=? AND id=?
        """,rejection,clinic,id);
        if("PENDING_COLLECTION".equals(next))jdbc.update("""
            UPDATE lab_specimens SET collected_at=NULL,received_at=NULL,processing_started_at=NULL,
                result_ready_at=NULL,rejected_at=NULL,result_ciphertext=NULL,rejection_reason_ciphertext=NULL,
                result_entered_by_membership_id=NULL,collected_by_membership_id=NULL,
                received_by_membership_id=NULL,processing_by_membership_id=NULL
             WHERE clinic_id=? AND id=?
        """,clinic,id);
        if("RELEASED".equals(next))jdbc.update("""
            UPDATE lab_specimens SET released_at=now(),released_by_membership_id=? WHERE clinic_id=? AND id=?
        """,a.membershipId(),clinic,id);
        if("CANCELLED".equals(next))jdbc.update("UPDATE lab_specimens SET cancelled_at=now() WHERE clinic_id=? AND id=?",clinic,id);
        String event=switch(next){
            case "COLLECTED"->"COLLECTED";case "RECEIVED"->"RECEIVED";case "PROCESSING"->"PROCESSING_STARTED";
            case "RESULT_READY"->"RESULT_READY";case "RELEASED"->"RELEASED";case "REJECTED"->"REJECTED";
            case "CANCELLED"->"CANCELLED";case "PENDING_COLLECTION"->"RECOLLECTED";default->"UPDATED";
        };
        String detail=switch(next){
            case "COLLECTED"->"Coleta registrada";case "RECEIVED"->"Amostra recebida pelo laboratório";
            case "PROCESSING"->"Processamento iniciado";case "RESULT_READY"->"Resultado registrado e aguardando liberação";
            case "RELEASED"->"Resultado liberado";case "REJECTED"->"Amostra rejeitada";
            case "CANCELLED"->"Solicitação cancelada";case "PENDING_COLLECTION"->"Nova coleta solicitada";default->"Amostra atualizada";
        };
        record(clinic,id,event,previous,next,detail,a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"status",next,"message","Fluxo laboratorial atualizado."));
    }

    private Access access(Authentication auth){
        if(auth==null||!auth.isAuthenticated())return null;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id,m.role FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
        """,auth.getName(),TenantContext.requireClinicId());
        if(rows.isEmpty())return null;
        String role=String.valueOf(rows.getFirst().get("role"));
        if(!List.of("CLINIC_ADMIN","LAB_TECHNICIAN").contains(role))return null;
        return new Access((UUID)rows.getFirst().get("id"),role);
    }
    private int scalar(String sql,Object...args){Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;}
    private void record(UUID clinic,UUID specimenId,String type,String previous,String next,String detail,UUID actor){
        jdbc.update("""
            INSERT INTO lab_specimen_events(clinic_id,specimen_id,event_type,previous_status,new_status,detail,actor_membership_id)
            VALUES(?,?,?,?,?,?,?)
        """,clinic,specimenId,type,previous,next,detail,actor);
    }
    private void recordOrder(UUID clinic,UUID orderId,String type,String previous,String next,String detail,UUID actor){
        jdbc.update("""
            INSERT INTO exam_order_events(clinic_id,exam_order_id,event_type,previous_status,new_status,detail,actor_membership_id)
            VALUES(?,?,?,?,?,?,?)
        """,clinic,orderId,type,previous,next,detail,actor);
    }
    private Object[] append(Object[] values,Object...tail){Object[] result=Arrays.copyOf(values,values.length+tail.length);System.arraycopy(tail,0,result,values.length,tail.length);return result;}
    private ResponseEntity<Map<String,String>> forbidden(){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","Seu perfil não possui acesso ao laboratório."));}
    private ResponseEntity<Map<String,String>> badRequest(String message){return ResponseEntity.badRequest().body(Map.of("error",message));}
    private ResponseEntity<Map<String,String>> conflict(String message){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",message));}
    private record Access(UUID membershipId,String role){}
    public record StatusRequest(@NotBlank @Size(max=24) String status,@Size(max=80) String labReference,
        @Size(max=2000) String collectionNotes,@Size(max=500) String rejectionReason,@Size(max=12000) String result){}
}
