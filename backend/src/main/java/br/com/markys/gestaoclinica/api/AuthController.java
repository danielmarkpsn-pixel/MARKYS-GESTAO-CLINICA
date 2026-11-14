package br.com.markys.gestaoclinica.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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

    @GetMapping("/setup-status")
    public Map<String, Boolean> setupStatus() {
        Long masters = jdbc.queryForObject("SELECT count(*) FROM platform_admins", Long.class);
        boolean available = masters == null || masters == 0;
        return Map.of("available", available);
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
    public ResponseEntity<?> setupDisabled() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "O cadastro de clínicas é exclusivo do administrador MASTER."));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication, HttpSession session) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(userProfile(authentication.getName(), session));
    }

    @PostMapping("/select-clinic")
    public ResponseEntity<?> selectClinic(
        @Valid @RequestBody ClinicSelectionRequest body,
        Authentication authentication,
        HttpSession session
    ) {
        Long membershipCount = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM clinic_memberships m
              JOIN app_users u ON u.id = m.user_id
              JOIN clinics c ON c.id = m.clinic_id
             WHERE lower(u.email) = lower(?)
               AND m.clinic_id = ?
               AND m.status = 'ACTIVE'
               AND c.status = 'ACTIVE'
               AND u.status = 'ACTIVE'
            """,
            Long.class,
            authentication.getName(),
            body.clinicId()
        );
        if (membershipCount == null || membershipCount != 1) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Você não possui acesso ativo a esta clínica."));
        }
        session.setAttribute("MARKYS_ACTIVE_CLINIC_ID", body.clinicId().toString());
        return ResponseEntity.ok(userProfile(authentication.getName(), session));
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
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "E-mail ou senha inválidos."));
        }
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
            if (request.getSession(false) != null) {
                request.changeSessionId();
            }
            HttpSession session = request.getSession();
            List<Map<String, Object>> memberships = membershipsFor(email);
            if (memberships.size() == 1) {
                session.setAttribute("MARKYS_ACTIVE_CLINIC_ID", memberships.getFirst().get("clinic_id").toString());
            } else {
                session.removeAttribute("MARKYS_ACTIVE_CLINIC_ID");
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            return ResponseEntity.ok(userProfile(email, session));
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
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "E-mail ou senha inválidos."));
        } catch (RuntimeException ex) {
            if (ex instanceof BadCredentialsException) {
                throw ex;
            }
            throw new AuthenticationServiceException("Não foi possível concluir a autenticação.", ex);
        }
    }

    private Map<String, Object> userProfile(String email, HttpSession session) {
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
        List<Map<String, Object>> memberships = membershipsFor(email);
        Map<String, Object> profile = new java.util.LinkedHashMap<>();
        profile.put("userId", user.get("id"));
        profile.put("fullName", user.get("full_name"));
        profile.put("email", user.get("email"));
        Boolean master = jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM platform_admins pa
                JOIN app_users u ON u.id = pa.user_id
                WHERE lower(u.email) = lower(?)
            )
            """,
            Boolean.class,
            email
        );
        profile.put("globalRole", Boolean.TRUE.equals(master) ? "MASTER" : "USER");
        profile.put("memberships", memberships);
        profile.put("activeClinicId", session.getAttribute("MARKYS_ACTIVE_CLINIC_ID"));
        return profile;
    }

    private List<Map<String, Object>> membershipsFor(String email) {
        return jdbc.queryForList(
            """
            SELECT m.id AS membership_id, c.id AS clinic_id, c.name AS clinic_name, m.role
              FROM clinic_memberships m
              JOIN app_users u ON u.id = m.user_id
              JOIN clinics c ON c.id = m.clinic_id
             WHERE lower(u.email) = lower(?)
               AND u.status = 'ACTIVE'
               AND m.status = 'ACTIVE'
               AND c.status = 'ACTIVE'
             ORDER BY c.name
            """,
            email
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
        @NotBlank @Size(min = 12, max = 72) String password
    ) {}

    public record ClinicSelectionRequest(@jakarta.validation.constraints.NotNull UUID clinicId) {}
}
