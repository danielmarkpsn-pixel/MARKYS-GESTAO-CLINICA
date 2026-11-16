package br.com.markys.gestaoclinica;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MarkysApplication {
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|secret|token|api[_-]?key|access[_-]?key|authorization)(\\s*[=:]\\s*)([^\\s,;]+)");
    private static final Pattern JDBC_QUERY = Pattern.compile(
            "(?i)(jdbc:postgresql://[^\\s?]+)\\?[^\\s]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile(
            "(?i)(://[^:/\\s]+):[^@/\\s]+@");

    public static void main(String[] args) {
        try {
            SpringApplication.run(MarkysApplication.class, args);
        } catch (Throwable failure) {
            logStartupFailure(failure);
            throw failure;
        }
    }

    /*
     * Emit the complete diagnostic as ONE physical log event. Cloudflare may
     * split multi-line stderr output into separate records, which previously
     * made the exception cause difficult to correlate with the start/end markers.
     */
    private static void logStartupFailure(Throwable failure) {
        StringBuilder diagnostic = new StringBuilder(4096);
        diagnostic.append("MARKYS_STARTUP_FATAL ");
        diagnostic.append("Spring Boot startup failed; sensitive values are filtered. ");
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        appendThrowable(diagnostic, failure, "CAUSE", visited);
        System.err.println(sanitize(diagnostic.toString()));
        System.err.flush();
    }

    private static void appendThrowable(
            StringBuilder diagnostic, Throwable error, String relation, Set<Throwable> visited) {
        if (error == null || !visited.add(error)) {
            return;
        }

        diagnostic.append("\\n").append(relation).append(": ")
                .append(error.getClass().getName());
        String message = error.getMessage();
        if (message != null && !message.isBlank()) {
            diagnostic.append(": ").append(message);
        }
        for (StackTraceElement frame : error.getStackTrace()) {
            diagnostic.append("\\n    at ").append(frame);
        }
        for (Throwable suppressed : error.getSuppressed()) {
            appendThrowable(diagnostic, suppressed, "SUPPRESSED", visited);
        }
        appendThrowable(diagnostic, error.getCause(), "ROOT_CAUSE", visited);
    }

    private static String sanitize(String value) {
        String sanitized = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1$2[REDACTED]");
        sanitized = JDBC_QUERY.matcher(sanitized).replaceAll("$1?[REDACTED]");
        return URL_CREDENTIALS.matcher(sanitized).replaceAll("$1:[REDACTED]@");
    }
}
