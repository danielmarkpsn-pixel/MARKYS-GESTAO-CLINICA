package br.com.markys.gestaoclinica.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PatientDocumentStorageTest {
    @Test
    void missingR2ConfigurationDoesNotPreventStorageBeanConstruction() {
        assertDoesNotThrow(() ->
            new PatientDocumentStorage("/tmp/markys-private", "r2", "", "", "", "")
        );
    }

    @Test
    void missingR2ConfigurationIsReportedWhenDocumentStorageIsUsed() {
        PatientDocumentStorage storage =
            new PatientDocumentStorage("/tmp/markys-private", "r2", "", "", "", "");

        var pdf = new org.springframework.mock.web.MockMultipartFile(
            "file", "documento.pdf", "application/pdf",
            new byte[] { '%', 'P', 'D', 'F', '-', '1', '.', '4' }
        );

        assertThrows(IllegalStateException.class, () -> storage.store(pdf));
    }

    @Test
    void localStorageDoesNotRequireR2Configuration() {
        assertDoesNotThrow(() ->
            new PatientDocumentStorage("/tmp/markys-private", "local", "", "", "", "")
        );
    }
}
