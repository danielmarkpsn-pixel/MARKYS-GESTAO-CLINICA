package br.com.markys.gestaoclinica.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {
    private final JdbcTemplate jdbc;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextLogoutHandler logoutHandler;

    public AuthController(
        JdbcTemplate jdbc,
        AuthenticationManager authenticationManager,
        PasswordEncoder passwordEncoder,
        SecurityContextRepository securityContextRepository,
        SecurityContextLogoutHandler logoutHandler
    ) {
        this.jdbc = jdbc;
        this.authenticationManager = authenticationManager;
        this.passwordEncoder = passwordEncoder;
        this.securityContextRepository = securityContextRepository;
        this.logoutHandler = logoutHandler;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
        @Valid @RequestBody LoginRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        return authenticate(body.email(), body.password(), request, response);
    }

    @PostMapping("/setup")
    @Transactional
    public ResponseEntity<?> setup(
        @Valid @RequestBody SetupRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        // Serialize first-run requests so two administrators cannot bootstrap two tenants concurrently.
        jdbc.execute("LOCK TABLE clinics IN EXCLUSIVE MODE");
        Long users = jdbc.queryForObject("SELECT count(*) FROM app_users", Long.class);
        Long clinics = jdbc.queryForObject("SELECT count(*) FROM clinics", Long.class);
        if (users != null && users > 0 || clinics != null && clinics > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "A configuração inicial já foi concluída."));
        }

        UUID clinicId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        String email = body.email().trim().toLowerCase();

        jdbc.update(
            """
            INSERT INTO clinics (id, name, status)
            VALUES (?, ?, 'ACTIVE')
            """,
            clinicId, body.clinicName().trim()
        );
        jdbc.update(
            """
            INSERT INTO app_users (id, full_name, email, password_hash, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """,
            userId, body.fullName().trim(), email, passwordEncoder.encode(body.password())
        );
        jdbc.update(
            """
            INSERT INTO clinic_memberships (id, clinic_id, user_id, role, status)
            VALUES (?, ?, ?, 'CLINIC_ADMIN', 'ACTIVE')
            """,
            membershipId, clinicId, userId
        );

        return authenticate(email, body.password(), request, response);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(userProfile(authentication.getName()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        Authentication authentication,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        logoutHandler.logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> authenticate(
        String rawEmail,
        String password,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String email = rawEmail.trim().toLowerCase();
        try {
            Authentication result = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, password)
            );
            jdbc.update(
                """
                UPDATE app_users
                   SET failed_login_attempts = 0,
                       locked_until = NULL,
                       last_login_at = now()
                 WHERE lower(email) = lower(?)
                """,
                email
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            return ResponseEntity.ok(userProfile(email));
        } catch (AuthenticationException ex) {
            jdbc.update(
                """
                UPDATE app_users
                   SET failed_login_attempts = LEAST(failed_login_attempts + 1, 100),
                       locked_until = CASE
                           WHEN failed_login_attempts + 1 >= 5
                           THEN now() + interval '15 minutes'
                           ELSE locked_until
                       END
                 WHERE lower(email) = lower(?)
                   AND status = 'ACTIVE'
                   AND (locked_until IS NULL OR locked_until <= now())
                """,
                email
            );
            throw new BadCredentialsException("E-mail ou senha inválidos.");
        } catch (RuntimeException ex) {
            if (ex instanceof BadCredentialsException) {
                throw ex;
            }
            throw new AuthenticationServiceException("Não foi possível concluir a autenticação.", ex);
        }
    }

    private Map<String, Object> userProfile(String email) {
        List<Map<String, Object>> users = jdbc.queryForList(
            """
            SELECT id, full_name, email
              FROM app_users
             WHERE lower(email) = lower(?)
               AND status = 'ACTIVE'
            """,
            email
        );
        if (users.isEmpty()) {
            throw new BadCredentialsException("Usuário não encontrado.");
        }
        Map<String, Object> user = users.getFirst();
        List<Map<String, Object>> memberships = jdbc.queryForList(
            """
            SELECT m.id AS membership_id, c.id AS clinic_id, c.name AS clinic_name, m.role
              FROM clinic_memberships m
              JOIN clinics c ON c.id = m.clinic_id
             WHERE m.user_id = ?
               AND m.status = 'ACTIVE'
               AND c.status = 'ACTIVE'
             ORDER BY c.name
            """,
            user.get("id")
        );
        return Map.of(
            "userId", user.get("id"),
            "fullName", user.get("full_name"),
            "email", user.get("email"),
            "memberships", memberships
        );
    }

    public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 200) String password
    ) {}

    public record SetupRequest(
        @NotBlank @Size(max = 180) String clinicName,
        @NotBlank @Size(max = 180) String fullName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 12, max = 200) String password
    ) {}
}
