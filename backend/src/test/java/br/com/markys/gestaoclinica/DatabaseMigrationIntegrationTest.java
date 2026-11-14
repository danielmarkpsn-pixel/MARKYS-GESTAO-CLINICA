package br.com.markys.gestaoclinica;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@EnabledIfSystemProperty(named = "markys.db.integration", matches = "true")
class DatabaseMigrationIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

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
}
