package br.com.markys.gestaoclinica.api;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/subscription")
public class SubscriptionController {
    private final JdbcTemplate jdbc;
    public SubscriptionController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public ResponseEntity<?> current(Authentication auth) {
        Access access=access(auth);
        if(access==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> subscriptions=jdbc.queryForList("""
            SELECT s.id,s.status,s.payment_method,s.started_at,s.current_period_start,
                   s.current_period_end,s.next_due_date,p.code AS plan_code,p.name AS plan_name,
                   p.description AS plan_description,p.monthly_price
              FROM clinic_subscriptions s
              JOIN subscription_plans p ON p.id=s.plan_id
             WHERE s.clinic_id=? AND s.status NOT IN ('CANCELLED','EXPIRED')
             ORDER BY s.created_at DESC LIMIT 1
        """,clinic);
        List<Map<String,Object>> payments=jdbc.queryForList("""
            SELECT id,amount,payment_method,status,due_date,paid_at,checkout_url,
                   boleto_url,boleto_barcode,pix_copy_paste,pix_qr_code,created_at
              FROM subscription_payments
             WHERE clinic_id=? ORDER BY due_date DESC,created_at DESC LIMIT 36
        """,clinic);
        List<Map<String,Object>> plans=jdbc.queryForList("""
            SELECT id,code,name,description,monthly_price,billing_interval
              FROM subscription_plans WHERE active=true ORDER BY monthly_price,name
        """);
        Map<String,Object> current=subscriptions.isEmpty()?Map.of():subscriptions.getFirst();
        return ResponseEntity.ok(Map.of("subscription",current,"payments",payments,"plans",plans,
            "paymentMethods",List.of(
                Map.of("code","CREDIT_CARD","label","Cartão de crédito","recurrence","Recorrência automática; integração pendente","enabled",false),
                Map.of("code","PIX","label","PIX","recurrence","Cobrança mensal individual; integração pendente","enabled",false),
                Map.of("code","BOLETO","label","Boleto bancário","recurrence","Emissão recorrente via API; integração pendente","enabled",false)
            ),"gatewayCredentialsConfigured",gatewayCredentialsConfigured(),"integrationReady",false));
    }

    private boolean gatewayCredentialsConfigured() {
        return System.getenv("PICPAY_CLIENT_ID")!=null && !System.getenv("PICPAY_CLIENT_ID").isBlank()
            && System.getenv("PICPAY_CLIENT_SECRET")!=null && !System.getenv("PICPAY_CLIENT_SECRET").isBlank();
    }
    private Access access(Authentication auth) {
        if(auth==null||!auth.isAuthenticated())return null;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id AS membership_id,m.role FROM clinic_memberships m
              JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
        """,auth.getName(),TenantContext.requireClinicId());
        if(rows.isEmpty())return null;
        Map<String,Object> row=rows.getFirst();String role=String.valueOf(row.get("role"));
        if(!List.of("CLINIC_ADMIN","RECEPTION","PROFESSIONAL").contains(role))return null;
        return new Access((UUID)row.get("membership_id"),role);
    }
    private ResponseEntity<Map<String,String>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error","Seu perfil não tem permissão para esta operação."));
    }
    private record Access(UUID membershipId,String role) {}
}
