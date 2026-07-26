package io.clusterinfra.rca.webconsole.security;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class OpaqueTokenKeyRing {
    public static final int MAX_PREVIOUS_KEYS = 8;

    private static final Pattern KEY_ID_PATTERN =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final String currentKeyId;
    private final Map<String, String> keys;
    private final WriteVersion writeVersion;
    private final boolean rehashOnAuthentication;

    private OpaqueTokenKeyRing(
        String currentKeyId,
        Map<String, String> keys,
        WriteVersion writeVersion,
        boolean rehashOnAuthentication
    ) {
        this.currentKeyId = currentKeyId;
        this.keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
        this.writeVersion = writeVersion;
        this.rehashOnAuthentication = rehashOnAuthentication;
    }

    public static OpaqueTokenKeyRing from(RcaConsoleProperties.Security security) {
        String currentKeyId = requireKeyId(security.getOpaqueTokenKeyId());
        String currentPepper = security.getOpaqueTokenPepper();
        if (currentPepper == null || currentPepper.isBlank()) {
            throw new IllegalStateException("RCA_OPAQUE_TOKEN_PEPPER must not be blank");
        }

        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(currentKeyId, currentPepper);
        for (Map.Entry<String, String> entry
            : parsePreviousKeys(security.getOpaqueTokenPreviousKeys()).entrySet()) {
            if (keys.containsKey(entry.getKey())) {
                throw new IllegalStateException(
                    "RCA_OPAQUE_TOKEN_PREVIOUS_KEYS must not repeat the current key id"
                );
            }
            if (keys.containsValue(entry.getValue())) {
                throw new IllegalStateException(
                    "opaque token key ids must not share the same pepper"
                );
            }
            keys.put(entry.getKey(), entry.getValue());
        }

        WriteVersion writeVersion = WriteVersion.parse(
            security.getOpaqueTokenWriteVersion()
        );
        if (security.isOpaqueTokenRehashOnAuthentication()
            && writeVersion != WriteVersion.V2) {
            throw new IllegalStateException(
                "RCA_OPAQUE_TOKEN_REHASH_ON_AUTHENTICATION requires "
                    + "RCA_OPAQUE_TOKEN_WRITE_VERSION=v2"
            );
        }
        return new OpaqueTokenKeyRing(
            currentKeyId,
            keys,
            writeVersion,
            security.isOpaqueTokenRehashOnAuthentication()
        );
    }

    public static Map<String, String> parsePreviousKeys(String configuredKeys) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (configuredKeys == null || configuredKeys.isBlank()) {
            return parsed;
        }
        String[] entries = configuredKeys.split(",", -1);
        if (entries.length > MAX_PREVIOUS_KEYS) {
            throw new IllegalStateException(
                "RCA_OPAQUE_TOKEN_PREVIOUS_KEYS supports at most "
                    + MAX_PREVIOUS_KEYS + " entries"
            );
        }
        for (String rawEntry : entries) {
            String entry = rawEntry.trim();
            int separator = entry.indexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalStateException(
                    "RCA_OPAQUE_TOKEN_PREVIOUS_KEYS entries must use key-id=pepper"
                );
            }
            String keyId = requireKeyId(entry.substring(0, separator).trim());
            String pepper = entry.substring(separator + 1).trim();
            if (pepper.isBlank()) {
                throw new IllegalStateException(
                    "RCA_OPAQUE_TOKEN_PREVIOUS_KEYS peppers must not be blank"
                );
            }
            if (parsed.putIfAbsent(keyId, pepper) != null) {
                throw new IllegalStateException(
                    "RCA_OPAQUE_TOKEN_PREVIOUS_KEYS contains a duplicate key id"
                );
            }
        }
        return parsed;
    }

    public String currentKeyId() {
        return currentKeyId;
    }

    public Map<String, String> keys() {
        return keys;
    }

    public WriteVersion writeVersion() {
        return writeVersion;
    }

    public boolean rehashOnAuthentication() {
        return rehashOnAuthentication;
    }

    private static String requireKeyId(String keyId) {
        if (keyId == null || !KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new IllegalStateException(
                "opaque token key ids must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}"
            );
        }
        return keyId;
    }

    public enum WriteVersion {
        V1,
        V2;

        private static WriteVersion parse(String value) {
            if ("v1".equalsIgnoreCase(value)) {
                return V1;
            }
            if ("v2".equalsIgnoreCase(value)) {
                return V2;
            }
            throw new IllegalStateException(
                "RCA_OPAQUE_TOKEN_WRITE_VERSION must be v1 or v2"
            );
        }
    }
}
