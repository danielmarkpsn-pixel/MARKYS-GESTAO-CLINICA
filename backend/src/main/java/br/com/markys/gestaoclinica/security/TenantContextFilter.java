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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantContextFilter extends OncePerRequestFilter {
    private static final String SESSION_CLINIC_KEY = "MARKYS_ACTIVE_CLINIC_ID";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate requestTransaction;

    public TenantContextFilter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requestTransaction = new TransactionTemplate(transactionManager);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(request.getContextPath() + "/api/v1/")
            || path.startsWith(request.getContextPath() + "/api/v1/auth/")
            || path.startsWith(request.getContextPath() + "/api/v1/master/")
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

        try {
            requestTransaction.executeWithoutResult(transaction -> {
                try {
                    // true makes this setting transaction-local; the connection pool
                    // cannot leak one request's tenant into another request.
                    jdbc.queryForObject(
                        "SELECT set_config('app.current_clinic_id', ?, true)",
                        String.class,
                        clinicId.toString()
                    );

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
                        try {
                            response.sendError(
                                HttpServletResponse.SC_FORBIDDEN,
                                "O vínculo com a clínica não está mais ativo."
                            );
                        } catch (IOException ex) {
                            throw new FilterExecutionException(ex);
                        }
                        return;
                    }

                    TenantContext.setClinicId(clinicId);
                    try {
                        chain.doFilter(request, response);
                    } catch (IOException | ServletException ex) {
                        throw new FilterExecutionException(ex);
                    } finally {
                        TenantContext.clear();
                    }
                } catch (FilterExecutionException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    throw ex;
                }
            });
        } catch (FilterExecutionException ex) {
            if (ex.getCause() instanceof IOException io) {
                throw io;
            }
            if (ex.getCause() instanceof ServletException servlet) {
                throw servlet;
            }
            throw new ServletException("Falha ao processar requisição com contexto de clínica.", ex);
        }
    }

    private static final class FilterExecutionException extends RuntimeException {
        private FilterExecutionException(Exception cause) {
            super(cause);
        }
    }
}
