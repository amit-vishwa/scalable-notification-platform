package io.github.amitvishwa.notification.application.idempotency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String calculate(
            String recipientEmail,
            String subject,
            String body
    ) {
        MessageDigest digest = sha256();

        // Version marker allows deliberate evolution of the hash format.
        digest.update((byte) 1);

        appendField(digest, recipientEmail);
        appendField(digest, subject);
        appendField(digest, body);

        return HexFormat.of().formatHex(digest.digest());
    }

    private static void appendField(
            MessageDigest digest,
            String value
    ) {
        Objects.requireNonNull(value, "Fingerprint field must not be null");

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        digest.update(
                ByteBuffer.allocate(Integer.BYTES)
                        .putInt(bytes.length)
                        .array()
        );
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Required SHA-256 algorithm is unavailable",
                    exception
            );
        }
    }
}