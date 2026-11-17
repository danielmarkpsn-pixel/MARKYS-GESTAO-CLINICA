package br.com.markys.gestaoclinica.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PatientDataProtection {
    private final byte[] encryptionKey;
    private final byte[] fingerprintKey;

    public PatientDataProtection(
        @Value("${MARKYS_PII_ENCRYPTION_KEY:}") String encryptionKey,
        @Value("${MARKYS_PII_FINGERPRINT_KEY:}") String fingerprintKey
    ) {
        this.encryptionKey = decodeKey(encryptionKey, 32, "MARKYS_PII_ENCRYPTION_KEY");
        this.fingerprintKey = decodeKey(fingerprintKey, 32, "MARKYS_PII_FINGERPRINT_KEY");
    }

    private static byte[] decodeKey(String value, int expectedLength, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != expectedLength) throw new IllegalArgumentException();
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(name + " deve ser Base64 com " + expectedLength + " bytes.", ex);
        }
    }

    public boolean isConfigured() {
        return encryptionKey != null && fingerprintKey != null;
    }

    public byte[] encrypt(String value) {
        requireConfigured();
        try {
            byte[] nonce = new byte[12];
            java.security.SecureRandom.getInstanceStrong().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Não foi possível proteger o documento do paciente.", ex);
        }
    }

    public String decrypt(byte[] encryptedValue) {
        requireConfigured();
        if (encryptedValue == null || encryptedValue.length < 29) {
            throw new IllegalArgumentException("Documento protegido inválido.");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(encryptedValue);
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Não foi possível descriptografar os dados do paciente.", ex);
        }
    }

    public byte[] fingerprint(String value) {
        requireConfigured();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Não foi possível validar o documento do paciente.", ex);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) throw new IllegalStateException(
            "Proteção de dados pessoais indisponível: configure as duas chaves de proteção no servidor.");
    }
}
