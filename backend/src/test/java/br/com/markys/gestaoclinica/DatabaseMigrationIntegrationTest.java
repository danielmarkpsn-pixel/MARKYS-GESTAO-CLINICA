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
               AND table_name IN ('patients', 'queue_entries', 'triage_records', 'encounters', 'clinical_notes', 'service_types', 'exam_catalog', 'queue_entry_exams', 'patient_documents')
            """, Integer.class);
        Integer masterTables = jdbc.queryForObject("""
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema = current_schema() AND table_name = 'platform_admins'
            """, Integer.class);

        assertThat(migrationCount).isEqualTo(8);
        assertThat(authColumns).isEqualTo(4);
        assertThat(clinicalTables).isEqualTo(9);
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
            TRUNCATE TABLE clinical_notes, encounters, triage_records, queue_entries,
                exam_catalog, service_types, clinic_rooms, professionals, patients, platform_admins,
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

        MvcResult patientResult = mockMvc.perform(post("/api/v1/clinic/patients")
                .session(clinicAdminSession).cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "fullName":"Paciente de Integração",
                      "cpf":"529.982.247-25",
                      "birthDate":"1990-05-20",
                      "insuranceName":"Convênio Teste",
                      "jobTitle":"Técnico",
                      "serviceTypeId":"%s",
                      "examIds":["%s"]
                    }
                    """.formatted(serviceTypeId, examId)))
            .andExpect(status().isCreated())
            .andReturn();
        UUID patientId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
            patientResult.getResponse().getContentAsString(), "$.patientId"));
        assertThat(jdbc.queryForObject("SELECT document_ciphertext IS NOT NULL FROM patients WHERE id=?",
            Boolean.class, patientId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM queue_entry_exams WHERE clinic_id=?",
            Integer.class, clinicId)).isEqualTo(1);

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
                      "birthDate":"1990-05-20",
                      "insuranceName":"Convênio Atualizado",
                      "jobTitle":"Técnico Sênior",
                      "serviceTypeId":"%s",
                      "examIds":[]
                    }
                    """.formatted(serviceTypeId)))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT full_name FROM patients WHERE id=?", String.class, patientId))
            .isEqualTo("Paciente Atualizado");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM queue_entry_exams WHERE clinic_id=?",
            Integer.class, clinicId)).isZero();

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
