package br.com.markys.gestaoclinica.security;

import java.util.UUID;

public final class TenantContext {
    private static final ThreadLocal<UUID> ACTIVE_CLINIC = new ThreadLocal<>();

    private TenantContext() {}

    public static void setClinicId(UUID clinicId) {
        ACTIVE_CLINIC.set(clinicId);
    }

    public static UUID getClinicId() {
        return ACTIVE_CLINIC.get();
    }

    public static UUID requireClinicId() {
        UUID clinicId = ACTIVE_CLINIC.get();
        if (clinicId == null) {
            throw new IllegalStateException("Nenhuma clínica ativa foi selecionada para esta requisição.");
        }
        return clinicId;
    }

    public static void clear() {
        ACTIVE_CLINIC.remove();
    }
}
