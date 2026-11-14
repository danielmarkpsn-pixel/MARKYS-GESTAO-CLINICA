package br.com.markys.gestaoclinica.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/master")
public class MasterClinicController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public MasterClinicController(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @GetMapping("/clinics")
    public List<Map<String, Object>> clinics() {
        return jdbc.queryForList("""
            SELECT c.id, c.name, c.status, c.created_at,
                   count(DISTINCT m.user_id) FILTER (WHERE m.status = 'ACTIVE') AS active_users,
                   count(DISTINCT m.user_id) FILTER (WHERE m.role = 'CLINIC_ADMIN' AND m.status = 'ACTIVE') AS administrators
              FROM clinics c
              LEFT JOIN clinic_memberships m ON m.clinic_id = c.id
             GROUP BY c.id
             ORDER BY c.created_at DESC
            """);
    }

    @PostMapping("/clinics")
    @Transactional
    public ResponseEntity<?> createClinic(@Valid @RequestBody CreateClinicRequest body) {
        if (body.adminPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.badRequest().body(Map.of("error", "A senha inicial excede o limite seguro do BCrypt."));
        }
        String email = body.adminEmail().trim().toLowerCase(Locale.ROOT);
        Long duplicate = jdbc.queryForObject(
            "SELECT count(*) FROM app_users WHERE lower(email) = lower(?)", Long.class, email
        );
        if (duplicate != null && duplicate > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Já existe uma conta com este e-mail."));
        }

        UUID clinicId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        jdbc.update("INSERT INTO clinics (id, name, status) VALUES (?, ?, 'ACTIVE')",
            clinicId, body.clinicName().trim());
        jdbc.update("""
            INSERT INTO app_users (id, full_name, email, password_hash, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """, userId, body.adminName().trim(), email, encoder.encode(body.adminPassword()));
        jdbc.update("""
            INSERT INTO clinic_memberships (id, clinic_id, user_id, role, status)
            VALUES (?, ?, ?, 'CLINIC_ADMIN', 'ACTIVE')
            """, membershipId, clinicId, userId);
        jdbc.queryForObject("SELECT set_config('app.current_clinic_id', ?, true)", String.class, clinicId.toString());
        seedClinicCatalog(clinicId);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "clinicId", clinicId,
            "clinicName", body.clinicName().trim(),
            "adminEmail", email,
            "message", "Clínica e administrador local criados."
        ));
    }


    private void seedClinicCatalog(UUID clinicId) {
        Map<String, UUID> rooms = new HashMap<>();
        List<String> roomNames = List.of(
            "RAIO X", "COLETA / LABORATÓRIO", "AUDIOMETRIA", "EXAMES COMPLEMENTARES",
            "CONSULTA MÉDICA", "COLETA DE PCCU", "PROCEDIMENTOS", "TRIAGEM", "ULTRASSOM", "FISIOTERAPIA"
        );
        for (String name : roomNames) {
            UUID roomId = UUID.randomUUID();
            jdbc.update("INSERT INTO clinic_rooms (id, clinic_id, name) VALUES (?, ?, ?)", roomId, clinicId, name);
            rooms.put(name, roomId);
        }

        List<String> serviceTypes = List.of(
            "Admissional", "Demissional", "Periódico", "Retorno", "Mudança de riscos",
            "Particular", "Pontual", "Consulta Ocupacional", "Concurso"
        );
        for (int i = 0; i < serviceTypes.size(); i++) {
            jdbc.update("INSERT INTO service_types (clinic_id, name, sort_order) VALUES (?, ?, ?)",
                clinicId, serviceTypes.get(i), i);
        }

        List<ExamSeed> exams = new ArrayList<>();
        addExams(exams, "RAIO_X", "RAIO X",
            "Tórax PA", "Tórax Perfil", "Tórax OIT", "Tórax AP", "Coluna Lombar", "Coluna Lombo Sacra",
            "Coluna Total", "Coluna Cervical", "Coluna Torácica", "Coluna Dorsal", "Ombros", "Ombro D",
            "Ombro E", "Mãos", "Mão D", "Mão E", "Joelhos", "Joelhos D", "Joelhos E", "Punhos", "Punhos D", "Punhos E");
        addExams(exams, "LABORATORIO", "COLETA / LABORATÓRIO",
            "Coleta de sangue", "Hemograma C.", "Glicose", "Creatinina", "Tipagem S.", "Beta", "Colesterol",
            "Colinesterase", "Cultura F", "EPF", "EAS", "Acetona", "Teste Ergonométrico", "Acetona na Urina",
            "Ácido Mandélico", "Hemoglobina Glicada", "Lipidograma", "Chumbo na Urina", "Micológico",
            "Toxicológico", "Triglicerídeos", "TGO", "TGP", "Chumbo sanguíneo", "Hepatite HBsAg",
            "Hepatite Anti-HBs", "Hepatite Anti-HCV", "Hepatite B - ANTI-HBC TOTAL", "Metil etil cetona",
            "Ácido metil-hipúrico", "Ácido hipúrico", "Ácido delta-aminolevulínico");
        addExams(exams, "AUDIOMETRIA", "AUDIOMETRIA",
            "Audiometria", "Audiometria Tonal", "Audiometria Vocal", "Audiometria Ocupacional",
            "Audiometria Concurso", "Teste Otoneurológico", "Teste Vestibular");
        addExams(exams, "COMPLEMENTAR", "EXAMES COMPLEMENTARES",
            "EEG", "ECG", "Espirometria", "Acuidade", "Acuidade com percepção de cores",
            "Av Psico", "Mapa 24h", "Holter 24h", "Risco Cirúrgico");

        for (int i = 0; i < exams.size(); i++) {
            ExamSeed exam = exams.get(i);
            jdbc.update("""
                INSERT INTO exam_catalog (clinic_id, category, name, room_id, sort_order)
                VALUES (?, ?, ?, ?, ?)
                """, clinicId, exam.category(), exam.name(), rooms.get(exam.roomName()), i);
        }
    }

    private static void addExams(List<ExamSeed> target, String category, String roomName, String... names) {
        for (String name : names) target.add(new ExamSeed(category, roomName, name));
    }

    private record ExamSeed(String category, String roomName, String name) {}

    @PatchMapping("/clinics/{clinicId}/status")
    public ResponseEntity<?> updateStatus(
        @PathVariable UUID clinicId,
        @Valid @RequestBody ClinicStatusRequest body
    ) {
        String status = body.status().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "SUSPENDED", "INACTIVE").contains(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Status inválido."));
        }
        int updated = jdbc.update(
            "UPDATE clinics SET status = ?, updated_at = now() WHERE id = ?", status, clinicId
        );
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("clinicId", clinicId, "status", status));
    }

    public record CreateClinicRequest(
        @NotBlank @Size(max = 180) String clinicName,
        @NotBlank @Size(max = 180) String adminName,
        @NotBlank @Email @Size(max = 254) String adminEmail,
        @NotBlank @Size(min = 12, max = 72) String adminPassword
    ) {}

    public record ClinicStatusRequest(@NotBlank @Size(max = 20) String status) {}
}
