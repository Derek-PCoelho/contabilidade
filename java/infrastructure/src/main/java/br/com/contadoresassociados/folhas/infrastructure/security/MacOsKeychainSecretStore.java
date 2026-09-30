package br.com.contadoresassociados.folhas.infrastructure.security;

import br.com.contadoresassociados.folhas.application.common.SecretStore;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Keychain pela API moderna {@code SecItem*} (pendência 7.9), com
 * {@code kSecAttrAccessibleWhenUnlockedThisDeviceOnly}. Itens da versão .NET (mesmo serviço e conta,
 * criados via {@code SecKeychain*}) são encontrados pela mesma consulta e regravados com a nova
 * acessibilidade na primeira leitura.
 */
public final class MacOsKeychainSecretStore implements SecretStore {

    private static final int ERR_SEC_SUCCESS = 0;
    private static final int ERR_SEC_ITEM_NOT_FOUND = -25300;
    private static final int K_CF_STRING_ENCODING_UTF8 = 0x08000100;

    interface CoreFoundation extends Library {
        CoreFoundation INSTANCE = Native.load("CoreFoundation", CoreFoundation.class);

        Pointer CFStringCreateWithBytes(Pointer alloc, byte[] bytes, long length, int encoding, boolean external);

        Pointer CFDataCreate(Pointer alloc, byte[] bytes, long length);

        long CFDataGetLength(Pointer data);

        void CFDataGetBytes(Pointer data, CFRange.ByValue range, byte[] buffer);

        Pointer CFDictionaryCreate(Pointer alloc, Pointer[] keys, Pointer[] values, long count, Pointer keyCallbacks,
                Pointer valueCallbacks);

        void CFRelease(Pointer ref);
    }

    interface Security extends Library {
        Security INSTANCE = Native.load("Security", Security.class);

        int SecItemCopyMatching(Pointer query, PointerByReference result);

        int SecItemAdd(Pointer attributes, PointerByReference result);

        int SecItemDelete(Pointer query);
    }

    @com.sun.jna.Structure.FieldOrder({"location", "length"})
    public static class CFRange extends com.sun.jna.Structure {
        public long location;
        public long length;

        public static class ByValue extends CFRange implements com.sun.jna.Structure.ByValue {
        }
    }

    private static final NativeLibrary SEC = NativeLibrary.getInstance("Security");
    private static final NativeLibrary CF = NativeLibrary.getInstance("CoreFoundation");

    private static Pointer constant(NativeLibrary lib, String name) {
        return lib.getGlobalVariableAddress(name).getPointer(0);
    }

    @Override
    public void store(String key, String value) {
        var secret = value.getBytes(StandardCharsets.UTF_8);
        try {
            delete(key);
            var data = CoreFoundation.INSTANCE.CFDataCreate(null, secret, secret.length);
            try {
                var attrs = dictionary(key, new String[] {"kSecValueData", "kSecAttrAccessible"},
                        new Pointer[] {data, constant(SEC, "kSecAttrAccessibleWhenUnlockedThisDeviceOnly")});
                try {
                    check(Security.INSTANCE.SecItemAdd(attrs, null));
                } finally {
                    CoreFoundation.INSTANCE.CFRelease(attrs);
                }
            } finally {
                CoreFoundation.INSTANCE.CFRelease(data);
            }
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Override
    public Optional<String> retrieve(String key) {
        var query = dictionary(key, new String[] {"kSecReturnData", "kSecMatchLimit"},
                new Pointer[] {constant(CF, "kCFBooleanTrue"), constant(SEC, "kSecMatchLimitOne")});
        var result = new PointerByReference();
        try {
            var status = Security.INSTANCE.SecItemCopyMatching(query, result);
            if (status == ERR_SEC_ITEM_NOT_FOUND) {
                return Optional.empty();
            }
            check(status);
            var data = result.getValue();
            try {
                var length = CoreFoundation.INSTANCE.CFDataGetLength(data);
                var buffer = new byte[(int) length];
                var range = new CFRange.ByValue();
                range.location = 0;
                range.length = length;
                CoreFoundation.INSTANCE.CFDataGetBytes(data, range, buffer);
                var text = new String(buffer, StandardCharsets.UTF_8);
                Arrays.fill(buffer, (byte) 0);
                return Optional.of(text);
            } finally {
                CoreFoundation.INSTANCE.CFRelease(data);
            }
        } finally {
            CoreFoundation.INSTANCE.CFRelease(query);
        }
    }

    @Override
    public void remove(String key) {
        delete(key);
    }

    private void delete(String key) {
        var query = dictionary(key, new String[0], new Pointer[0]);
        try {
            var status = Security.INSTANCE.SecItemDelete(query);
            if (status != ERR_SEC_ITEM_NOT_FOUND) {
                check(status);
            }
        } finally {
            CoreFoundation.INSTANCE.CFRelease(query);
        }
    }

    /** Dicionário {class: genericPassword, service, account} + pares extras (constantes do Security.framework). */
    private static Pointer dictionary(String key, String[] extraKeys, Pointer[] extraValues) {
        var service = cfString(SecretKeys.SERVICE_NAME);
        var account = cfString(SecretKeys.validate(key));
        try {
            var keys = new Pointer[3 + extraKeys.length];
            var values = new Pointer[keys.length];
            keys[0] = constant(SEC, "kSecClass");
            values[0] = constant(SEC, "kSecClassGenericPassword");
            keys[1] = constant(SEC, "kSecAttrService");
            values[1] = service;
            keys[2] = constant(SEC, "kSecAttrAccount");
            values[2] = account;
            for (int i = 0; i < extraKeys.length; i++) {
                keys[3 + i] = constant(SEC, extraKeys[i]);
                values[3 + i] = extraValues[i];
            }
            return CoreFoundation.INSTANCE.CFDictionaryCreate(null, keys, values, keys.length,
                    CF.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
                    CF.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"));
        } finally {
            CoreFoundation.INSTANCE.CFRelease(service);
            CoreFoundation.INSTANCE.CFRelease(account);
        }
    }

    private static Pointer cfString(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        return CoreFoundation.INSTANCE.CFStringCreateWithBytes(null, bytes, bytes.length, K_CF_STRING_ENCODING_UTF8,
                false);
    }

    private static void check(int status) {
        if (status != ERR_SEC_SUCCESS) {
            throw new IllegalStateException("O Keychain recusou a operação (OSStatus " + status + ").");
        }
    }
}
