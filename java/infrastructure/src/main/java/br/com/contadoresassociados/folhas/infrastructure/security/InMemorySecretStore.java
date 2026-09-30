package br.com.contadoresassociados.folhas.infrastructure.security;

import br.com.contadoresassociados.folhas.application.common.SecretStore;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Somente para testes e para Linux em desenvolvimento (nada persiste). */
public final class InMemorySecretStore implements SecretStore {

    private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();

    @Override
    public void store(String key, String value) {
        values.put(SecretKeys.validate(key), java.util.Objects.requireNonNull(value));
    }

    @Override
    public Optional<String> retrieve(String key) {
        return Optional.ofNullable(values.get(SecretKeys.validate(key)));
    }

    @Override
    public void remove(String key) {
        values.remove(SecretKeys.validate(key));
    }
}
