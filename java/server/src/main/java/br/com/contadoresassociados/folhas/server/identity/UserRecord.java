package br.com.contadoresassociados.folhas.server.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Linha de {@code users} (ASP.NET Identity) com os papéis carregados. */
public record UserRecord(
        UUID id,
        UUID organizationId,
        String displayName,
        boolean active,
        long version,
        String userName,
        String email,
        boolean emailConfirmed,
        String passwordHash,
        String securityStamp,
        boolean twoFactorEnabled,
        Instant lockoutEnd,
        boolean lockoutEnabled,
        int accessFailedCount,
        List<String> roles) {

    public UserRecord {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean lockedOut(Instant now) {
        return lockoutEnabled && lockoutEnd != null && lockoutEnd.isAfter(now);
    }

    /** Mesma regra do {@code SignInManager.CanSignInAsync} + {@code IsActive} da versão .NET. */
    public boolean canSignIn(Instant now) {
        return active && emailConfirmed && !lockedOut(now);
    }

    public boolean privileged() {
        return roles.stream().anyMatch(r -> r.equals("OwnerTechnical") || r.equals("Administrator")
                || r.equals("Manager"));
    }
}
