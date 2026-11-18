package br.com.markys.gestaoclinica.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import br.com.markys.gestaoclinica.security.TenantContext;

@RestController
@RequestMapping("/api/v1/clinic/finance")
public class FinanceController {
    private static final List<String> DIRECTIONS = List.of("INCOME", "EXPENSE");
    private static final List<String> STATUSES = List.of("OPEN", "PARTIAL", "PAID", "OVERDUE", "CANCELLED");
    private static final List<String> PAYMENT_METHODS =
        List.of("CASH", "PIX", "DEBIT_CARD", "CREDIT_CARD", "TRANSFER", "BOLETO", "OTHER");
    private final JdbcTemplate jdbc;

    public FinanceController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/resources")
    public ResponseEntity<?> resources(Authentication auth) {
        Access access = access(auth);
        if (access == null) return forbidden();
        UUID clinic = TenantContext.requireClinicId();
        return ResponseEntity.ok(Map.of(
            "role", access.role(),
            "categories", jdbc.queryForList("""
                SELECT id,name,direction FROM finance_categories
                 WHERE clinic_id=? AND active=true ORDER BY direction,lower(name)
                """, clinic),
            "patients", jdbc.queryForList("""
                SELECT id,full_name FROM patients
                 WHERE clinic_id=? AND status='ACTIVE' ORDER BY lower(full_name) LIMIT 500
                """, clinic),
            "appointments", jdbc.queryForList("""
                SELECT a.id,a.starts_at,p.full_name AS patient_name,pr.display_name AS professional_name
                  FROM appointments a
                  JOIN patients p ON p.clinic_id=a.clinic_id AND p.id=a.patient_id
                  JOIN professionals pr ON pr.clinic_id=a.clinic_id AND pr.id=a.professional_id
                 WHERE a.clinic_id=? AND a.status NOT IN ('CANCELLED','NO_SHOW')
                   AND a.starts_at >= now()-interval '90 days'
                 ORDER BY a.starts_at DESC LIMIT 300
                """, clinic)
        ));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(Authentication auth,
            @RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to) {
        Access access = access(auth);
        if (access == null) return forbidden();
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (!validPeriod(start, end)) return badRequest("Informe um período válido de até 366 dias.");
        UUID clinic = TenantContext.requireClinicId();
        Map<String,Object> totals = jdbc.queryForMap("""
            SELECT
              (SELECT COALESCE(sum(e.amount),0) FROM finance_entries e
                WHERE e.clinic_id=c.id AND e.direction='INCOME' AND e.status<>'CANCELLED'
                  AND e.due_date BETWEEN ? AND ?) AS income_due,
              (SELECT COALESCE(sum(e.amount),0) FROM finance_entries e
                WHERE e.clinic_id=c.id AND e.direction='EXPENSE' AND e.status<>'CANCELLED'
                  AND e.due_date BETWEEN ? AND ?) AS expense_due,
              (SELECT COALESCE(sum(fp.amount),0) FROM finance_payments fp
                JOIN finance_entries e ON e.clinic_id=fp.clinic_id AND e.id=fp.entry_id
                WHERE e.clinic_id=c.id AND e.direction='INCOME'
                  AND (fp.paid_at AT TIME ZONE c.timezone)::date BETWEEN ? AND ?) AS income_received,
              (SELECT COALESCE(sum(fp.amount),0) FROM finance_payments fp
                JOIN finance_entries e ON e.clinic_id=fp.clinic_id AND e.id=fp.entry_id
                WHERE e.clinic_id=c.id AND e.direction='EXPENSE'
                  AND (fp.paid_at AT TIME ZONE c.timezone)::date BETWEEN ? AND ?) AS expense_paid,
              (SELECT count(*) FROM finance_entries e WHERE e.clinic_id=c.id
                AND e.status NOT IN ('PAID','CANCELLED') AND e.due_date < (now() AT TIME ZONE c.timezone)::date
                AND e.due_date BETWEEN ? AND ?) AS overdue_count,
              (SELECT count(*) FROM finance_entries e WHERE e.clinic_id=c.id
                AND e.status NOT IN ('PAID','CANCELLED') AND e.due_date BETWEEN ? AND ?) AS open_count
            FROM clinics c WHERE c.id=?
            """, start,end,start,end,start,end,start,end,start,end,start,end,clinic);
        return ResponseEntity.ok(Map.of(
            "from", start, "to", end,
            "incomeDue", money(totals.get("income_due")),
            "expenseDue", money(totals.get("expense_due")),
            "incomeReceived", money(totals.get("income_received")),
            "expensePaid", money(totals.get("expense_paid")),
            "balanceRealized", money(money(totals.get("income_received")).subtract(money(totals.get("expense_paid")))),
            "overdueCount", totals.get("overdue_count"),
            "openCount", totals.get("open_count")
        ));
    }

    @GetMapping("/entries")
    public ResponseEntity<?> entries(Authentication auth,
            @RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to,
            @RequestParam(defaultValue="ALL") String direction,
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="") String search,
            @RequestParam(required=false) UUID categoryId,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="30") int size) {
        if (access(auth) == null) return forbidden();
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now().plusMonths(1).withDayOfMonth(1) : to;
        if (!validPeriod(start, end)) return badRequest("Informe um período válido de até 366 dias.");
        String dir = direction.trim().toUpperCase(Locale.ROOT);
        String state = status.trim().toUpperCase(Locale.ROOT);
        if (!"ALL".equals(dir) && !DIRECTIONS.contains(dir)) return badRequest("Tipo de lançamento inválido.");
        if (!"ALL".equals(state) && !STATUSES.contains(state)) return badRequest("Situação financeira inválida.");
        if (page < 1 || size < 1 || size > 100) return badRequest("Paginação inválida.");
        String term = search == null ? "" : search.trim();
        if (term.length() > 100) term = term.substring(0,100);
        UUID clinic = TenantContext.requireClinicId();
        String filter = """
            FROM finance_entries e
            JOIN finance_categories c ON c.clinic_id=e.clinic_id AND c.id=e.category_id
            LEFT JOIN patients p ON p.clinic_id=e.clinic_id AND p.id=e.patient_id
            LEFT JOIN appointments a ON a.clinic_id=e.clinic_id AND a.id=e.appointment_id
            LEFT JOIN LATERAL (SELECT COALESCE(sum(fp.amount),0) AS paid
                FROM finance_payments fp WHERE fp.clinic_id=e.clinic_id AND fp.entry_id=e.id) pay ON true
            WHERE e.clinic_id=? AND e.due_date BETWEEN ? AND ?
              AND (?='ALL' OR e.direction=?) AND (?='ALL' OR
                CASE WHEN e.status IN ('OPEN','PARTIAL') AND e.due_date<(SELECT (now() AT TIME ZONE cl.timezone)::date FROM clinics cl WHERE cl.id=e.clinic_id) THEN 'OVERDUE' ELSE e.status END=?)
              AND (?::uuid IS NULL OR e.category_id=?)
              AND (?='' OR e.description ILIKE ? OR COALESCE(p.full_name,'') ILIKE ? OR c.name ILIKE ?)
            """;
        Object[] args = {clinic,start,end,dir,dir,state,state,categoryId,categoryId,term,
            "%"+term+"%","%"+term+"%","%"+term+"%"};
        Integer total = jdbc.queryForObject("SELECT count(*) "+filter, Integer.class, args);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT e.id,e.direction,e.description,e.amount,e.due_date,
                   CASE WHEN e.status IN ('OPEN','PARTIAL') AND e.due_date<(SELECT (now() AT TIME ZONE cl.timezone)::date FROM clinics cl WHERE cl.id=e.clinic_id) THEN 'OVERDUE' ELSE e.status END AS status,
                   e.notes,e.created_at,e.category_id,c.name AS category_name,
                   e.patient_id,p.full_name AS patient_name,e.appointment_id,a.starts_at AS appointment_at,
                   pay.paid AS amount_paid,(e.amount-pay.paid) AS amount_remaining
            """+filter+"""
            ORDER BY e.due_date DESC,e.created_at DESC
            LIMIT ? OFFSET ?
            """, append(args, size, (page-1)*size));
        return ResponseEntity.ok(Map.of("items",rows,"page",page,"size",size,"total",total==null?0:total,
            "pages",total==null?0:(int)Math.ceil(total/(double)size)));
    }

    @GetMapping("/categories")
    public ResponseEntity<?> categories(Authentication auth,@RequestParam(defaultValue="false") boolean all) {
        Access access=access(auth);
        if(access==null)return forbidden();
        boolean includeInactive=all && "CLINIC_ADMIN".equals(access.role());
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT id,name,direction,active FROM finance_categories
             WHERE clinic_id=? AND (? OR active=true)
             ORDER BY direction,lower(name)
            """,TenantContext.requireClinicId(),includeInactive));
    }

    @PostMapping("/categories")
    @Transactional
    public ResponseEntity<?> createCategory(Authentication auth, @Valid @RequestBody CategoryRequest body) {
        Access access=access(auth);
        if(access==null || !"CLINIC_ADMIN".equals(access.role()))return forbidden();
        String direction=body.direction().trim().toUpperCase(Locale.ROOT);
        if(!List.of("INCOME","EXPENSE","BOTH").contains(direction))return badRequest("Tipo de categoria inválido.");
        UUID id=UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO finance_categories(id,clinic_id,name,direction) VALUES(?,?,?,?)",
                id,TenantContext.requireClinicId(),body.name().trim(),direction);
        } catch(DuplicateKeyException ex) { return conflict("Já existe uma categoria com esse nome e tipo."); }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"name",body.name().trim(),"direction",direction));
    }

    @PatchMapping("/categories/{id}/status")
    @Transactional
    public ResponseEntity<?> categoryStatus(Authentication auth,@PathVariable UUID id,@Valid @RequestBody ActiveRequest body) {
        Access access=access(auth);
        if(access==null || !"CLINIC_ADMIN".equals(access.role()))return forbidden();
        int changed=jdbc.update("UPDATE finance_categories SET active=?,updated_at=now() WHERE clinic_id=? AND id=?",
            body.active(),TenantContext.requireClinicId(),id);
        return changed==0?ResponseEntity.notFound().build():ResponseEntity.ok(Map.of("id",id,"active",body.active()));
    }

    @PostMapping("/entries")
    @Transactional
    public ResponseEntity<?> create(Authentication auth,@Valid @RequestBody EntryRequest body) {
        Access access=access(auth);
        if(access==null)return forbidden();
        String direction=body.direction().trim().toUpperCase(Locale.ROOT);
        if(!DIRECTIONS.contains(direction))return badRequest("Tipo deve ser RECEITA ou DESPESA.");
        if("EXPENSE".equals(direction) && !"CLINIC_ADMIN".equals(access.role()))return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(!validReferences(clinic,body.categoryId(),direction,body.patientId(),body.appointmentId()))
            return badRequest("Categoria, paciente ou agendamento inválido para esta clínica.");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO finance_entries(id,clinic_id,direction,description,category_id,patient_id,appointment_id,
                amount,due_date,notes,created_by_membership_id,updated_by_membership_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,clinic,direction,body.description().trim(),body.categoryId(),body.patientId(),body.appointmentId(),
            body.amount().setScale(2,RoundingMode.HALF_UP),body.dueDate(),clean(body.notes()),access.membershipId(),access.membershipId());
        recordEvent(clinic,id,"CREATED","Lançamento financeiro criado",access.membershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"status","OPEN","message","Lançamento criado."));
    }

    @PutMapping("/entries/{id}")
    @Transactional
    public ResponseEntity<?> update(Authentication auth,@PathVariable UUID id,@Valid @RequestBody EntryRequest body) {
        Access access=access(auth);
        if(access==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> existing=jdbc.queryForList("""
            SELECT direction,status FROM finance_entries WHERE clinic_id=? AND id=? FOR UPDATE
            """,clinic,id);
        if(existing.isEmpty())return ResponseEntity.notFound().build();
        String direction=body.direction().trim().toUpperCase(Locale.ROOT);
        String oldDirection=String.valueOf(existing.getFirst().get("direction"));
        if(!DIRECTIONS.contains(direction) || ("EXPENSE".equals(direction) && !"CLINIC_ADMIN".equals(access.role()))
            || (!"CLINIC_ADMIN".equals(access.role()) && !"INCOME".equals(oldDirection)))
            return forbidden();
        if(!"OPEN".equals(existing.getFirst().get("status")))return conflict("Somente lançamentos em aberto podem ser editados.");
        Integer paymentCount=jdbc.queryForObject("SELECT count(*) FROM finance_payments WHERE clinic_id=? AND entry_id=?",Integer.class,clinic,id);
        if(paymentCount!=null && paymentCount>0)return conflict("Lançamentos com movimentação financeira não podem ser editados.");
        if(!validReferences(clinic,body.categoryId(),direction,body.patientId(),body.appointmentId()))
            return badRequest("Categoria, paciente ou agendamento inválido para esta clínica.");
        jdbc.update("""
            UPDATE finance_entries SET direction=?,description=?,category_id=?,patient_id=?,appointment_id=?,
                amount=?,due_date=?,notes=?,updated_by_membership_id=?,updated_at=now()
             WHERE clinic_id=? AND id=?
            """,direction,body.description().trim(),body.categoryId(),body.patientId(),body.appointmentId(),
            body.amount().setScale(2,RoundingMode.HALF_UP),body.dueDate(),clean(body.notes()),access.membershipId(),clinic,id);
        recordEvent(clinic,id,"UPDATED","Lançamento financeiro atualizado",access.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"message","Lançamento atualizado."));
    }

    @PostMapping("/entries/{id}/payments")
    @Transactional
    public ResponseEntity<?> pay(Authentication auth,@PathVariable UUID id,@Valid @RequestBody PaymentRequest body) {
        Access access=access(auth);
        if(access==null)return forbidden();
        String method=body.paymentMethod().trim().toUpperCase(Locale.ROOT);
        if(!PAYMENT_METHODS.contains(method))return badRequest("Forma de pagamento inválida.");
        if(body.paidAt()!=null && body.paidAt().isAfter(OffsetDateTime.now().plusMinutes(5)))
            return badRequest("A data da movimentação não pode estar no futuro.");
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT direction,amount,status FROM finance_entries
             WHERE clinic_id=? AND id=? FOR UPDATE
            """,clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        String direction=String.valueOf(rows.getFirst().get("direction"));
        if("EXPENSE".equals(direction) && !"CLINIC_ADMIN".equals(access.role()))return forbidden();
        String state=String.valueOf(rows.getFirst().get("status"));
        if("CANCELLED".equals(state) || "PAID".equals(state))return conflict("Este lançamento não aceita novos pagamentos.");
        BigDecimal total=(BigDecimal)rows.getFirst().get("amount");
        BigDecimal paid=jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM finance_payments WHERE clinic_id=? AND entry_id=?",
            BigDecimal.class,clinic,id);
        BigDecimal amount=body.amount().setScale(2,RoundingMode.HALF_UP);
        BigDecimal remaining=total.subtract(paid==null?BigDecimal.ZERO:paid);
        if(amount.compareTo(remaining)>0)return badRequest("O valor informado supera o saldo devedor de "+remaining.setScale(2,RoundingMode.HALF_UP)+".");
        UUID paymentId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO finance_payments(id,clinic_id,entry_id,amount,payment_method,paid_at,reference,notes,recorded_by_membership_id)
            VALUES(?,?,?,?,?,COALESCE(?,now()),?,?,?)
            """,paymentId,clinic,id,amount,method,body.paidAt(),clean(body.reference()),clean(body.notes()),access.membershipId());
        BigDecimal newPaid=(paid==null?BigDecimal.ZERO:paid).add(amount);
        String next=newPaid.compareTo(total)==0?"PAID":"PARTIAL";
        jdbc.update("UPDATE finance_entries SET status=?,updated_by_membership_id=?,updated_at=now() WHERE clinic_id=? AND id=?",
            next,access.membershipId(),clinic,id);
        recordEvent(clinic,id,"PAYMENT_RECORDED","Movimentação de "+amount.toPlainString()+" registrada ("+method+")",access.membershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",paymentId,"status",next,"amountPaid",newPaid,"amountRemaining",total.subtract(newPaid)));
    }

    @PostMapping("/entries/{id}/cancel")
    @Transactional
    public ResponseEntity<?> cancel(Authentication auth,@PathVariable UUID id,@Valid @RequestBody CancelRequest body) {
        Access access=access(auth);
        if(access==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT direction,status FROM finance_entries WHERE clinic_id=? AND id=? FOR UPDATE",clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        String direction=String.valueOf(rows.getFirst().get("direction"));
        if("EXPENSE".equals(direction) && !"CLINIC_ADMIN".equals(access.role()))return forbidden();
        if(!"CLINIC_ADMIN".equals(access.role()) && !"INCOME".equals(direction))return forbidden();
        if("CANCELLED".equals(rows.getFirst().get("status")))return conflict("Este lançamento já está cancelado.");
        Integer payments=jdbc.queryForObject("SELECT count(*) FROM finance_payments WHERE clinic_id=? AND entry_id=?",Integer.class,clinic,id);
        if(payments!=null && payments>0)return conflict("Não é permitido cancelar um lançamento que já possui pagamentos. Registre a devolução ou ajuste contábil correspondente.");
        jdbc.update("UPDATE finance_entries SET status='CANCELLED',notes=concat_ws(E'\\n',notes,?),updated_by_membership_id=?,updated_at=now() WHERE clinic_id=? AND id=?",
            "Cancelamento: "+body.reason().trim(),access.membershipId(),clinic,id);
        recordEvent(clinic,id,"CANCELLED","Cancelamento: "+body.reason().trim(),access.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"status","CANCELLED"));
    }

    @GetMapping("/entries/{id}/payments")
    public ResponseEntity<?> payments(Authentication auth,@PathVariable UUID id) {
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        Integer exists=jdbc.queryForObject("SELECT count(*) FROM finance_entries WHERE clinic_id=? AND id=?",Integer.class,clinic,id);
        if(exists==null || exists==0)return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT fp.id,fp.amount,fp.payment_method,fp.paid_at,fp.reference,fp.notes,u.full_name AS recorded_by
              FROM finance_payments fp
              JOIN clinic_memberships m ON m.clinic_id=fp.clinic_id AND m.id=fp.recorded_by_membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE fp.clinic_id=? AND fp.entry_id=? ORDER BY fp.paid_at DESC,fp.created_at DESC
            """,clinic,id));
    }

    @GetMapping("/entries/{id}/history")
    public ResponseEntity<?> history(Authentication auth,@PathVariable UUID id) {
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT ev.id,ev.event_type,ev.detail,ev.created_at,u.full_name AS actor_name
              FROM finance_events ev
              JOIN clinic_memberships m ON m.clinic_id=ev.clinic_id AND m.id=ev.actor_membership_id
              JOIN app_users u ON u.id=m.user_id
             WHERE ev.clinic_id=? AND ev.entry_id=?
             ORDER BY ev.created_at DESC LIMIT 100
            """,clinic,id));
    }

    @GetMapping("/insurance-exam-prices/resources")
    public ResponseEntity<?> insuranceExamPriceResources(Authentication auth) {
        Access a=access(auth);
        if(a==null || !List.of("CLINIC_ADMIN","RECEPTION").contains(a.role()))return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        return ResponseEntity.ok(Map.of(
            "insurances",jdbc.queryForList("SELECT id,name FROM clinic_insurances WHERE clinic_id=? AND status='ACTIVE' ORDER BY lower(name)",clinic),
            "exams",jdbc.queryForList("SELECT id,name,category FROM exam_catalog WHERE clinic_id=? AND active=true ORDER BY category,lower(name)",clinic)
        ));
    }

    @GetMapping("/insurance-exam-prices")
    public ResponseEntity<?> insuranceExamPrices(Authentication auth,@RequestParam UUID insuranceId) {
        Access a=access(auth);
        if(a==null || !List.of("CLINIC_ADMIN","RECEPTION").contains(a.role()))return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(scalar("SELECT count(*) FROM clinic_insurances WHERE clinic_id=? AND id=?",clinic,insuranceId)==0)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT p.id,p.exam_id,e.name AS exam_name,e.category,p.amount,p.active,p.updated_at
              FROM insurance_exam_prices p JOIN exam_catalog e
                ON e.clinic_id=p.clinic_id AND e.id=p.exam_id
             WHERE p.clinic_id=? AND p.insurance_id=?
             ORDER BY e.category,lower(e.name)
        """,clinic,insuranceId));
    }

    @PutMapping("/insurance-exam-prices")
    @Transactional
    public ResponseEntity<?> saveInsuranceExamPrice(Authentication auth,@Valid @RequestBody InsuranceExamPriceRequest body) {
        Access a=access(auth);
        if(a==null || !"CLINIC_ADMIN".equals(a.role()))return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(scalar("SELECT count(*) FROM clinic_insurances WHERE clinic_id=? AND id=? AND status='ACTIVE'",clinic,body.insuranceId())==0)
            return badRequest("Selecione um convênio ativo.");
        if(scalar("SELECT count(*) FROM exam_catalog WHERE clinic_id=? AND id=?",clinic,body.examId())==0)
            return badRequest("Exame não encontrado nesta clínica.");
        jdbc.update("""
            INSERT INTO insurance_exam_prices(id,clinic_id,insurance_id,exam_id,amount,active,created_by_membership_id,updated_by_membership_id)
            VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT (clinic_id,insurance_id,exam_id)
            DO UPDATE SET amount=EXCLUDED.amount,active=EXCLUDED.active,updated_by_membership_id=EXCLUDED.updated_by_membership_id,updated_at=now()
        """,UUID.randomUUID(),clinic,body.insuranceId(),body.examId(),body.amount().setScale(2,RoundingMode.HALF_UP),
            body.active()==null?true:body.active(),a.membershipId(),a.membershipId());
        return ResponseEntity.ok(Map.of("message","Valor do exame salvo para o convênio selecionado."));
    }

    @GetMapping("/service-notes")
    public ResponseEntity<?> serviceNotes(Authentication auth) {
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT n.id,n.note_number,n.service_date,n.total_amount,n.status,n.created_at,
                   p.full_name AS patient_name,i.name AS insurance_name
              FROM service_notes n
              JOIN patients p ON p.clinic_id=n.clinic_id AND p.id=n.patient_id
              JOIN clinic_insurances i ON i.clinic_id=n.clinic_id AND i.id=n.insurance_id
             WHERE n.clinic_id=? ORDER BY n.created_at DESC LIMIT 100
        """,clinic));
    }

    @PostMapping("/service-notes")
    @Transactional
    public ResponseEntity<?> createServiceNote(Authentication auth,@Valid @RequestBody ServiceNoteRequest body) {
        Access a=access(auth);
        if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        if(scalar("SELECT count(*) FROM patients WHERE clinic_id=? AND id=? AND status='ACTIVE'",clinic,body.patientId())==0)
            return badRequest("Paciente não encontrado ou inativo.");
        if(scalar("SELECT count(*) FROM clinic_insurances WHERE clinic_id=? AND id=? AND status='ACTIVE'",clinic,body.insuranceId())==0)
            return badRequest("Convênio não encontrado ou inativo.");
        if(body.items()==null||body.items().isEmpty()||body.items().size()>50)
            return badRequest("Adicione ao menos um exame à nota de serviço.");
        UUID noteId=UUID.randomUUID();
        String number="NS-"+body.serviceDate().getYear()+"-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        BigDecimal total=BigDecimal.ZERO;
        List<Map<String,Object>> selected=new java.util.ArrayList<>();
        for(ServiceNoteItemRequest item:body.items()){
            if(item.quantity()==null||item.quantity()<1||item.quantity()>100)
                return badRequest("A quantidade de cada exame deve ficar entre 1 e 100.");
            List<Map<String,Object>> price=jdbc.queryForList("""
                SELECT e.name,p.amount FROM insurance_exam_prices p
                  JOIN exam_catalog e ON e.clinic_id=p.clinic_id AND e.id=p.exam_id
                 WHERE p.clinic_id=? AND p.insurance_id=? AND p.exam_id=? AND p.active=true
            """,clinic,body.insuranceId(),item.examId());
            if(price.isEmpty())return badRequest("Um dos exames não possui preço cadastrado para o convênio selecionado.");
            BigDecimal unit=(BigDecimal)price.getFirst().get("amount");
            BigDecimal line=unit.multiply(BigDecimal.valueOf(item.quantity())).setScale(2,RoundingMode.HALF_UP);
            total=total.add(line);
            selected.add(Map.of("examId",item.examId(),"name",price.getFirst().get("name"),"quantity",item.quantity(),"unit",unit,"line",line));
        }
        jdbc.update("""
            INSERT INTO service_notes(id,clinic_id,note_number,patient_id,insurance_id,service_date,total_amount,notes,created_by_membership_id)
            VALUES(?,?,?,?,?,?,?,?,?)
        """,noteId,clinic,number,body.patientId(),body.insuranceId(),body.serviceDate(),total.setScale(2,RoundingMode.HALF_UP),
            clean(body.notes()),a.membershipId());
        for(Map<String,Object> item:selected){
            jdbc.update("""
                INSERT INTO service_note_items(clinic_id,service_note_id,exam_id,exam_name_snapshot,quantity,unit_price,total_amount)
                VALUES(?,?,?,?,?,?,?)
            """,clinic,noteId,item.get("examId"),item.get("name"),item.get("quantity"),item.get("unit"),item.get("line"));
        }
        jdbc.update("INSERT INTO service_note_events(clinic_id,service_note_id,event_type,detail,actor_membership_id) VALUES(?,?,'CREATED','Nota de serviço criada',?)",
            clinic,noteId,a.membershipId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",noteId,"noteNumber",number,"total",total,"status","DRAFT"));
    }

    @GetMapping("/service-notes/{id}")
    public ResponseEntity<?> serviceNoteDetail(Authentication auth,@PathVariable UUID id) {
        if(access(auth)==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> notes=jdbc.queryForList("""
            SELECT n.*,p.full_name AS patient_name,i.name AS insurance_name
              FROM service_notes n JOIN patients p ON p.clinic_id=n.clinic_id AND p.id=n.patient_id
              JOIN clinic_insurances i ON i.clinic_id=n.clinic_id AND i.id=n.insurance_id
             WHERE n.clinic_id=? AND n.id=?
        """,clinic,id);
        if(notes.isEmpty())return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("note",notes.getFirst(),"items",jdbc.queryForList("""
            SELECT exam_name_snapshot,quantity,unit_price,total_amount FROM service_note_items
             WHERE clinic_id=? AND service_note_id=? ORDER BY created_at
        """,clinic,id)));
    }

    @PostMapping("/service-notes/{id}/sign")
    @Transactional
    public ResponseEntity<?> signServiceNote(Authentication auth,@PathVariable UUID id,@Valid @RequestBody ServiceNoteSignRequest body) {
        Access a=access(auth);
        if(a==null)return forbidden();
        UUID clinic=TenantContext.requireClinicId();
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT status FROM service_notes WHERE clinic_id=? AND id=? FOR UPDATE",clinic,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        if(!"DRAFT".equals(String.valueOf(rows.getFirst().get("status"))))
            return badRequest("Somente notas ainda não assinadas podem receber assinatura.");
        jdbc.update("""
            UPDATE service_notes SET status='SIGNED',signer_name=?,signature_text=?,signed_at=now(),updated_at=now()
             WHERE clinic_id=? AND id=? AND status='DRAFT'
        """,body.signerName().trim(),body.signatureText().trim(),clinic,id);
        jdbc.update("INSERT INTO service_note_events(clinic_id,service_note_id,event_type,detail,actor_membership_id) VALUES(?,?,'SIGNED','Nota de serviço assinada',?)",
            clinic,id,a.membershipId());
        return ResponseEntity.ok(Map.of("id",id,"status","SIGNED","message","Nota de serviço assinada."));
    }

    private int scalar(String sql,Object...args) {
        Integer value=jdbc.queryForObject(sql,Integer.class,args);
        return value==null?0:value;
    }
    private boolean validReferences(UUID clinic,UUID category,String direction,UUID patient,UUID appointment) {
        Integer categoryCount=jdbc.queryForObject("""
            SELECT count(*) FROM finance_categories WHERE clinic_id=? AND id=? AND active=true
              AND (direction=? OR direction='BOTH')
            """,Integer.class,clinic,category,direction);
        if(categoryCount==null || categoryCount==0)return false;
        if(patient!=null && jdbc.queryForObject("SELECT count(*) FROM patients WHERE clinic_id=? AND id=? AND status='ACTIVE'",
            Integer.class,clinic,patient)==0)return false;
        if(appointment!=null) {
            Integer appointmentCount=jdbc.queryForObject("""
                SELECT count(*) FROM appointments WHERE clinic_id=? AND id=?
                  AND (?::uuid IS NULL OR patient_id=?)
                """,Integer.class,clinic,appointment,patient,patient);
            if(appointmentCount==null || appointmentCount==0)return false;
        }
        return true;
    }

    private Access access(Authentication auth) {
        if(auth==null || !auth.isAuthenticated())return null;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.id,m.role FROM clinic_memberships m
              JOIN app_users u ON u.id=m.user_id
             WHERE lower(u.email)=lower(?) AND m.clinic_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
            """,auth.getName(),TenantContext.requireClinicId());
        if(rows.isEmpty())return null;
        String role=String.valueOf(rows.getFirst().get("role"));
        if(!List.of("CLINIC_ADMIN","RECEPTION").contains(role))return null;
        return new Access((UUID)rows.getFirst().get("id"),role);
    }

    private void recordEvent(UUID clinic,UUID entry,String type,String detail,UUID actor) {
        jdbc.update("INSERT INTO finance_events(clinic_id,entry_id,event_type,detail,actor_membership_id) VALUES(?,?,?,?,?)",
            clinic,entry,type,detail,actor);
    }
    private boolean validPeriod(LocalDate from,LocalDate to) {
        return from!=null && to!=null && !to.isBefore(from) && to.toEpochDay()-from.toEpochDay()<=366;
    }
    private String clean(String value) { if(value==null)return null;String v=value.trim();return v.isEmpty()?null:v; }
    private BigDecimal money(Object value) { return value==null?BigDecimal.ZERO:((BigDecimal)value).setScale(2,RoundingMode.HALF_UP); }
    private Object[] append(Object[] values,Object... tail) {
        Object[] result=java.util.Arrays.copyOf(values,values.length+tail.length);
        System.arraycopy(tail,0,result,values.length,tail.length);return result;
    }
    private ResponseEntity<Map<String,String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","Seu perfil não possui acesso ao Financeiro."));
    }
    private ResponseEntity<Map<String,String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error",message));
    }
    private ResponseEntity<Map<String,String>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",message));
    }

    public record InsuranceExamPriceRequest(@NotNull UUID insuranceId,@NotNull UUID examId,
        @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal amount,Boolean active) {}
    public record ServiceNoteItemRequest(@NotNull UUID examId,@NotNull Integer quantity) {}
    public record ServiceNoteRequest(@NotNull UUID patientId,@NotNull UUID insuranceId,@NotNull LocalDate serviceDate,
        @Size(max=500) String notes,@NotNull List<ServiceNoteItemRequest> items) {}
    public record ServiceNoteSignRequest(@NotBlank @Size(max=180) String signerName,
        @NotBlank @Size(max=240) String signatureText) {}
    public record CategoryRequest(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=12) String direction) {}
    public record ActiveRequest(@NotNull Boolean active) {}
    public record EntryRequest(
        @NotBlank @Size(max=180) String description,
        @NotBlank @Size(max=12) String direction,
        @NotNull UUID categoryId,
        UUID patientId, UUID appointmentId,
        @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal amount,
        @NotNull LocalDate dueDate,
        @Size(max=500) String notes
    ) {}
    public record PaymentRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal amount,
        @NotBlank @Size(max=16) String paymentMethod,
        OffsetDateTime paidAt,
        @Size(max=120) String reference,
        @Size(max=300) String notes
    ) {}
    public record CancelRequest(@NotBlank @Size(max=240) String reason) {}
    private record Access(UUID membershipId,String role) {}
}
