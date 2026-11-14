package br.com.markys.gestaoclinica.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class MasterBootstrapController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String bootstrapToken;

    public MasterBootstrapController(
        JdbcTemplate jdbc,
        PasswordEncoder encoder,
        @Value("${MARKYS_MASTER_BOOTSTRAP_TOKEN:}") String bootstrapToken
    ) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.bootstrapToken = bootstrapToken;
    }

    @PostMapping("/master-bootstrap")
    @Transactional
    public ResponseEntity<?> bootstrap(
        @RequestHeader(value = "X-MARKYS-BOOTSTRAP-TOKEN", required = false) String suppliedToken,
        @Valid @RequestBody BootstrapRequest body
    ) {
        if (bootstrapToken == null || bootstrapToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Bootstrap MASTER não foi habilitado no servidor."));
        }
        if (suppliedToken == null || !MessageDigest.isEqual(
            bootstrapToken.getBytes(StandardCharsets.UTF_8),
            suppliedToken.getBytes(StandardCharsets.UTF_8)
        )) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Token de inicialização inválido."));
        }
        if (body.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.badRequest().body(Map.of("error", "A senha excede o limite seguro do BCrypt."));
        }

        jdbc.execute("LOCK TABLE platform_admins IN EXCLUSIVE MODE");
        Long existing = jdbc.queryForObject("SELECT count(*) FROM platform_admins", Long.class);
        if (existing != null && existing > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "A conta MASTER já foi criada. O bootstrap está encerrado."));
        }

        String email = body.email().trim().toLowerCase(Locale.ROOT);
        Long duplicate = jdbc.queryForObject(
            "SELECT count(*) FROM app_users WHERE lower(email) = lower(?)", Long.class, email
        );
        if (duplicate != null && duplicate > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Este e-mail já está cadastrado."));
        }

        UUID userId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO app_users (id, full_name, email, password_hash, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
            userId, body.fullName().trim(), email, encoder.encode(body.password())
        );
        jdbc.update("INSERT INTO platform_admins (user_id, created_by) VALUES (?, 'bootstrap')", userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "message", "Conta MASTER criada. Faça login com o e-mail e a senha cadastrados.",
            "email", email
        ));
    }

    public record BootstrapRequest(
        @NotBlank @Size(max = 180) String fullName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 12, max = 72) String password
    ) {}
}
