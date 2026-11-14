package br.com.markys.gestaoclinica.security;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantContextFilter extends OncePerRequestFilter {
    private static final String SESSION_CLINIC_KEY = "MARKYS_ACTIVE_CLINIC_ID";
    private final JdbcTemplate jdbc;

    public TenantContextFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(request.getContextPath() + "/api/v1/")
            || path.startsWith(request.getContextPath() + "/api/v1/auth/")
            || path.equals(request.getContextPath() + "/api/v1/health");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        Object selectedClinic = session == null ? null : session.getAttribute(SESSION_CLINIC_KEY);

        if (authentication == null || !authentication.isAuthenticated() || selectedClinic == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Selecione uma clínica autorizada.");
            return;
        }

        final UUID clinicId;
        try {
            clinicId = UUID.fromString(selectedClinic.toString());
        } catch (IllegalArgumentException ex) {
            session.removeAttribute(SESSION_CLINIC_KEY);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Clínica ativa inválida.");
            return;
        }

        Long allowed = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM clinic_memberships m
              JOIN app_users u ON u.id = m.user_id
              JOIN clinics c ON c.id = m.clinic_id
             WHERE lower(u.email) = lower(?)
               AND m.clinic_id = ?
               AND m.status = 'ACTIVE'
               AND u.status = 'ACTIVE'
               AND c.status = 'ACTIVE'
            """,
            Long.class,
            authentication.getName(),
            clinicId
        );

        if (allowed == null || allowed != 1) {
            session.removeAttribute(SESSION_CLINIC_KEY);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "O vínculo com a clínica não está mais ativo.");
            return;
        }

        TenantContext.setClinicId(clinicId);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
