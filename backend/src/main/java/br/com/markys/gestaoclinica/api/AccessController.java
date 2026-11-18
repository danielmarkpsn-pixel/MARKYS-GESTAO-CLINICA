package br.com.markys.gestaoclinica.api;

import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/access")
public class AccessController {
    private static final List<String> ROLES=List.of("CLINIC_ADMIN","RECEPTION","PROFESSIONAL","TRIAGE","AUDITOR","LAB_TECHNICIAN");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    public AccessController(JdbcTemplate jdbc,PasswordEncoder passwordEncoder){
        this.jdbc=jdbc;this.passwordEncoder=passwordEncoder;
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth,
            @RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="ALL") String role,
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size){
        Access actor=admin(auth);if(actor==null)return forbidden();
        if(page<1||size<1||size>100)return badRequest("Paginação inválida.");
        String selectedRole=role.trim().toUpperCase(Locale.ROOT);
        if(!"ALL".equals(selectedRole)&&!ROLES.contains(selectedRole))return badRequest("Perfil inválido.");
        String selectedStatus=status.trim().toUpperCase(Locale.ROOT);
        if(!List.of("ALL","ACTIVE","SUSPENDED","REVOKED").contains(selectedStatus))return badRequest("Situação de acesso inválida.");
        String term=search==null?"":search.trim();if(term.length()>120)term=term.substring(0,120);
        UUID clinic=TenantContext.requireClinicId();
        String base="""
            FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
            LEFT JOIN professionals p ON p.clinic_id=m.clinic_id AND p.membership_id=m.id
            WHERE m.clinic_id=? AND (?='ALL' OR m.role=?)
              AND (?='ALL' OR m.status=?)
              AND (?='' OR u.full_name ILIKE ? OR u.email ILIKE ?)
        """;
        Object[] args={clinic,selectedRole,selectedRole,selectedStatus,selectedStatus,term,"%"+term+"%","%"+term+"%"};
        Integer total=jdbc.queryForObject("SELECT count(*) "+base,Integer.class,args);
        List<Map<String,Object>> items=jdbc.queryForList("""
            SELECT m.id AS membership_id,u.id AS user_id,u.full_name,u.email,m.role,m.status AS membership_status,
                   u.status AS account_status,u.last_login_at,u.locked_until,u.failed_login_attempts,
                   m.created_at,m.updated_at,p.id AS professional_id,p.display_name AS professional_name,
                   CASE WHEN u.locked_until>now() OR u.status='LOCKED' THEN true ELSE false END AS is_locked
            """+base+"""
            ORDER BY CASE m.status WHEN 'ACTIVE' THEN 0 WHEN 'SUSPENDED' THEN 1 ELSE 2 END,
                     lower(u.full_name)
            LIMIT ? OFFSET ?
        """,append(args,size,(page-1)*size));
        Map<String,Object> summary=jdbc.queryForMap("""
            SELECT count(*) AS total,
              count(*) FILTER(WHERE m.status='ACTIVE') AS active,
              count(*) FILTER(WHERE m.status='SUSPENDED') AS suspended,
              count(*) FILTER(WHERE m.status='REVOKED') AS revoked,
              count(*) FILTER(WHERE u.status='LOCKED' OR u.locked_until>now()) AS locked_accounts
             FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE m.clinic_id=?
        """,clinic);
        return ResponseEntity.ok(Map.of("items",items,"page",page,"size",size,"total",total==null?0:total,
            "pages",total==null?0:(int)Math.ceil(total/(double)size),"summary",summary));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(Authentication auth,@Valid @RequestBody CreateRequest body){
        Access actor=admin(auth);if(actor==null)return forbidden();
        String role=body.role().trim().toUpperCase(Locale.ROOT);
        if(!ROLES.contains(role))return badRequest("Perfil de acesso inválido.");
        String email=body.email().trim().toLowerCase(Locale.ROOT);
        UUID clinic=TenantContext.requireClinicId(),userId;
        List<Map<String,Object>> users=jdbc.queryForList(
            "SELECT id,status FROM app_users WHERE lower(email)=lower(?) FOR UPDATE",email);
        boolean reused=!users.isEmpty();
        if(!reused){
            if(body.initialPassword()==null||body.initialPassword().length()<12)
                return badRequest("A senha inicial deve ter pelo menos 12 caracteres.");
            if(body.initialPassword().getBytes(StandardCharsets.UTF_8).length>72)
                return badRequest("A senha inicial excede o limite seguro de 72 bytes.");
            userId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO app_users(id,full_name,email,password_hash,status,password_changed_at)
                VALUES(?,?,?,?, 'ACTIVE', now())
            """,userId,body.fullName().trim(),email,passwordEncoder.encode(body.initialPassword()));
        }else{
            Map<String,Object> existing=users.getFirst();
            if(!"ACTIVE".equals(String.valueOf(existing.get("status"))))
                return conflict("Este e-mail já existe, mas a conta está inativa ou bloqueada globalmente. Solicite a reativação ao administrador da plataforma.");
            userId=(UUID)existing.get("id");
            if(scalar("SELECT count(*) FROM clinic_memberships WHERE clinic_id=? AND user_id=?",clinic,userId)>0)
                return conflict("Este usuário já possui acesso a esta clínica. Edite ou reative o acesso existente.");
        }
        UUID membershipId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO clinic_memberships(id,clinic_id,user_id,role,status,updated_at)
            VALUES(?,?,?,?,'ACTIVE',now())
        """,membershipId,clinic,userId,role);
        record(clinic,membershipId,"CREATED",reused
            ?"Acesso criado usando uma conta já existente; senha preservada"
            :"Conta e acesso criados",actor.membershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("membershipId",membershipId,
            "userReused",reused,"message",reused?"Conta existente vinculada à clínica; senha não alterada.":"Conta e acesso criados."));
    }

    @PatchMapping("/{membershipId}/role")
    @Transactional
    public ResponseEntity<?> changeRole(Authentication auth,@PathVariable UUID membershipId,@Valid @RequestBody RoleRequest body){
        Access actor=admin(auth);if(actor==null)return forbidden();
        if(actor.membershipId().equals(membershipId))return conflict("Não é permitido alterar o próprio perfil de acesso.");
        String next=body.role().trim().toUpperCase(Locale.ROOT);
        if(!ROLES.contains(next))return badRequest("Perfil de acesso inválido.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT role,status FROM clinic_memberships WHERE clinic_id=? AND id=? FOR UPDATE
        """,clinic,membershipId);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        String previous=String.valueOf(rows.getFirst().get("role"));
        String status=String.valueOf(rows.getFirst().get("status"));
        if(previous.equals(next))return ResponseEntity.ok(Map.of("membershipId",membershipId,"role",next,"message","Perfil já está atualizado."));
        if("CLINIC_ADMIN".equals(previous)&&"ACTIVE".equals(status)&&!"CLINIC_ADMIN".equals(next)
            &&scalar("SELECT count(*) FROM clinic_memberships WHERE clinic_id=? AND role='CLINIC_ADMIN' AND status='ACTIVE'",clinic)<=1)
            return conflict("A clínica precisa manter pelo menos um administrador ativo.");
        jdbc.update("UPDATE clinic_memberships SET role=?,updated_at=now() WHERE clinic_id=? AND id=?",next,clinic,membershipId);
        record(clinic,membershipId,"ROLE_CHANGED","Perfil alterado de "+previous+" para "+next,actor.membershipId());
        return ResponseEntity.ok(Map.of("membershipId",membershipId,"role",next,"message","Perfil atualizado."));
    }

    @PatchMapping("/{membershipId}/status")
    @Transactional
    public ResponseEntity<?> changeStatus(Authentication auth,@PathVariable UUID membershipId,@Valid @RequestBody ActiveRequest body){
        Access actor=admin(auth);if(actor==null)return forbidden();
        if(actor.membershipId().equals(membershipId))return conflict("Não é permitido suspender ou reativar o próprio acesso.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT role,status FROM clinic_memberships WHERE clinic_id=? AND id=? FOR UPDATE
        """,clinic,membershipId);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        String role=String.valueOf(rows.getFirst().get("role")),previous=String.valueOf(rows.getFirst().get("status"));
        if("REVOKED".equals(previous))return conflict("Este acesso foi revogado e não pode ser reativado.");
        String next=body.active()?"ACTIVE":"SUSPENDED";
        if(previous.equals(next))return ResponseEntity.ok(Map.of("membershipId",membershipId,"status",next,"message","Situação já atualizada."));
        if("CLINIC_ADMIN".equals(role)&&"ACTIVE".equals(previous)&&"SUSPENDED".equals(next)
            &&scalar("SELECT count(*) FROM clinic_memberships WHERE clinic_id=? AND role='CLINIC_ADMIN' AND status='ACTIVE'",clinic)<=1)
            return conflict("Não é possível suspender o último administrador ativo da clínica.");
        jdbc.update("UPDATE clinic_memberships SET status=?,updated_at=now() WHERE clinic_id=? AND id=?",next,clinic,membershipId);
        record(clinic,membershipId,body.active()?"REACTIVATED":"SUSPENDED",
            body.active()?"Acesso reativado":"Acesso suspenso",actor.membershipId());
        return ResponseEntity.ok(Map.of("membershipId",membershipId,"status",next,"message",body.active()?"Acesso reativado.":"Acesso suspenso."));
    }

    @PatchMapping("/{membershipId}/password")
    @Transactional
    public ResponseEntity<?> resetPassword(Authentication auth,@PathVariable UUID membershipId,@Valid @RequestBody PasswordRequest body){
        Access actor=admin(auth);if(actor==null)return forbidden();
        if(actor.membershipId().equals(membershipId))return conflict("Use a opção de alteração de senha da própria conta.");
        if(body.password().length()<12||body.password().getBytes(StandardCharsets.UTF_8).length>72)
            return badRequest("A nova senha deve ter pelo menos 12 caracteres e no máximo 72 bytes.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT u.id,u.status FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE m.clinic_id=? AND m.id=? FOR UPDATE OF u
        """,clinic,membershipId);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        if(!"ACTIVE".equals(String.valueOf(rows.getFirst().get("status"))))
            return conflict("A conta está inativa globalmente. Solicite a reativação ao administrador da plataforma.");
        UUID userId=(UUID)rows.getFirst().get("id");
        if(scalar("SELECT count(*) FROM clinic_memberships WHERE user_id=? AND clinic_id<>?",userId,clinic)>0)
            return conflict("Esta conta é compartilhada com outra clínica. A redefinição global de senha deve ser feita pelo próprio titular.");
        jdbc.update("""
            UPDATE app_users SET password_hash=?,failed_login_attempts=0,locked_until=NULL,
                password_changed_at=now(),updated_at=now() WHERE id=?
        """,passwordEncoder.encode(body.password()),userId);
        record(clinic,membershipId,"PASSWORD_RESET","Senha redefinida pelo administrador da clínica",actor.membershipId());
        return ResponseEntity.ok(Map.of("membershipId",membershipId,"message","Senha redefinida e bloqueios temporários removidos."));
    }

    @PostMapping("/{membershipId}/unlock")
    @Transactional
    public ResponseEntity<?> unlock(Authentication auth,@PathVariable UUID membershipId){
        Access actor=admin(auth);if(actor==null)return forbidden();
        if(actor.membershipId().equals(membershipId))return conflict("Não é necessário desbloquear o próprio acesso por esta tela.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT u.id,u.status,u.failed_login_attempts,u.locked_until
              FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE m.clinic_id=? AND m.id=? FOR UPDATE OF u
        """,clinic,membershipId);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        if(!"ACTIVE".equals(String.valueOf(rows.getFirst().get("status"))))
            return conflict("A conta está inativa globalmente. Solicite a reativação ao administrador da plataforma.");
        UUID userId=(UUID)rows.getFirst().get("id");
        if(scalar("SELECT count(*) FROM clinic_memberships WHERE user_id=? AND clinic_id<>?",userId,clinic)>0)
            return conflict("Esta conta é compartilhada com outra clínica. O desbloqueio global deve ser feito pelo próprio titular.");
        jdbc.update("UPDATE app_users SET failed_login_attempts=0,locked_until=NULL,updated_at=now() WHERE id=?",userId);
        record(clinic,membershipId,"ACCOUNT_UNLOCKED","Bloqueio temporário removido",actor.membershipId());
        return ResponseEntity.ok(Map.of("membershipId",membershipId,"message","Bloqueio temporário removido."));
    }

    @GetMapping("/{membershipId}")
    public ResponseEntity<?> detail(Authentication auth,@PathVariable UUID membershipId){
        if(admin(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id AS membership_id,u.id AS user_id,u.full_name,u.email,m.role,
                   m.status AS membership_status,u.status AS account_status,u.last_login_at,
                   u.locked_until,u.failed_login_attempts,m.created_at,m.updated_at,
                   CASE WHEN u.locked_until>now() OR u.status='LOCKED' THEN true ELSE false END AS is_locked,
                   p.id AS professional_id,p.display_name AS professional_name
              FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
              LEFT JOIN professionals p ON p.clinic_id=m.clinic_id AND p.membership_id=m.id
             WHERE m.clinic_id=? AND m.id=?
        """,clinic,membershipId);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        return ResponseEntity.ok(rows.getFirst());
    }

    @GetMapping("/{membershipId}/history")
    public ResponseEntity<?> history(Authentication auth,@PathVariable UUID membershipId){
        if(admin(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(scalar("SELECT count(*) FROM clinic_memberships WHERE clinic_id=? AND id=?",clinic,membershipId)==0)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT ev.id,ev.event_type,ev.detail,ev.created_at,u.full_name AS actor_name
              FROM clinic_membership_events ev JOIN clinic_memberships m
                ON m.clinic_id=ev.clinic_id AND m.id=ev.actor_membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE ev.clinic_id=? AND ev.membership_id=? ORDER BY ev.created_at DESC LIMIT 100
        """,clinic,membershipId));
    }

    private Access admin(Authentication auth){
        if(auth==null||!auth.isAuthenticated())return null;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.role='CLINIC_ADMIN'
               AND m.status='ACTIVE' AND u.status='ACTIVE'
        """,auth.getName(),TenantContext.requireClinicId());
        return rows.isEmpty()?null:new Access((UUID)rows.getFirst().get("id"));
    }
    private int scalar(String sql,Object...args){Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;}
    private void record(UUID clinic,UUID membershipId,String type,String detail,UUID actor){
        jdbc.update("""
            INSERT INTO clinic_membership_events(clinic_id,membership_id,event_type,detail,actor_membership_id)
            VALUES(?,?,?,?,?)
        """,clinic,membershipId,type,detail,actor);
    }
    private Object[] append(Object[] values,Object...tail){Object[] result=Arrays.copyOf(values,values.length+tail.length);System.arraycopy(tail,0,result,values.length,tail.length);return result;}
    private ResponseEntity<Map<String,String>> forbidden(){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","Somente o administrador da clínica pode gerenciar acessos."));}
    private ResponseEntity<Map<String,String>> badRequest(String message){return ResponseEntity.badRequest().body(Map.of("error",message));}
    private ResponseEntity<Map<String,String>> conflict(String message){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",message));}
    private record Access(UUID membershipId){}
    public record CreateRequest(@NotBlank @Size(max=180) String fullName,@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=30) String role,@Size(max=72) String initialPassword){}
    public record RoleRequest(@NotBlank @Size(max=30) String role){}
    public record ActiveRequest(@NotNull Boolean active){}
    public record PasswordRequest(@NotBlank @Size(max=72) String password){}
}
