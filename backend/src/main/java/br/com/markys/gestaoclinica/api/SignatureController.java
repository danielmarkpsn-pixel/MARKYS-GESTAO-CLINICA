package br.com.markys.gestaoclinica.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/signatures")
public class SignatureController {
    private static final String CONSENT = "Declaro que li o documento identificado nesta solicitação, que estou assinando de forma consciente e que reconheço o registro eletrônico desta manifestação, com data, hora, usuário autenticado e identificação do arquivo.";
    private final JdbcTemplate jdbc;
    public SignatureController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/resources")
    public ResponseEntity<?> resources(Authentication auth) {
        Access a=access(auth); if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> patients=jdbc.queryForList("""
            SELECT id,full_name FROM patients WHERE clinic_id=? AND status='ACTIVE'
            ORDER BY lower(full_name) LIMIT 1000
        """,clinic);
        List<Map<String,Object>> documents=jdbc.queryForList("""
            SELECT d.id,d.patient_id,d.original_file_name,d.file_size,d.created_at,p.full_name AS patient_name,
                   encode(d.sha256,'hex') AS sha256
              FROM patient_documents d JOIN patients p ON p.clinic_id=d.clinic_id AND p.id=d.patient_id
             WHERE d.clinic_id=? ORDER BY d.created_at DESC LIMIT 500
        """,clinic);
        return ResponseEntity.ok(Map.of("role",a.role(),"patients",patients,"documents",documents,"consentText",CONSENT));
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth,
            @RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="") String from,
            @RequestParam(defaultValue="") String to) {
        Access a=access(auth); if(a==null)return forbidden();
        String state=status.trim().toUpperCase(Locale.ROOT);
        if(!List.of("ALL","PENDING","SIGNED","CANCELLED").contains(state))return badRequest("Situação inválida.");
        String term=search==null?"":search.trim(); if(term.length()>120)term=term.substring(0,120);
        java.sql.Date start=null,end=null;
        try {if(!from.isBlank())start=java.sql.Date.valueOf(from);if(!to.isBlank())end=java.sql.Date.valueOf(to);}
        catch(RuntimeException ex){return badRequest("Período inválido.");}
        if(start!=null&&end!=null&&end.before(start))return badRequest("A data final deve ser igual ou posterior à inicial.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> items=jdbc.queryForList("""
            SELECT s.id,s.patient_id,p.full_name AS patient_name,s.patient_document_id,
                   s.document_name_snapshot,s.status,s.requested_at,s.signed_at,
                   s.signer_name_snapshot,s.signature_method,s.cancelled_at,s.cancellation_reason,
                   requester.full_name AS requested_by,signer.full_name AS signed_by,
                   encode(s.document_sha256,'hex') AS document_sha256,
                   encode(s.evidence_sha256,'hex') AS evidence_sha256
              FROM document_signature_requests s
              JOIN patients p ON p.clinic_id=s.clinic_id AND p.id=s.patient_id
              JOIN clinic_memberships rm ON rm.clinic_id=s.clinic_id AND rm.id=s.requested_by_membership_id
              JOIN app_users requester ON requester.id=rm.user_id
              LEFT JOIN clinic_memberships sm ON sm.clinic_id=s.clinic_id AND sm.id=s.signed_by_membership_id
              LEFT JOIN app_users signer ON signer.id=sm.user_id
             WHERE s.clinic_id=? AND (?='ALL' OR s.status=?)
               AND (?='' OR p.full_name ILIKE ? OR s.document_name_snapshot ILIKE ?
                    OR COALESCE(s.signer_name_snapshot,'') ILIKE ?)
               AND (?::date IS NULL OR s.requested_at::date>=?)
               AND (?::date IS NULL OR s.requested_at::date<=?)
             ORDER BY CASE s.status WHEN 'PENDING' THEN 0 WHEN 'SIGNED' THEN 1 ELSE 2 END,
                      s.requested_at DESC LIMIT 500
        """,clinic,state,state,term,"%"+term+"%","%"+term+"%","%"+term+"%",start,start,end,end);
        Map<String,Object> summary=jdbc.queryForMap("""
            SELECT count(*) AS total,count(*) FILTER(WHERE status='PENDING') AS pending,
                   count(*) FILTER(WHERE status='SIGNED') AS signed,
                   count(*) FILTER(WHERE status='CANCELLED') AS cancelled
              FROM document_signature_requests WHERE clinic_id=?
        """,clinic);
        return ResponseEntity.ok(Map.of("items",items,"summary",summary));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(Authentication auth,@Valid @RequestBody CreateRequest body) {
        Access a=access(auth); if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> docs=jdbc.queryForList("""
            SELECT d.patient_id,d.original_file_name,d.sha256 FROM patient_documents d
             WHERE d.clinic_id=? AND d.id=?
        """,clinic,body.documentId());
        if(docs.isEmpty())return badRequest("Documento PDF não encontrado nesta clínica.");
        Map<String,Object> doc=docs.getFirst();
        UUID patient=(UUID)doc.get("patient_id");
        if(body.patientId()!=null&&!body.patientId().equals(patient))return badRequest("O documento não pertence ao paciente selecionado.");
        Integer duplicate=jdbc.queryForObject("""
            SELECT count(*) FROM document_signature_requests
             WHERE clinic_id=? AND patient_document_id=? AND status='PENDING'
        """,Integer.class,clinic,body.documentId());
        if(duplicate!=null&&duplicate>0)return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","Já existe uma solicitação pendente para este documento."));
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO document_signature_requests(id,clinic_id,patient_id,patient_document_id,
              document_name_snapshot,document_sha256,requested_by_membership_id)
            VALUES(?,?,?,?,?,?,?)
        """,id,clinic,patient,body.documentId(),doc.get("original_file_name"),doc.get("sha256"),a.membershipId());
        event(clinic,id,"REQUESTED","Solicitação de assinatura criada",a.membershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"status","PENDING","message","Solicitação criada."));
    }

    @PostMapping("/{id}/sign")
    @Transactional
    public ResponseEntity<?> sign(Authentication auth,@PathVariable UUID id,@Valid @RequestBody SignRequest body) throws java.security.NoSuchAlgorithmException {
        Access a=access(auth); if(a==null)return forbidden();
        String signer=body.signerName().trim();
        if(signer.length()<3)return badRequest("Informe o nome completo de quem está assinando.");
        if(!body.consent())return badRequest("É necessário confirmar a declaração de consentimento.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT status,document_sha256 FROM document_signature_requests
             WHERE clinic_id=? AND id=? FOR UPDATE
        """,clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        if(!"PENDING".equals(String.valueOf(rows.getFirst().get("status"))))return conflict("Esta solicitação não está mais pendente.");
        byte[] docHash=(byte[])rows.getFirst().get("document_sha256");
        OffsetDateTime now=OffsetDateTime.now();
        String evidence=signer+"|"+a.membershipId()+"|"+now+"|"+HexFormat.of().formatHex(docHash)+"|"+CONSENT;
        byte[] evidenceHash=MessageDigest.getInstance("SHA-256").digest(evidence.getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
            UPDATE document_signature_requests SET status='SIGNED',signed_by_membership_id=?,
              signer_name_snapshot=?,signed_at=?,consent_text=?,signature_method='AUTHENTICATED_DECLARATION',
              evidence_sha256=? WHERE clinic_id=? AND id=?
        """,a.membershipId(),signer,now,CONSENT,evidenceHash,clinic,id);
        event(clinic,id,"SIGNED","Assinatura eletrônica declaratória registrada",a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"status","SIGNED","signedAt",now,"evidenceSha256",HexFormat.of().formatHex(evidenceHash),
            "message","Manifestação eletrônica registrada. Este registro não equivale a certificado ICP-Brasil."));
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<?> cancel(Authentication auth,@PathVariable UUID id,@Valid @RequestBody CancelRequest body) {
        Access a=access(auth); if(a==null||!("CLINIC_ADMIN".equals(a.role())||"RECEPTION".equals(a.role())))return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        int changed=jdbc.update("""
            UPDATE document_signature_requests SET status='CANCELLED',cancelled_by_membership_id=?,
              cancelled_at=now(),cancellation_reason=?
             WHERE clinic_id=? AND id=? AND status='PENDING'
        """,a.membershipId(),body.reason().trim(),clinic,id);
        if(changed==0)return conflict("Solicitação inexistente ou que não está pendente.");
        event(clinic,id,"CANCELLED",body.reason().trim(),a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"status","CANCELLED","message","Solicitação cancelada."));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> history(Authentication auth,@PathVariable UUID id) {
        Access a=access(auth); if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        Integer exists=jdbc.queryForObject("SELECT count(*) FROM document_signature_requests WHERE clinic_id=? AND id=?",Integer.class,clinic,id);
        if(exists==null||exists==0)return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT e.event_type,e.detail,e.created_at,u.full_name AS actor_name
              FROM document_signature_events e JOIN clinic_memberships m ON m.clinic_id=e.clinic_id AND m.id=e.actor_membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE e.clinic_id=? AND e.request_id=? ORDER BY e.created_at
        """,clinic,id));
    }

    private void event(UUID clinic,UUID id,String type,String detail,UUID actor){
        jdbc.update("INSERT INTO document_signature_events(clinic_id,request_id,event_type,detail,actor_membership_id) VALUES(?,?,?,?,?)",clinic,id,type,detail,actor);
    }
    private Access access(Authentication auth){
        if(auth==null||!auth.isAuthenticated())return null;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id AS membership_id,m.role FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
        """,auth.getName(),TenantContext.requireClinicId());
        if(rows.isEmpty())return null;
        Map<String,Object> row=rows.getFirst();String role=String.valueOf(row.get("role"));
        if(!List.of("CLINIC_ADMIN","RECEPTION","PROFESSIONAL").contains(role))return null;
        return new Access((UUID)row.get("membership_id"),role);
    }
    private ResponseEntity<Map<String,String>> forbidden(){return ResponseEntity.status(403).body(Map.of("error","Seu perfil não tem permissão para esta operação."));}
    private ResponseEntity<Map<String,String>> badRequest(String message){return ResponseEntity.badRequest().body(Map.of("error",message));}
    private ResponseEntity<Map<String,String>> conflict(String message){return ResponseEntity.status(409).body(Map.of("error",message));}
    public record CreateRequest(@NotNull UUID documentId,UUID patientId){}
    public record SignRequest(@NotBlank @Size(max=180) String signerName,boolean consent){}
    public record CancelRequest(@NotBlank @Size(max=300) String reason){}
    private record Access(UUID membershipId,String role){}
}
