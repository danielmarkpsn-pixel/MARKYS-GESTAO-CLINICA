package br.com.markys.gestaoclinica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "markys.db.integration", matches = "true")
class DatabaseMigrationIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allClinicAndAuthenticationMigrationsApplyOnPostgresql() {
        Integer migrationCount = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success = true",
            Integer.class
        );
        Integer authColumns = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM information_schema.columns
             WHERE table_schema = current_schema()
               AND table_name = 'app_users'
               AND column_name IN ('failed_login_attempts', 'locked_until', 'last_login_at', 'password_changed_at')
            """,
            Integer.class
        );
        Integer clinicalTables = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM information_schema.tables
             WHERE table_schema = current_schema()
               AND table_name IN ('patients', 'queue_entries', 'triage_records', 'encounters', 'clinical_notes')
            """,
            Integer.class
        );

        assertThat(migrationCount).isEqualTo(3);
        assertThat(authColumns).isEqualTo(4);
        assertThat(clinicalTables).isEqualTo(5);
    }

    @Test
    void firstClinicSetupCreatesHashedAdminAndAuthenticatedSession() throws Exception {
        jdbc.update("DELETE FROM clinic_memberships");
        jdbc.update("DELETE FROM app_users");
        jdbc.update("DELETE FROM clinics");

        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(
            csrfResult.getResponse().getContentAsString(), "$.token"
        );
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();

        MvcResult setupResult = mockMvc.perform(post("/api/v1/auth/setup")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clinicName": "Clínica de Teste",
                      "fullName": "Administrador Inicial",
                      "email": "admin@markys.test",
                      "password": "SenhaSegura123!"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("admin@markys.test"))
            .andExpect(jsonPath("$.memberships[0].role").value("CLINIC_ADMIN"))
            .andReturn();

        String passwordHash = jdbc.queryForObject(
            "SELECT password_hash FROM app_users WHERE lower(email) = lower(?)",
            String.class,
            "admin@markys.test"
        );
        assertThat(passwordHash).isNotEqualTo("SenhaSegura123!");
        assertThat(passwordHash).startsWith("$2");

        MockHttpSession session = (MockHttpSession) setupResult.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mockMvc.perform(get("/api/v1/auth/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeClinicId").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/setup")
                .session(session)
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clinicName": "Outra Clínica",
                      "fullName": "Outro Administrador",
                      "email": "outro@markys.test",
                      "password": "OutraSenhaSegura123!"
                    }
                    """))
            .andExpect(status().isConflict());
    }
}
