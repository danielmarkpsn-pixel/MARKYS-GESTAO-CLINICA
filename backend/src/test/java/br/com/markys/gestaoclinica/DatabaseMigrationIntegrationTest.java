package br.com.markys.gestaoclinica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "MARKYS_MASTER_BOOTSTRAP_TOKEN=integration-test-bootstrap-secret",
    "MARKYS_PII_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "MARKYS_PII_FINGERPRINT_KEY=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
    "MARKYS_PRIVATE_STORAGE_PATH=/tmp/markys-test-private"
})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "markys.db.integration", matches = "true")
class DatabaseMigrationIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void allClinicAuthenticationAndMasterMigrationsApplyOnPostgresql() {
        Integer migrationCount = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        Integer authColumns = jdbc.queryForObject("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema = current_schema() AND table_name = 'app_users'
               AND column_name IN ('failed_login_attempts', 'locked_until', 'last_login_at', 'password_changed_at')
            """, Integer.class);
        Integer clinicalTables = jdbc.queryForObject("""
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema = current_schema()
               AND table_name IN ('patients', 'queue_entries', 'triage_records', 'encounters', 'clinical_notes', 'service_types', 'exam_catalog', 'queue_entry_exams', 'patient_documents', 'appointments', 'professional_schedule_rules', 'schedule_blocks', 'appointment_events', 'finance_categories', 'finance_entries', 'finance_payments', 'finance_events', 'exam_orders', 'exam_order_events', 'clinic_room_events', 'clinic_insurances', 'clinic_insurance_events', 'clinic_membership_events', 'lab_specimens', 'lab_specimen_events', 'esocial_employers', 'esocial_workers', 'esocial_events', 'esocial_event_audit')
            """, Integer.class);
        Integer masterTables = jdbc.queryForObject("""
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema = current_schema() AND table_name = 'platform_admins'
            """, Integer.class);

        assertThat(migrationCount).isEqualTo(18);
        assertThat(authColumns).isEqualTo(4);
        assertThat(clinicalTables).isEqualTo(29);
        assertThat(masterTables).isEqualTo(1);
    }

    @Test
    void postgresRowLevelSecurityHidesOtherClinicPatientsAndRejectsCrossTenantWrites() {
        UUID clinicA = UUID.randomUUID();
        UUID clinicB = UUID.randomUUID();
        UUID patientA = UUID.randomUUID();
        UUID patientB = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // The postgres container's bootstrap user is a superuser and bypasses RLS.
        // Exercise the policies as a dedicated non-owner, non-superuser runtime role.
        jdbc.execute("CREATE ROLE markys_rls_test NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT SELECT, INSERT ON patients TO markys_rls_test");
        Boolean bypassesRls = jdbc.queryForObject(
            "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = 'markys_rls_test'",
            Boolean.class);
        assertThat(bypassesRls).isFalse();

        int[] visibleCounts = tx.execute(status -> {
            jdbc.update("INSERT INTO clinics (id, name, status) VALUES (?, ?, 'ACTIVE')", clinicA, "RLS Clínica A");
            jdbc.update("INSERT INTO clinics (id, name, status) VALUES (?, ?, 'ACTIVE')", clinicB, "RLS Clínica B");

            jdbc.execute("SET LOCAL ROLE markys_rls_test");
            jdbc.queryForObject("SELECT set_config('app.current_clinic_id', ?, true)", String.class, clinicA.toString());
            jdbc.update("INSERT INTO patients (id, clinic_id, full_name) VALUES (?, ?, ?)", patientA, clinicA, "Paciente A");

            jdbc.queryForObject("SELECT set_config('app.current_clinic_id', ?, true)", String.class, clinicB.toString());
            jdbc.update("INSERT INTO patients (id, clinic_id, full_name) VALUES (?, ?, ?)", patientB, clinicB, "Paciente B");

            jdbc.queryForObject("SELECT set_config('app.current_clinic_id', ?, true)", String.class, clinicA.toString());
            Integer clinicAVisible = jdbc.queryForObject(
                "SELECT count(*) FROM patients WHERE id IN (?, ?)", Integer.class, patientA, patientB);

            jdbc.queryForObject("SELECT set_config('app.current_clinic_id', ?, true)", String.class, clinicB.toString());
            Integer clinicBVisible = jdbc.queryForObject(
                "SELECT count(*) FROM patients WHERE id IN (?, ?)", Integer.class, patientA, patientB);
            return new int[] { clinicAVisible, clinicBVisible };
        });

        assertThat(visibleCounts).containsExactly(1, 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            // Each transaction starts as the connection user again; re-enter the restricted role
            // so this assertion exercises RLS rather than the CI bootstrap superuser.
            jdbc.execute("SET LOCAL ROLE markys_rls_test");
            jdbc.queryForObject("SELECT set_config('app.current_clinic_id', ?, true)", String.class, clinicA.toString());
            jdbc.update("INSERT INTO patients (id, clinic_id, full_name) VALUES (?, ?, ?)",
                UUID.randomUUID(), clinicB, "Tentativa cross-tenant");
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void masterBootstrapCreatesGlobalAdminAndMasterCanProvisionClinic() throws Exception {
        // Test cleanup is intentionally explicit: TRUNCATE is not filtered by RLS,
        // unlike DELETE, and CASCADE clears tenant-owned rows before parent clinics.
        jdbc.execute("""
            TRUNCATE TABLE esocial_event_audit, esocial_events, esocial_workers, esocial_employers, clinic_membership_events, lab_specimen_events, lab_specimens, clinic_room_events, clinic_insurance_events, exam_order_events, exam_orders, finance_events, finance_payments, finance_entries, finance_categories, appointment_events, appointments, schedule_blocks, professional_schedule_rules, clinical_notes, encounters, triage_records, queue_entries,
                exam_catalog, service_types, clinic_rooms, clinic_insurances, professionals, patients, platform_admins,
                clinic_memberships, app_users, clinics CASCADE
            """);

        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isOk()).andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(
            csrfResult.getResponse().getContentAsString(), "$.token");
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(post("/api/v1/auth/master-bootstrap")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", token)
                .header("X-MARKYS-BOOTSTRAP-TOKEN", "integration-test-bootstrap-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "fullName": "Master MARKYS",
                      "email": "master@markys.test",
                      "password": "SenhaMasterSegura123!"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("master@markys.test"));

        String passwordHash = jdbc.queryForObject(
            "SELECT password_hash FROM app_users WHERE lower(email) = lower(?)",
            String.class, "master@markys.test");
        assertThat(passwordHash).startsWith("$2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_admins", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinic_memberships", Integer.class)).isZero();

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"master@markys.test","password":"SenhaMasterSegura123!"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.globalRole").value("MASTER"))
            .andExpect(jsonPath("$.memberships").isEmpty())
            .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(post("/api/v1/master/clinics")
                .session(session).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clinicName":"Clínica de Teste",
                      "adminName":"Administrador Local",
                      "adminEmail":"admin@clinica.test",
                      "adminPassword":"SenhaLocalSegura123!"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.adminEmail").value("admin@clinica.test"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinics", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT role FROM clinic_memberships m JOIN app_users u ON u.id=m.user_id WHERE lower(u.email)=lower(?)",
            String.class, "admin@clinica.test")).isEqualTo("CLINIC_ADMIN");

        MvcResult clinicAdminLogin = mockMvc.perform(post("/api/v1/auth/login")
                .cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"admin@clinica.test","password":"SenhaLocalSegura123!"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.globalRole").value("USER"))
            .andReturn();
        MockHttpSession clinicAdminSession = (MockHttpSession) clinicAdminLogin.getRequest().getSession(false);
        mockMvc.perform(get("/api/v1/master/clinics").session(clinicAdminSession))
            .andExpect(status().isForbidden());

        UUID clinicId = jdbc.queryForObject("SELECT id FROM clinics WHERE name=?", UUID.class, "Clínica de Teste");
        UUID serviceTypeId = jdbc.queryForObject(
            "SELECT id FROM service_types WHERE clinic_id=? AND name=?", UUID.class, clinicId, "Admissional");
        UUID examId = jdbc.queryForObject(
            "SELECT id FROM exam_catalog WHERE clinic_id=? AND name=?", UUID.class, clinicId, "Tórax PA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinic_rooms WHERE clinic_id=?", Integer.class, clinicId)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_types WHERE clinic_id=?", Integer.class, clinicId)).isEqualTo(9);

        MvcResult insuranceResult=mockMvc.perform(post("/api/v1/clinic/insurances")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Convênio Integração","ansCode":"123456","contactName":"Equipe Comercial",
                     "phone":"92999990000","email":"contato@convenio.test","billingEmail":"financeiro@convenio.test",
                     "paymentTermsDays":30,"notes":"Cadastro para teste de integração"}
                    """))
            .andExpect(status().isCreated()).andReturn();
        UUID insuranceId=UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            insuranceResult.getResponse().getContentAsString(),"$.id"));
        mockMvc.perform(get("/api/v1/clinic/insurances").param("search","Convênio Integração")
                .session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(put("/api/v1/clinic/insurances/{id}",insuranceId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Convênio Integração Atualizado","ansCode":"123456","contactName":"Equipe Comercial",
                     "phone":"92999990000","email":"contato@convenio.test","billingEmail":"financeiro@convenio.test",
                     "paymentTermsDays":45,"notes":"Cadastro atualizado"}
                    """))
            .andExpect(status().isOk());
        MvcResult patientResult = mockMvc.perform(post("/api/v1/clinic/patients")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "fullName":"Paciente de Integração",
                      "cpf":"529.982.247-25",
                      "birthDate":"1990-05-20",
                      "insuranceId":"%s",
                      "jobTitle":"Técnico",
                      "serviceTypeId":"%s",
                      "examIds":["%s"]
                    }
                    """.formatted(insuranceId, serviceTypeId, examId)))
            .andExpect(status().isCreated())
            .andReturn();
        UUID patientId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            patientResult.getResponse().getContentAsString(), "$.patientId"));
        assertThat(jdbc.queryForObject("SELECT document_ciphertext IS NOT NULL FROM patients WHERE id=?",
            Boolean.class, patientId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT insurance_id FROM patients WHERE id=?",UUID.class,patientId))
            .isEqualTo(insuranceId);
        assertThat(jdbc.queryForObject("SELECT insurance_name FROM patients WHERE id=?",String.class,patientId))
            .isEqualTo("Convênio Integração Atualizado");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM queue_entry_exams WHERE clinic_id=?",
            Integer.class, clinicId)).isEqualTo(1);

        mockMvc.perform(patch("/api/v1/clinic/insurances/{id}/status",insuranceId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        MockMultipartFile pdf = new MockMultipartFile("file", "ficha.pdf", "application/pdf",
            "%PDF-1.4\nMARKYS TEST\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MvcResult documentResult = mockMvc.perform(multipart("/api/v1/clinic/patients/{id}/documents", patientId)
                .file(pdf).session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token))
            .andExpect(status().isCreated()).andReturn();
        UUID documentId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            documentResult.getResponse().getContentAsString(), "$.id"));
        mockMvc.perform(get("/api/v1/clinic/patients/{id}/documents/{documentId}", patientId, documentId)
                .session(clinicAdminSession))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentType(MediaType.APPLICATION_PDF));

        mockMvc.perform(put("/api/v1/clinic/patients/{id}", patientId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "fullName":"Paciente Atualizado",
                      "cpf":"529.982.247-25",
                      "birthDate":"1990-05-20",
                      "insuranceId":"%s",
                      "jobTitle":"Técnico Sênior",
                      "serviceTypeId":"%s",
                      "examIds":[]
                    }
                    """.formatted(insuranceId, serviceTypeId)))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT full_name FROM patients WHERE id=?", String.class, patientId))
            .isEqualTo("Paciente Atualizado");
        assertThat(jdbc.queryForObject("SELECT insurance_id FROM patients WHERE id=?",UUID.class,patientId))
            .isEqualTo(insuranceId);
        assertThat(jdbc.queryForObject("SELECT insurance_name FROM patients WHERE id=?",String.class,patientId))
            .isEqualTo("Convênio Integração Atualizado");
        mockMvc.perform(get("/api/v1/clinic/insurances/{id}",insuranceId).session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.patient_count").value(1))
            .andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(get("/api/v1/clinic/insurances/{id}/history",insuranceId).session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            jdbc.update("UPDATE clinic_insurance_events SET detail='alterado' WHERE insurance_id=?",insuranceId))
            .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM queue_entry_exams WHERE clinic_id=?",
            Integer.class, clinicId)).isZero();

        UUID professionalUserId = UUID.randomUUID();
        UUID professionalMembershipId = UUID.randomUUID();
        UUID professionalId = UUID.randomUUID();
        UUID roomId = jdbc.queryForObject("SELECT id FROM clinic_rooms WHERE clinic_id=? ORDER BY name LIMIT 1",
            UUID.class, clinicId);
        jdbc.update("INSERT INTO app_users (id, full_name, email, password_hash, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
            professionalUserId, "Profissional Agenda", "profissional.agenda@clinica.test", "$2a$10$abcdefghijklmnopqrstuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu");
        jdbc.update("INSERT INTO clinic_memberships (id, clinic_id, user_id, role, status) VALUES (?, ?, ?, 'PROFESSIONAL', 'ACTIVE')",
            professionalMembershipId, clinicId, professionalUserId);
        jdbc.update("INSERT INTO professionals (id, clinic_id, membership_id, display_name, specialty, status) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
            professionalId, clinicId, professionalMembershipId, "Profissional Agenda", "Clínica Geral");
        java.time.LocalDate appointmentDay = java.time.LocalDate.now(java.time.ZoneId.of("America/Manaus")).plusDays(1);
        mockMvc.perform(post("/api/v1/clinic/schedule/rules")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"professionalId":"%s","weekday":%d,"startsLocal":"08:00:00","endsLocal":"18:00:00","slotMinutes":30}
                    """.formatted(professionalId, appointmentDay.getDayOfWeek().getValue())))
            .andExpect(status().isCreated());
        UUID secondPatientId = UUID.randomUUID();
        jdbc.update("INSERT INTO patients (id, clinic_id, full_name) VALUES (?, ?, ?)",
            secondPatientId, clinicId, "Segundo Paciente Agenda");
        String appointmentPayload = """
            {"patientId":"%s","professionalId":"%s","roomId":"%s",
             "startsAt":"%sT09:00:00","endsAt":"%sT09:30:00","reason":"Consulta de integração"}
            """.formatted(patientId, professionalId, roomId, appointmentDay, appointmentDay);
        MvcResult appointmentResult = mockMvc.perform(post("/api/v1/clinic/schedule")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content(appointmentPayload))
            .andExpect(status().isCreated()).andReturn();
        UUID appointmentId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            appointmentResult.getResponse().getContentAsString(), "$.id"));
        mockMvc.perform(post("/api/v1/clinic/schedule")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content(appointmentPayload.replace(patientId.toString(),secondPatientId.toString())))
            .andExpect(status().isConflict());

        UUID secondProfessionalUser = UUID.randomUUID();
        UUID secondProfessionalMembership = UUID.randomUUID();
        UUID secondProfessionalId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, full_name, email, password_hash, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
            secondProfessionalUser, "Segundo Profissional Agenda", "profissional.agenda2@clinica.test", "$2a$10$abcdefghijklmnopqrstuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu");
        jdbc.update("INSERT INTO clinic_memberships (id, clinic_id, user_id, role, status) VALUES (?, ?, ?, 'PROFESSIONAL', 'ACTIVE')",
            secondProfessionalMembership, clinicId, secondProfessionalUser);
        jdbc.update("INSERT INTO professionals (id, clinic_id, membership_id, display_name, specialty, status) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
            secondProfessionalId, clinicId, secondProfessionalMembership, "Segundo Profissional", "Clínica Geral");
        mockMvc.perform(post("/api/v1/clinic/schedule/rules")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"professionalId":"%s","weekday":%d,"startsLocal":"08:00:00","endsLocal":"18:00:00","slotMinutes":30}
                    """.formatted(secondProfessionalId, appointmentDay.getDayOfWeek().getValue())))
            .andExpect(status().isCreated());
        String patientConflictPayload = """
            {"patientId":"%s","professionalId":"%s","roomId":null,
             "startsAt":"%sT09:00:00","endsAt":"%sT09:30:00","reason":"Consulta duplicada"}
            """.formatted(patientId, secondProfessionalId, appointmentDay, appointmentDay);
        mockMvc.perform(post("/api/v1/clinic/schedule")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content(patientConflictPayload))
            .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/v1/clinic/schedule/{id}/status", appointmentId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONFIRMED\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id=?", String.class, appointmentId))
            .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointment_events WHERE appointment_id=?",
            Integer.class, appointmentId)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            jdbc.update("UPDATE appointment_events SET detail='alterado' WHERE appointment_id=?", appointmentId))
            .isInstanceOf(DataAccessException.class);

        UUID incomeCategoryId = jdbc.queryForObject("""
            SELECT id FROM finance_categories WHERE clinic_id=? AND name='Consultas particulares' AND direction='INCOME'
            """, UUID.class, clinicId);
        String financeEntryPayload = """
            {"direction":"INCOME","description":"Consulta de integração","categoryId":"%s",
             "patientId":"%s","appointmentId":"%s","amount":250.00,"dueDate":"%s","notes":"Teste financeiro"}
            """.formatted(incomeCategoryId, patientId, appointmentId, appointmentDay);
        MvcResult financeEntryResult = mockMvc.perform(post("/api/v1/clinic/finance/entries")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content(financeEntryPayload))
            .andExpect(status().isCreated()).andReturn();
        UUID financeEntryId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            financeEntryResult.getResponse().getContentAsString(), "$.id"));
        mockMvc.perform(post("/api/v1/clinic/finance/entries/{id}/payments", financeEntryId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":300.00,\"paymentMethod\":\"PIX\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/clinic/finance/entries/{id}/payments", financeEntryId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100.00,\"paymentMethod\":\"PIX\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PARTIAL"))
            .andExpect(jsonPath("$.amountRemaining").value(150.00));
        mockMvc.perform(post("/api/v1/clinic/finance/entries/{id}/payments", financeEntryId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":150.00,\"paymentMethod\":\"CREDIT_CARD\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(post("/api/v1/clinic/finance/entries/{id}/payments", financeEntryId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1.00,\"paymentMethod\":\"PIX\"}"))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM finance_entries WHERE id=?",String.class,financeEntryId))
            .isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finance_payments WHERE entry_id=?",Integer.class,financeEntryId))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finance_events WHERE entry_id=?",Integer.class,financeEntryId))
            .isEqualTo(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            jdbc.update("DELETE FROM finance_payments WHERE entry_id=?",financeEntryId))
            .isInstanceOf(DataAccessException.class);
        mockMvc.perform(get("/api/v1/clinic/finance/entries")
                .param("from",appointmentDay.toString()).param("to",appointmentDay.toString())
                .session(clinicAdminSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].status").value("PAID"));
        mockMvc.perform(get("/api/v1/clinic/finance/summary")
                .param("from",java.time.LocalDate.now(java.time.ZoneId.of("America/Manaus")).toString()).param("to",appointmentDay.toString())
                .session(clinicAdminSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.incomeReceived").value(250.00));

        String examOrderPayload = """
            {"patientId":"%s","examId":"%s","appointmentId":"%s","professionalId":"%s",
             "priority":"URGENT","notes":"Solicitação clínica de integração"}
            """.formatted(patientId,examId,appointmentId,professionalId);
        MvcResult examOrderResult = mockMvc.perform(post("/api/v1/clinic/exams")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content(examOrderPayload))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED")).andReturn();
        UUID examOrderId=UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            examOrderResult.getResponse().getContentAsString(),"$.id"));
        assertThat(jdbc.queryForObject("SELECT notes_ciphertext IS NOT NULL FROM exam_orders WHERE id=?",
            Boolean.class,examOrderId)).isTrue();
        mockMvc.perform(patch("/api/v1/clinic/exams/{id}/status",examOrderId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/clinic/exams/{id}/status",examOrderId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\",\"result\":\"Exame sem alterações relevantes.\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(jdbc.queryForObject("SELECT status FROM exam_orders WHERE id=?",String.class,examOrderId))
            .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT result_ciphertext IS NOT NULL FROM exam_orders WHERE id=?",
            Boolean.class,examOrderId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_order_events WHERE exam_order_id=?",
            Integer.class,examOrderId)).isEqualTo(3);
        mockMvc.perform(get("/api/v1/clinic/exams/{id}",examOrderId).session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("Exame sem alterações relevantes."));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            jdbc.update("UPDATE exam_order_events SET detail='alterado' WHERE exam_order_id=?",examOrderId))
            .isInstanceOf(DataAccessException.class);
        mockMvc.perform(get("/api/v1/clinic/exams").param("status","COMPLETED")
                .session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].exam_name").value("Tórax PA"));

        MvcResult roomResult=mockMvc.perform(post("/api/v1/clinic/rooms")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Sala de Integração","roomType":"CONSULTORIO","floorLabel":"1º andar",
                     "capacity":2,"description":"Sala de teste"}
                    """))
            .andExpect(status().isCreated()).andReturn();
        UUID managedRoomId=UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            roomResult.getResponse().getContentAsString(),"$.id"));
        mockMvc.perform(get("/api/v1/clinic/rooms").param("search","Sala de Integração")
                .session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/v1/clinic/rooms/{id}",managedRoomId).session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.room.name").value("Sala de Integração"));
        mockMvc.perform(put("/api/v1/clinic/rooms/{id}",managedRoomId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Sala de Integração Atualizada","roomType":"MULTIUSO","floorLabel":"2º andar",
                     "capacity":3,"description":"Sala revisada"}
                    """))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/clinic/rooms/{id}/status",managedRoomId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/clinic/rooms/{id}/status",managedRoomId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/clinic/rooms/{id}/history",managedRoomId).session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinic_room_events WHERE room_id=?",
            Integer.class,managedRoomId)).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            jdbc.update("UPDATE clinic_room_events SET detail='alterado' WHERE room_id=?",managedRoomId))
            .isInstanceOf(DataAccessException.class);

        MvcResult labCatalogResult=mockMvc.perform(post("/api/v1/clinic/catalog/exams")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"category":"LABORATORIO","name":"Hemograma Completo","roomId":"%s"}
                    """.formatted(managedRoomId)))
            .andExpect(status().isCreated()).andReturn();
        UUID labExamId=UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            labCatalogResult.getResponse().getContentAsString(),"$.id"));
        String missingSpecimenPayload="""
            {"patientId":"%s","examId":"%s","appointmentId":"%s","professionalId":"%s","priority":"NORMAL"}
            """.formatted(patientId,labExamId,appointmentId,professionalId);
        mockMvc.perform(post("/api/v1/clinic/exams")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content(missingSpecimenPayload))
            .andExpect(status().isBadRequest());
        String labOrderPayload="""
            {"patientId":"%s","examId":"%s","appointmentId":"%s","professionalId":"%s",
             "priority":"URGENT","specimenType":"SANGUE","notes":"Coleta para hemograma"}
            """.formatted(patientId,labExamId,appointmentId,professionalId);
        MvcResult labOrderResult=mockMvc.perform(post("/api/v1/clinic/exams")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content(labOrderPayload))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED")).andReturn();
        UUID labOrderId=UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            labOrderResult.getResponse().getContentAsString(),"$.id"));
        UUID specimenId=jdbc.queryForObject("SELECT id FROM lab_specimens WHERE clinic_id=? AND exam_order_id=?",
            UUID.class,clinicId,labOrderId);
        assertThat(jdbc.queryForObject("SELECT collection_status FROM lab_specimens WHERE id=?",String.class,specimenId))
            .isEqualTo("PENDING_COLLECTION");
        mockMvc.perform(patch("/api/v1/clinic/exams/{id}/status",labOrderId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
            .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/clinic/laboratory").param("search","Hemograma")
                .session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(patch("/api/v1/clinic/laboratory/{id}/status",specimenId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COLLECTED\",\"labReference\":\"LAB-TEST-001\",\"collectionNotes\":\"Coleta realizada\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/clinic/laboratory/{id}/status",specimenId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RECEIVED\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/clinic/laboratory/{id}/status",specimenId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PROCESSING\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM exam_orders WHERE id=?",String.class,labOrderId))
            .isEqualTo("IN_PROGRESS");
        mockMvc.perform(patch("/api/v1/clinic/laboratory/{id}/status",specimenId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RESULT_READY\",\"result\":\"Hemoglobina 14,2 g/dL; demais parâmetros dentro da referência.\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT result_ciphertext IS NOT NULL FROM exam_orders WHERE id=?",
            Boolean.class,labOrderId)).isFalse();
        mockMvc.perform(get("/api/v1/clinic/laboratory/{id}",specimenId).session(clinicAdminSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exam_notes").value("Coleta para hemograma"))
            .andExpect(jsonPath("$.collection_notes").value("Coleta realizada"))
            .andExpect(jsonPath("$.result").value("Hemoglobina 14,2 g/dL; demais parâmetros dentro da referência."));
        mockMvc.perform(patch("/api/v1/clinic/laboratory/{id}/status",specimenId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RELEASED\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM exam_orders WHERE id=?",String.class,labOrderId))
            .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT collection_status FROM lab_specimens WHERE id=?",String.class,specimenId))
            .isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT result_ciphertext FROM exam_orders WHERE id=?",byte[].class,labOrderId))
            .isEqualTo(jdbc.queryForObject("SELECT result_ciphertext FROM lab_specimens WHERE id=?",byte[].class,specimenId));
        mockMvc.perform(get("/api/v1/clinic/exams/{id}",labOrderId).session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("Hemoglobina 14,2 g/dL; demais parâmetros dentro da referência."));
        mockMvc.perform(get("/api/v1/clinic/laboratory/{id}/history",specimenId).session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lab_specimen_events WHERE specimen_id=?",
            Integer.class,specimenId)).isEqualTo(6);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            jdbc.update("UPDATE lab_specimen_events SET detail='alterado' WHERE specimen_id=?",specimenId))
            .isInstanceOf(DataAccessException.class);

        UUID adminMembershipId=jdbc.queryForObject("""
            SELECT id FROM clinic_memberships WHERE clinic_id=?
              AND user_id=(SELECT id FROM app_users WHERE lower(email)=lower(?))
            """,UUID.class,clinicId,"admin@clinica.test");
        mockMvc.perform(patch("/api/v1/clinic/access/{id}/status",adminMembershipId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
            .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/v1/clinic/access/{id}/role",adminMembershipId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"RECEPTION\"}"))
            .andExpect(status().isConflict());

        MvcResult accessResult=mockMvc.perform(post("/api/v1/clinic/access")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Usuário de Acesso","email":"recepcao.acesso@clinica.test",
                     "role":"RECEPTION","initialPassword":"SenhaInicialForte#2026"}
                    """))
            .andExpect(status().isCreated()).andReturn();
        UUID membershipId=UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            accessResult.getResponse().getContentAsString(),"$.membershipId"));
        mockMvc.perform(get("/api/v1/clinic/access").param("search","recepcao.acesso")
                .session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(post("/api/v1/clinic/access")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Usuário Duplicado","email":"recepcao.acesso@clinica.test",
                     "role":"RECEPTION","initialPassword":"SenhaInicialForte#2026"}
                    """))
            .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/v1/clinic/access/{id}/role",membershipId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TRIAGE\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/clinic/access/{id}/role",membershipId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"RECEPTION\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/clinic/access/{id}/status",membershipId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/clinic/access/{id}/status",membershipId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
            .andExpect(status().isOk());
        String previousPasswordHash=jdbc.queryForObject("""
            SELECT u.password_hash FROM app_users u JOIN clinic_memberships m ON m.user_id=u.id
             WHERE m.clinic_id=? AND m.id=?
            """,String.class,clinicId,membershipId);
        mockMvc.perform(patch("/api/v1/clinic/access/{id}/password",membershipId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"NovaSenhaSegura#2026\"}"))
            .andExpect(status().isOk());
        String updatedPasswordHash=jdbc.queryForObject("""
            SELECT u.password_hash FROM app_users u JOIN clinic_memberships m ON m.user_id=u.id
             WHERE m.clinic_id=? AND m.id=?
            """,String.class,clinicId,membershipId);
        assertThat(updatedPasswordHash).isNotEqualTo(previousPasswordHash);
        jdbc.update("""
            UPDATE app_users SET failed_login_attempts=5,locked_until=now()+interval '20 minutes'
             WHERE id=(SELECT user_id FROM clinic_memberships WHERE clinic_id=? AND id=?)
            """,clinicId,membershipId);
        mockMvc.perform(post("/api/v1/clinic/access/{id}/unlock",membershipId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
            SELECT failed_login_attempts FROM app_users u JOIN clinic_memberships m ON m.user_id=u.id
             WHERE m.clinic_id=? AND m.id=?
            """,Integer.class,clinicId,membershipId)).isZero();
        mockMvc.perform(get("/api/v1/clinic/access/{id}/history",membershipId).session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinic_membership_events WHERE membership_id=?",
            Integer.class,membershipId)).isEqualTo(7);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            jdbc.update("UPDATE clinic_membership_events SET detail='alterado' WHERE membership_id=?",membershipId))
            .isInstanceOf(DataAccessException.class);

        mockMvc.perform(put("/api/v1/clinic/esocial/employer")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"registrationType":"CNPJ","registrationNumber":"11222333000181","legalName":"Empresa Teste Ltda",
                     "classificationCode":"8610101","environment":"PRODUCTION_RESTRICTED","contactName":"Responsável",
                     "contactEmail":"sst@empresa.test","contactPhone":"92999990000"}
                    """))
            .andExpect(status().isOk());
        UUID workerId;
        MvcResult workerResult=mockMvc.perform(post("/api/v1/clinic/esocial/workers")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"patientId":"%s","cpf":"52998224725","fullName":"Trabalhador Teste","birthDate":"1990-01-15",
                     "sex":"M","registrationCode":"MAT-0001","admissionDate":"2020-02-01","categoryCode":"101",
                     "cboCode":"225125","jobTitle":"Médico","department":"Clínica","workLocation":"Unidade Central"}
                    """.formatted(patientId)))
            .andExpect(status().isCreated()).andReturn();
        workerId=UUID.fromString(com.jayway.jsonpath.JsonPath.read(workerResult.getResponse().getContentAsString(),"$.id"));
        MvcResult esocialEvent=mockMvc.perform(post("/api/v1/clinic/esocial/events")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"workerId":"%s","eventType":"S-2220","eventDate":"%s","eventSequence":1,
                     "sourceExamOrderId":"%s","payload":{"examDate":"%s","examType":"ASO_ADMISSIONAL",
                     "doctorName":"Dra. Teste","doctorCouncil":"CRM","doctorNumber":"12345","result":"APTO"}}
                    """.formatted(workerId,java.time.LocalDate.now(java.time.ZoneId.of("America/Manaus")),labOrderId,java.time.LocalDate.now(java.time.ZoneId.of("America/Manaus")))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn();
        UUID esocialEventId=UUID.fromString(com.jayway.jsonpath.JsonPath.read(esocialEvent.getResponse().getContentAsString(),"$.id"));
        mockMvc.perform(get("/api/v1/clinic/esocial/events/{id}",esocialEventId).session(clinicAdminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.payload.doctorName").value("Dra. Teste"));
        mockMvc.perform(put("/api/v1/clinic/esocial/events/{id}",esocialEventId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"workerId":"%s","eventType":"S-2220","eventDate":"%s","eventSequence":1,
                     "sourceExamOrderId":"%s","payload":{"examDate":"%s","examType":"ASO_ADMISSIONAL",
                     "doctorName":"Dra. Atualizada","doctorCouncil":"CRM","doctorNumber":"12345","result":"APTO"}}
                    """.formatted(workerId,java.time.LocalDate.now(java.time.ZoneId.of("America/Manaus")),labOrderId,
                        java.time.LocalDate.now(java.time.ZoneId.of("America/Manaus")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(post("/api/v1/clinic/esocial/events/{id}/validate",esocialEventId)
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(jdbc.queryForObject("SELECT status FROM esocial_events WHERE id=?",String.class,esocialEventId)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM esocial_event_audit WHERE event_id=?",Integer.class,esocialEventId))
            .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT payload_ciphertext IS NOT NULL FROM esocial_events WHERE id=?",Boolean.class,esocialEventId))
            .isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            jdbc.update("UPDATE esocial_event_audit SET detail='alterado' WHERE event_id=?",esocialEventId))
            .isInstanceOf(DataAccessException.class);

        jdbc.update("""
            UPDATE clinic_memberships SET role='RECEPTION'
             WHERE clinic_id=? AND user_id=(SELECT id FROM app_users WHERE lower(email)=lower(?))
            """,clinicId,"admin@clinica.test");
        UUID expenseCategoryId=jdbc.queryForObject("""
            SELECT id FROM finance_categories WHERE clinic_id=? AND name='Aluguel e condomínio' AND direction='EXPENSE'
            """,UUID.class,clinicId);
        mockMvc.perform(post("/api/v1/clinic/finance/entries")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"direction":"EXPENSE","description":"Aluguel","categoryId":"%s","amount":1000.00,"dueDate":"%s"}
                    """.formatted(expenseCategoryId,appointmentDay)))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/master-bootstrap")
                .cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .header("X-MARKYS-BOOTSTRAP-TOKEN", "integration-test-bootstrap-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Outro Master","email":"other@markys.test","password":"OutraSenhaSegura123!"}
                    """))
            .andExpect(status().isConflict());
    }

    @Test
    void publicClinicSetupIsDisabled() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(
            csrfResult.getResponse().getContentAsString(), "$.token");
        mockMvc.perform(post("/api/v1/auth/setup")
                .cookie(csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"clinicName":"Não permitida","fullName":"Teste","email":"x@markys.test","password":"SenhaSegura123!"}
                    """))
            .andExpect(status().isForbidden());
    }
}
