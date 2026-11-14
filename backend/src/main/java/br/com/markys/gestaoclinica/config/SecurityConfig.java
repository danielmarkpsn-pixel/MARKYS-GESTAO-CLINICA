package br.com.markys.gestaoclinica.config;

import java.time.OffsetDateTime;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import br.com.markys.gestaoclinica.security.TenantContextFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.config.http.SessionCreationPolicy;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return email -> jdbc.query(
            """
            SELECT u.email, u.password_hash, u.status, u.locked_until,
                   EXISTS (SELECT 1 FROM platform_admins pa WHERE pa.user_id = u.id) AS is_master
              FROM app_users u
             WHERE lower(u.email) = lower(?)
            """,
            rs -> {
                if (!rs.next()) {
                    throw new UsernameNotFoundException("Credenciais inválidas");
                }
                boolean active = "ACTIVE".equals(rs.getString("status"));
                OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
                boolean locked = lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now());
                return User.withUsername(rs.getString("email"))
                    .password(rs.getString("password_hash"))
                    .authorities(rs.getBoolean("is_master") ? new String[] {"ROLE_USER", "ROLE_MASTER"} : new String[] {"ROLE_USER"})
                    .disabled(!active)
                    .accountLocked(locked)
                    .build();
            },
            email
        );
    }

    @Bean
    AuthenticationManager authenticationManager(
        UserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    TenantContextFilter tenantContextFilter(JdbcTemplate jdbc) {
        return new TenantContextFilter(jdbc);
    }

    @Bean
    FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration(TenantContextFilter filter) {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityContextLogoutHandler securityContextLogoutHandler() {
        return new SecurityContextLogoutHandler();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        SecurityContextRepository securityContextRepository,
        TenantContextFilter tenantContextFilter,
        @Value("${SESSION_COOKIE_SECURE:false}") boolean secureCookies
    ) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.secure(secureCookies).sameSite("Lax").path("/"));
        return http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(fixation -> fixation.changeSessionId())
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/health",
                    "/actuator/health",
                    "/actuator/health/**",
                    "/api/v1/auth/csrf",
                    "/api/v1/auth/setup-status",
                    "/api/v1/auth/login",
                    "/api/v1/auth/master-bootstrap",
                    "/api/v1/auth/setup"
                ).permitAll()
                .requestMatchers("/api/v1/master/**").hasRole("MASTER")
                .anyRequest().authenticated()
            )
            .headers(headers -> headers.frameOptions(frame -> frame.deny()))
            .addFilterAfter(tenantContextFilter, AuthorizationFilter.class)
            .build();
    }
}