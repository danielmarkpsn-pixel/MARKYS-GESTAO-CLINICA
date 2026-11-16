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

    private static void logStartupFailure(Throwable failure) {
        System.err.println("========== MARKYS_STARTUP_FATAL ==========");
        System.err.println("O Spring Boot falhou durante a inicialização. Detalhes (com dados sensíveis filtrados):");

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        logThrowable(failure, "CAUSE", visited);

        System.err.println("========== END_MARKYS_STARTUP_FATAL ==========");
        System.err.flush();
    }

    private static void logThrowable(Throwable error, String relation, Set<Throwable> visited) {
        if (error == null || !visited.add(error)) {
            return;
        }

        String message = error.getMessage();
        System.err.println(relation + ": " + error.getClass().getName()
                + (message == null || message.isBlank() ? "" : ": " + sanitize(message)));

        for (StackTraceElement frame : error.getStackTrace()) {
            System.err.println("\tat " + frame);
        }

        for (Throwable suppressed : error.getSuppressed()) {
            logThrowable(suppressed, "SUPPRESSED", visited);
        }

        logThrowable(error.getCause(), "ROOT_CAUSE", visited);
    }

    private static String sanitize(String value) {
        String sanitized = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1$2[REDACTED]");
        sanitized = JDBC_QUERY.matcher(sanitized).replaceAll("$1?[REDACTED]");
        return URL_CREDENTIALS.matcher(sanitized).replaceAll("$1:[REDACTED]@");
    }
}
