package br.com.markys.gestaoclinica.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PatientDocumentStorage {
    private final Path root;

    public PatientDocumentStorage(
        @Value("${MARKYS_PRIVATE_STORAGE_PATH:/var/lib/markys/private}") String directory
    ) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Selecione um arquivo PDF.");
        if (file.getSize() > 10L * 1024 * 1024) throw new IllegalArgumentException("O PDF deve ter no máximo 10 MB.");
        if (!"application/pdf".equalsIgnoreCase(file.getContentType())
            && (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase().endsWith(".pdf"))) {
            throw new IllegalArgumentException("Somente arquivos PDF são permitidos.");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') {
            throw new IllegalArgumentException("O arquivo selecionado não possui assinatura válida de PDF.");
        }
        Files.createDirectories(root);
        String key = UUID.randomUUID() + ".pdf";
        Path target = root.resolve(key).normalize();
        if (!target.getParent().equals(root)) throw new IllegalStateException("Caminho privado inválido.");
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return key;
    }

    public byte[] read(String key) throws IOException {
        if (key == null || !key.matches("[0-9a-fA-F-]{36}\\.pdf")) {
            throw new IllegalArgumentException("Identificador de documento inválido.");
        }
        Path target = root.resolve(key).normalize();
        if (!target.getParent().equals(root)) throw new IllegalArgumentException("Caminho de documento inválido.");
        return Files.readAllBytes(target);
    }

    public void delete(String key) {
        try {
            if (key != null && key.matches("[0-9a-fA-F-]{36}\\.pdf")) Files.deleteIfExists(root.resolve(key).normalize());
        } catch (IOException ignored) {
            // Orphan cleanup is handled operationally; never expose a storage path to the client.
        }
    }
}
