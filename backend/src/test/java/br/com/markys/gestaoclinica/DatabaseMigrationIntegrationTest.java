package br.com.markys.gestaoclinica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "MARKYS_MASTER_BOOTSTRAP_TOKEN=integration-test-bootstrap-secret")
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "markys.db.integration", matches = "true")
class DatabaseMigrationIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

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
               AND table_name IN ('patients', 'queue_entries', 'triage_records', 'encounters', 'clinical_notes')
            """, Integer.class);
        Integer masterTables = jdbc.queryForObject("""
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema = current_schema() AND table_name = 'platform_admins'
            """, Integer.class);

        assertThat(migrationCount).isEqualTo(4);
        assertThat(authColumns).isEqualTo(4);
        assertThat(clinicalTables).isEqualTo(5);
        assertThat(masterTables).isEqualTo(1);
    }

    @Test
    void masterBootstrapCreatesGlobalAdminAndMasterCanProvisionClinic() throws Exception {
        jdbc.update("DELETE FROM platform_admins");
        jdbc.update("DELETE FROM clinic_memberships");
        jdbc.update("DELETE FROM app_users");
        jdbc.update("DELETE FROM clinics");

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
