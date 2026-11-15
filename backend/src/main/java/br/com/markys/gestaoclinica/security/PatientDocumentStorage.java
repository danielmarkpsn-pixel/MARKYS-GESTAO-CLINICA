package br.com.markys.gestaoclinica.security;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class PatientDocumentStorage {
    private final Path root;
    private final String bucket;
    private final S3Client s3;

    public PatientDocumentStorage(
        @Value("${MARKYS_PRIVATE_STORAGE_PATH:/var/lib/markys/private}") String directory,
        @Value("${MARKYS_STORAGE_PROVIDER:local}") String provider,
        @Value("${R2_ENDPOINT:}") String endpoint,
        @Value("${R2_BUCKET:}") String bucket,
        @Value("${R2_ACCESS_KEY_ID:}") String accessKey,
        @Value("${R2_SECRET_ACCESS_KEY:}") String secretKey
    ) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.bucket = bucket;
        String selectedProvider = provider.trim().toLowerCase();
        if ("r2".equals(selectedProvider)) {
            if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
                throw new IllegalStateException("O armazenamento R2 foi selecionado, mas suas configurações estão incompletas.");
            }
            var credentials = AwsBasicCredentials.create(accessKey, secretKey);
            var serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build();
            this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of("auto"))
                .serviceConfiguration(serviceConfiguration)
                .build();
        } else if ("local".equals(selectedProvider)) {
            this.s3 = null;
        } else {
            throw new IllegalStateException("MARKYS_STORAGE_PROVIDER deve ser 'local' ou 'r2'.");
        }
    }

    public String store(MultipartFile file) throws IOException {
        validate(file);
        byte[] bytes = file.getBytes();
        String key = UUID.randomUUID() + ".pdf";
        if (s3 != null) {
            s3.putObject(PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType("application/pdf")
                .contentLength((long) bytes.length).build(), RequestBody.fromBytes(bytes));
        } else {
            Files.createDirectories(root);
            Files.write(resolveLocal(key), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        return key;
    }

    public byte[] read(String key) throws IOException {
        validateKey(key);
        if (s3 != null) {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
            return object.asByteArray();
        }
        return Files.readAllBytes(resolveLocal(key));
    }

    public void delete(String key) {
        try {
            if (key == null || !key.matches("[0-9a-fA-F-]{36}\\.pdf")) return;
            if (s3 != null) {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } else {
                Files.deleteIfExists(resolveLocal(key));
            }
        } catch (IOException | S3Exception ignored) {
            // Orphan cleanup is handled operationally; never expose a storage path to the client.
        }
    }

    private static void validate(MultipartFile file) throws IOException {
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
    }

    private Path resolveLocal(String key) {
        validateKey(key);
        Path target = root.resolve(key).normalize();
        if (!target.getParent().equals(root)) throw new IllegalArgumentException("Caminho de documento inválido.");
        return target;
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[0-9a-fA-F-]{36}\\.pdf")) {
            throw new IllegalArgumentException("Identificador de documento inválido.");
        }
    }

    @PreDestroy
    public void close() {
        if (s3 != null) s3.close();
    }
}
