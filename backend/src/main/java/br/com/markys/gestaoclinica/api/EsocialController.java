package br.com.markys.gestaoclinica.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@RequestMapping("/api/v1/clinic/esocial")
public class EsocialController {
    private static final List<String> TYPES=List.of("S-2210","S-2220","S-2240");
    private final JdbcTemplate jdbc;
    private final PatientDataProtection protection;
    private final ObjectMapper mapper;
    public EsocialController(JdbcTemplate jdbc,PatientDataProtection protection,ObjectMapper mapper){
        this.jdbc=jdbc;this.protection=protection;this.mapper=mapper;
    }

    @GetMapping("/resources")
    public ResponseEntity<?> resources(Authentication auth){
        Access a=access(auth);if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("role",a.role());
        result.put("employer",jdbc.queryForList("SELECT id,registration_type,legal_name,classification_code,environment,contact_name,contact_email,contact_phone,updated_at FROM esocial_employers WHERE clinic_id=?",clinic).stream().findFirst().orElse(null));
        result.put("patients",jdbc.queryForList("SELECT id,full_name,birth_date FROM patients WHERE clinic_id=? AND status='ACTIVE' ORDER BY lower(full_name) LIMIT 500",clinic));
        result.put("workers",jdbc.queryForList("""
            SELECT id,full_name,birth_date,registration_code,admission_date,category_code,cbo_code,job_title,department,work_location,status
              FROM esocial_workers WHERE clinic_id=? ORDER BY lower(full_name) LIMIT 500
            """,clinic));
        result.put("completedExams",jdbc.queryForList("""
            SELECT o.id,o.exam_name_snapshot AS exam_name,o.requested_at,p.full_name AS patient_name
              FROM exam_orders o JOIN patients p ON p.clinic_id=o.clinic_id AND p.id=o.patient_id
             WHERE o.clinic_id=? AND o.status='COMPLETED' ORDER BY o.completed_at DESC LIMIT 300
            """,clinic));
        result.put("summary",jdbc.queryForMap("""
            SELECT count(*) AS total,
              count(*) FILTER(WHERE status='DRAFT') AS drafts,
              count(*) FILTER(WHERE status='READY') AS ready,
              count(*) FILTER(WHERE status='TRANSMITTED') AS transmitted,
              count(*) FILTER(WHERE status='ACCEPTED') AS accepted,
              count(*) FILTER(WHERE status='REJECTED') AS rejected
             FROM esocial_events WHERE clinic_id=?
            """,clinic));
        result.put("transmissionEnabled",false);
        result.put("eventTypes",List.of(Map.of("code","S-2210","name","Comunicação de Acidente de Trabalho (CAT)"),
            Map.of("code","S-2220","name","Monitoramento da Saúde do Trabalhador (ASO)"),
            Map.of("code","S-2240","name","Condições Ambientais do Trabalho")));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/employer")
    @Transactional
    public ResponseEntity<?> saveEmployer(Authentication auth,@Valid @RequestBody EmployerRequest body){
        Access a=admin(auth);if(a==null)return forbidden();
        if(!protection.isConfigured())return badRequest("Proteção de dados pessoais indisponível no servidor.");
        String type=body.registrationType().trim().toUpperCase(Locale.ROOT);
        if(!List.of("CNPJ","CPF","CAEPF","CNO").contains(type))return badRequest("Tipo de inscrição inválido.");
        if(!List.of("PRODUCTION_RESTRICTED","PRODUCTION","RESTRICTED").contains(body.environment()))return badRequest("Ambiente de preparação inválido.");
        String number=body.registrationNumber().replaceAll("\\D","");
        int expected=switch(type){case "CNPJ"->14;case "CPF"->11;case "CAEPF"->14;default->12;};
        if(number.length()!=expected)return badRequest("Número de inscrição incompatível com o tipo selecionado.");
        UUID clinic=TenantContext.requireClinicId();
        byte[] encrypted=protection.encrypt(number),fingerprint=protection.fingerprint(type+":"+number);
        jdbc.update("""
            INSERT INTO esocial_employers(clinic_id,registration_type,registration_ciphertext,registration_fingerprint,
                legal_name,classification_code,environment,contact_name,contact_email,contact_phone,configured_by_membership_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(clinic_id) DO UPDATE SET registration_type=EXCLUDED.registration_type,
                registration_ciphertext=EXCLUDED.registration_ciphertext,registration_fingerprint=EXCLUDED.registration_fingerprint,
                legal_name=EXCLUDED.legal_name,classification_code=EXCLUDED.classification_code,
                environment=EXCLUDED.environment,contact_name=EXCLUDED.contact_name,contact_email=EXCLUDED.contact_email,
                contact_phone=EXCLUDED.contact_phone,configured_by_membership_id=EXCLUDED.configured_by_membership_id,
                updated_at=now()
            """,clinic,type,encrypted,fingerprint,body.legalName().trim(),clean(body.classificationCode()),
            body.environment(),clean(body.contactName()),clean(body.contactEmail()),clean(body.contactPhone()),a.membershipId());
        return ResponseEntity.ok(Map.of("message","Dados do empregador salvos com proteção de dados.","legalName",body.legalName().trim()));
    }

    @GetMapping("/workers")
    public ResponseEntity<?> workers(Authentication auth,@RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        if(access(auth)==null)return forbidden();
        if(page<1||size<1||size>100)return badRequest("Paginação inválida.");
        String term=search==null?"":search.trim();if(term.length()>100)term=term.substring(0,100);
        UUID clinic=TenantContext.requireClinicId();
        String where=" FROM esocial_workers WHERE clinic_id=? AND (?='' OR full_name ILIKE ? OR registration_code ILIKE ?) ";
        Object[] args={clinic,term,"%"+term+"%","%"+term+"%"};
        Integer total=jdbc.queryForObject("SELECT count(*)"+where,Integer.class,args);
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT id,patient_id,full_name,birth_date,registration_code,admission_date,category_code,cbo_code,
                   job_title,department,work_location,status,created_at,updated_at
            """+where+" ORDER BY lower(full_name) LIMIT ? OFFSET ?",append(args,size,(page-1)*size));
        return ResponseEntity.ok(Map.of("items",rows,"page",page,"size",size,"total",total==null?0:total,
            "pages",total==null?0:(int)Math.ceil(total/(double)size)));
    }

    @PostMapping("/workers")
    @Transactional
    public ResponseEntity<?> createWorker(Authentication auth,@Valid @RequestBody WorkerRequest body){
        Access a=admin(auth);if(a==null)return forbidden();
        if(!protection.isConfigured())return badRequest("Proteção de dados pessoais indisponível no servidor.");
        String cpf=body.cpf().replaceAll("\\D","");
        if(!validCpf(cpf))return badRequest("CPF do trabalhador inválido.");
        if(body.admissionDate().isAfter(LocalDate.now()))return badRequest("A data de admissão não pode estar no futuro.");
        if(body.birthDate().isAfter(LocalDate.now().minusYears(14)))return badRequest("Confira a data de nascimento do trabalhador.");
        UUID clinic=TenantContext.requireClinicId(),id=UUID.randomUUID();
        try{
            jdbc.update("""
                INSERT INTO esocial_workers(id,clinic_id,patient_id,cpf_ciphertext,cpf_fingerprint,full_name,birth_date,sex,
                    registration_code,admission_date,category_code,cbo_code,job_title,department,work_location,
                    created_by_membership_id,updated_by_membership_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,clinic,body.patientId(),protection.encrypt(cpf),protection.fingerprint(cpf),body.fullName().trim(),
                body.birthDate(),body.sex().trim().toUpperCase(Locale.ROOT),body.registrationCode().trim(),
                body.admissionDate(),body.categoryCode().trim(),clean(body.cboCode()),body.jobTitle().trim(),
                clean(body.department()),clean(body.workLocation()),a.membershipId(),a.membershipId());
        }catch(DuplicateKeyException ex){return conflict("Já existe trabalhador com este CPF ou matrícula nesta clínica.");}
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"message","Trabalhador cadastrado."));
    }

    @PatchMapping("/workers/{id}/status")
    @Transactional
    public ResponseEntity<?> workerStatus(Authentication auth,@PathVariable UUID id,@Valid @RequestBody WorkerStatus body){
        Access a=admin(auth);if(a==null)return forbidden();
        int changed=jdbc.update("UPDATE esocial_workers SET status=?,updated_by_membership_id=?,updated_at=now() WHERE clinic_id=? AND id=?",
            body.active()?"ACTIVE":"INACTIVE",a.membershipId(),TenantContext.requireClinicId(),id);
        if(changed==0)return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("id",id,"status",body.active()?"ACTIVE":"INACTIVE"));
    }

    @GetMapping("/events")
    public ResponseEntity<?> events(Authentication auth,@RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="ALL") String eventType,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size){
        if(access(auth)==null)return forbidden();
        if(page<1||size<1||size>100)return badRequest("Paginação inválida.");
        String state=status.trim().toUpperCase(Locale.ROOT),type=eventType.trim().toUpperCase(Locale.ROOT);
        if(!List.of("ALL","DRAFT","READY","TRANSMITTED","ACCEPTED","REJECTED","CANCELLED").contains(state))
            return badRequest("Situação do evento inválida.");
        if(!"ALL".equals(type)&&!TYPES.contains(type))return badRequest("Tipo de evento inválido.");
        UUID clinic=TenantContext.requireClinicId();
        String where=" FROM esocial_events e JOIN esocial_workers w ON w.clinic_id=e.clinic_id AND w.id=e.worker_id WHERE e.clinic_id=? AND (?='ALL' OR e.status=?) AND (?='ALL' OR e.event_type=?) ";
        Object[] args={clinic,state,state,type,type};
        Integer total=jdbc.queryForObject("SELECT count(*)"+where,Integer.class,args);
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT e.id,e.event_type,e.event_date,e.event_sequence,e.status,e.validation_errors,
                   e.protocol_number,e.receipt_number,e.rejection_reason,e.source_exam_order_id,
                   w.full_name AS worker_name,w.registration_code,e.created_at,e.updated_at
            """+where+" ORDER BY e.event_date DESC,e.created_at DESC LIMIT ? OFFSET ?",append(args,size,(page-1)*size));
        return ResponseEntity.ok(Map.of("items",rows,"page",page,"size",size,"total",total==null?0:total,
            "pages",total==null?0:(int)Math.ceil(total/(double)size)));
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<?> eventDetail(Authentication auth,@PathVariable UUID id){
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT e.*,w.full_name AS worker_name,w.registration_code
              FROM esocial_events e JOIN esocial_workers w ON w.clinic_id=e.clinic_id AND w.id=e.worker_id
             WHERE e.clinic_id=? AND e.id=?
            """,clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        Map<String,Object> row=new LinkedHashMap<>(rows.getFirst());
        byte[] encrypted=(byte[])row.remove("payload_ciphertext");
        try{row.put("payload",mapper.readTree(protection.decrypt(encrypted)));}catch(Exception ex){return conflict("Não foi possível ler o conteúdo protegido do evento.");}
        return ResponseEntity.ok(row);
    }

    @PostMapping("/events")
    @Transactional
    public ResponseEntity<?> createEvent(Authentication auth,@Valid @RequestBody EventRequest body){
        Access a=access(auth);if(a==null)return forbidden();
        String type=body.eventType().trim().toUpperCase(Locale.ROOT);
        if(!TYPES.contains(type))return badRequest("Tipo de evento SST não suportado.");
        if("S-2210".equals(type)&&!"CLINIC_ADMIN".equals(a.role()))return forbidden();
        if(!protection.isConfigured())return badRequest("Proteção de dados pessoais indisponível no servidor.");
        UUID clinic=TenantContext.requireClinicId();
        if("S-2220".equals(type)&&body.sourceExamOrderId()!=null){
            Integer count=jdbc.queryForObject("SELECT count(*) FROM exam_orders WHERE clinic_id=? AND id=? AND status='COMPLETED'",
                Integer.class,clinic,body.sourceExamOrderId());
            if(count==null||count==0)return badRequest("O exame vinculado precisa estar concluído nesta clínica.");
        }
        if(jdbc.queryForObject("SELECT count(*) FROM esocial_employers WHERE clinic_id=?",Integer.class,clinic)==0)
            return conflict("Configure os dados do empregador antes de criar eventos.");
        if(!workerExists(clinic,body.workerId()))return badRequest("Trabalhador não encontrado ou inativo nesta clínica.");
        List<String> errors=validatePayload(type,body.payload(),body.eventDate());
        String payload=json(body.payload());
        UUID id=UUID.randomUUID();
        try{jdbc.update("""
            INSERT INTO esocial_events(id,clinic_id,worker_id,event_type,event_date,event_sequence,payload_ciphertext,
                validation_errors,source_exam_order_id,created_by_membership_id,updated_by_membership_id)
            VALUES(?,?,?,?,?,?,?,?::jsonb,?,?,?)
            """,id,clinic,body.workerId(),type,body.eventDate(),body.eventSequence(),protection.encrypt(payload),
            json(mapper.valueToTree(errors)),body.sourceExamOrderId(),a.membershipId(),a.membershipId());
        }catch(DuplicateKeyException ex){return conflict("Já existe evento com esta sequência para o trabalhador.");}
        audit(clinic,id,"CREATED","Rascunho "+type+" criado",a.membershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"status","DRAFT","validationErrors",errors));
    }

    @PostMapping("/events/{id}/validate")
    @Transactional
    public ResponseEntity<?> validateEvent(Authentication auth,@PathVariable UUID id){
        Access a=access(auth);if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT e.event_type,e.event_date,e.payload_ciphertext,e.status
              FROM esocial_events e WHERE e.clinic_id=? AND e.id=? FOR UPDATE
            """,clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        Map<String,Object> row=rows.getFirst();String status=String.valueOf(row.get("status"));
        if(!"DRAFT".equals(status))return conflict("Somente rascunhos podem ser validados.");
        JsonNode payload;
        try{payload=mapper.readTree(protection.decrypt((byte[])row.get("payload_ciphertext")));}
        catch(Exception ex){return conflict("O conteúdo protegido do evento está ilegível.");}
        List<String> errors=validatePayload(String.valueOf(row.get("event_type")),payload,(LocalDate)row.get("event_date"));
        jdbc.update("UPDATE esocial_events SET validation_errors=?::jsonb,updated_at=now(),updated_by_membership_id=? WHERE clinic_id=? AND id=?",
            json(mapper.valueToTree(errors)),a.membershipId(),clinic,id);
        audit(clinic,id,"VALIDATED",errors.isEmpty()?"Validação local aprovada":"Validação local encontrou "+errors.size()+" pendência(s)",a.membershipId());
        if(!errors.isEmpty())return ResponseEntity.unprocessableEntity().body(Map.of("id",id,"valid",false,"errors",errors));
        jdbc.update("UPDATE esocial_events SET status='READY',updated_at=now(),updated_by_membership_id=? WHERE clinic_id=? AND id=?",
            a.membershipId(),clinic,id);
        audit(clinic,id,"MARKED_READY","Evento preparado; ainda não transmitido ao ambiente oficial",a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"valid",true,"status","READY",
            "message","Validação local concluída. O evento ainda não foi transmitido ao eSocial."));
    }

    @PostMapping("/events/{id}/cancel")
    @Transactional
    public ResponseEntity<?> cancelEvent(Authentication auth,@PathVariable UUID id){
        Access a=admin(auth);if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        int changed=jdbc.update("""
            UPDATE esocial_events SET status='CANCELLED',updated_at=now(),updated_by_membership_id=?
             WHERE clinic_id=? AND id=? AND status IN ('DRAFT','READY','REJECTED')
            """,a.membershipId(),clinic,id);
        if(changed==0)return conflict("Evento não encontrado ou não pode ser cancelado nesta situação.");
        audit(clinic,id,"CANCELLED","Evento cancelado antes da transmissão oficial",a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"status","CANCELLED"));
    }

    @GetMapping("/events/{id}/history")
    public ResponseEntity<?> history(Authentication auth,@PathVariable UUID id){
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(jdbc.queryForObject("SELECT count(*) FROM esocial_events WHERE clinic_id=? AND id=?",Integer.class,clinic,id)==0)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT a.action,a.detail,a.created_at,u.full_name AS actor_name
              FROM esocial_event_audit a JOIN clinic_memberships m ON m.clinic_id=a.clinic_id AND m.id=a.actor_membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE a.clinic_id=? AND a.event_id=? ORDER BY a.created_at
            """,clinic,id));
    }

    private List<String> validatePayload(String type,JsonNode p,LocalDate date){
        List<String> errors=new ArrayList<>();
        if(p==null||!p.isObject()){errors.add("Informe os dados estruturados do evento.");return errors;}
        if(date.isAfter(LocalDate.now()))errors.add("A data do evento não pode estar no futuro.");
        List<String> required=switch(type){
            case "S-2210"->List.of("accidentDate","accidentType","accidentLocation","description","medicalCare");
            case "S-2220"->List.of("examDate","examType","doctorName","doctorCouncil","doctorNumber","result");
            case "S-2240"->List.of("startDate","environment","exposureCode","riskDescription","protectionType");
            default->List.of();
        };
        for(String field:required)if(!p.hasNonNull(field)||p.get(field).asText().isBlank())
            errors.add("Campo obrigatório ausente: "+field+".");
        if("S-2220".equals(type)&&p.hasNonNull("examDate"))try{
            if(LocalDate.parse(p.get("examDate").asText()).isAfter(date))errors.add("A data do exame não pode ser posterior à data do evento.");
        }catch(Exception ex){errors.add("Data do exame inválida.");}
        if("S-2210".equals(type)&&p.hasNonNull("accidentDate"))try{
            if(LocalDate.parse(p.get("accidentDate").asText()).isAfter(date))errors.add("A data do acidente não pode ser posterior à data de comunicação.");
        }catch(Exception ex){errors.add("Data do acidente inválida.");}
        if("S-2240".equals(type)&&p.hasNonNull("startDate"))try{LocalDate.parse(p.get("startDate").asText());}
            catch(Exception ex){errors.add("Data de início da condição ambiental inválida.");}
        return errors;
    }
    private String json(JsonNode value){try{return mapper.writeValueAsString(value);}catch(Exception ex){throw new IllegalArgumentException("Conteúdo estruturado inválido.",ex);}}
    private boolean workerExists(UUID clinic,UUID worker){
        return jdbc.queryForObject("SELECT count(*) FROM esocial_workers WHERE clinic_id=? AND id=? AND status='ACTIVE'",
            Integer.class,clinic,worker)>0;
    }
    private void audit(UUID clinic,UUID id,String action,String detail,UUID actor){
        jdbc.update("INSERT INTO esocial_event_audit(clinic_id,event_id,action,detail,actor_membership_id) VALUES(?,?,?,?,?)",
            clinic,id,action,detail,actor);
    }
    private Access access(Authentication auth){
        if(auth==null||!auth.isAuthenticated())return null;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id,m.role FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
            """,auth.getName(),TenantContext.requireClinicId());
        if(rows.isEmpty())return null;String role=String.valueOf(rows.getFirst().get("role"));
        if(!List.of("CLINIC_ADMIN","PROFESSIONAL").contains(role))return null;
        return new Access((UUID)rows.getFirst().get("id"),role);
    }
    private Access admin(Authentication auth){Access a=access(auth);return a!=null&&"CLINIC_ADMIN".equals(a.role())?a:null;}
    private boolean validCpf(String cpf){
        if(!cpf.matches("\\d{11}")||cpf.chars().distinct().count()==1)return false;
        int sum=0;for(int i=0;i<9;i++)sum+=(cpf.charAt(i)-'0')*(10-i);
        int d1=(sum*10)%11;if(d1==10)d1=0;if(d1!=cpf.charAt(9)-'0')return false;
        sum=0;for(int i=0;i<10;i++)sum+=(cpf.charAt(i)-'0')*(11-i);
        int d2=(sum*10)%11;if(d2==10)d2=0;return d2==cpf.charAt(10)-'0';
    }
    private String clean(String v){if(v==null)return null;String s=v.trim();return s.isEmpty()?null:s;}
    private Object[] append(Object[] a,Object... b){Object[] r=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,r,a.length,b.length);return r;}
    private ResponseEntity<Map<String,String>> forbidden(){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","Seu perfil não possui acesso a este recurso do eSocial."));}
    private ResponseEntity<Map<String,String>> badRequest(String m){return ResponseEntity.badRequest().body(Map.of("error",m));}
    private ResponseEntity<Map<String,String>> conflict(String m){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",m));}
    public record EmployerRequest(@NotBlank @Size(max=8) String registrationType,@NotBlank @Size(max=30) String registrationNumber,
        @NotBlank @Size(max=180) String legalName,@Size(max=12) String classificationCode,
        @NotBlank String environment,@Size(max=160) String contactName,@Email @Size(max=180) String contactEmail,@Size(max=30) String contactPhone){}
    public record WorkerRequest(UUID patientId,@NotBlank @Size(max=14) String cpf,@NotBlank @Size(max=180) String fullName,
        @NotNull LocalDate birthDate,@NotBlank @Size(max=1) String sex,@NotBlank @Size(max=60) String registrationCode,
        @NotNull LocalDate admissionDate,@NotBlank @Size(max=8) String categoryCode,@Size(max=8) String cboCode,
        @NotBlank @Size(max=120) String jobTitle,@Size(max=120) String department,@Size(max=180) String workLocation){}
    public record WorkerStatus(@NotNull Boolean active){}
    public record EventRequest(@NotNull UUID workerId,@NotBlank @Size(max=8) String eventType,@NotNull LocalDate eventDate,
        @NotNull @Min(1) @Max(999) Integer eventSequence,@NotNull JsonNode payload,UUID sourceExamOrderId){}
    private record Access(UUID membershipId,String role){}
}
