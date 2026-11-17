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
    private final String provider;
    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private volatile S3Client s3;

    public PatientDocumentStorage(
        @Value("${MARKYS_PRIVATE_STORAGE_PATH:/var/lib/markys/private}") String directory,
        @Value("${MARKYS_STORAGE_PROVIDER:local}") String provider,
        @Value("${R2_ENDPOINT:}") String endpoint,
        @Value("${R2_BUCKET:}") String bucket,
        @Value("${R2_ACCESS_KEY_ID:}") String accessKey,
        @Value("${R2_SECRET_ACCESS_KEY:}") String secretKey
    ) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.provider = provider == null ? "local" : provider.trim().toLowerCase();
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.bucket = bucket == null ? "" : bucket.trim();
        this.accessKey = accessKey == null ? "" : accessKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        // Do not initialize or validate remote storage while Spring is starting.
        // A temporary R2 configuration problem must not prevent login and clinical screens.
    }

    private S3Client s3Client() {
        if ("local".equals(provider)) return null;
        if (!"r2".equals(provider)) {
            throw new IllegalStateException("MARKYS_STORAGE_PROVIDER deve ser 'local' ou 'r2'.");
        }

        S3Client current = s3;
        if (current != null) return current;

        synchronized (this) {
            current = s3;
            if (current != null) return current;

            if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
                throw new IllegalStateException(
                    "Armazenamento de documentos indisponível: configure endpoint, bucket e credenciais do R2."
                );
            }

            try {
                var credentials = AwsBasicCredentials.create(accessKey, secretKey);
                var serviceConfiguration = S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build();
                current = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .region(Region.of("auto"))
                    .serviceConfiguration(serviceConfiguration)
                    .build();
                s3 = current;
                return current;
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                    "Não foi possível preparar a conexão com o armazenamento R2. Verifique o endpoint e as credenciais.",
                    ex
                );
            }
        }
    }

    public String store(MultipartFile file) throws IOException {
        validate(file);
        byte[] bytes = file.getBytes();
        String key = UUID.randomUUID() + ".pdf";
        S3Client client = s3Client();
        if (client != null) {
            client.putObject(PutObjectRequest.builder()
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
        S3Client client = s3Client();
        if (client != null) {
            ResponseBytes<GetObjectResponse> object = client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
            return object.asByteArray();
        }
        return Files.readAllBytes(resolveLocal(key));
    }

    public void delete(String key) {
        try {
            if (key == null || !key.matches("[0-9a-fA-F-]{36}\\.pdf")) return;
            S3Client client = s3Client();
            if (client != null) {
                client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
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
        S3Client client = s3;
        if (client != null) client.close();
    }
}
