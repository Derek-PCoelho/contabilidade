package br.com.contadoresassociados.folhas.application.common;

import java.util.Optional;

/** Cofre de segredos do sistema operacional (Keychain no macOS, DPAPI no Windows). */
public interface SecretStore {

    void store(String key, String value);

    Optional<String> retrieve(String key);

    void remove(String key);
}
