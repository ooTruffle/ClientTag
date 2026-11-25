package com.ootruffle.clienttag.lunar;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gets a Lunar-issued JWT from Lunar's launcher websocket authenticator.
 * <p>
 * The exchange mirrors Minecraft's own login encryption handshake: Lunar sends an RSA
 * public key and a nonce, we prove account ownership through the Mojang session server
 * {@code join} call (hash of a random shared secret + that public key), then send back
 * the secret and nonce RSA-encrypted. Lunar replies with {@code auth_success}: a JWT
 * string and/or the same JWT as AES-128-GCM ({@code iv[12] | ciphertext | tag[16]}).
 */
public final class LunarAuthenticator {

    private static final String AUTH_URL = "wss://authenticator.lunarclientprod.com/launcher-v3";
    private static final String INITIATOR = "LAUNCHER_WEBSOCKET";
    private static final long TIMEOUT_MS = 15_000;

    /** Performs the Mojang session server join for the given server ID hash. */
    @FunctionalInterface
    public interface SessionJoiner {
        void join(String serverId) throws Exception;
    }

    private LunarAuthenticator() {}

    public static String fetchToken(UUID uuid, String name, SessionJoiner joiner) throws Exception {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", LunarSocket.LAUNCHER_USER_AGENT);
        headers.put("Accept", "application/x-protobuf");
        headers.put("X-Initiator", INITIATOR);

        final WsConnection ws = WsConnection.open(AUTH_URL, headers);
        try {
            // Serverbound hello: { 1: { 1: {uuid, name}, 2: initiator } }
            ws.send(new ProtoWriter()
                    .message(1, new ProtoWriter()
                            .message(1, ProtoWriter.uuidAndUsername(uuid, name))
                            .string(2, INITIATOR))
                    .toByteArray());

            // <- { 1: { 1: RSA public key (DER), 2: random_bytes } }
            final ProtoMessage challenge = ProtoMessage.parse(ws.nextMessage(TIMEOUT_MS)).message(1);
            if (challenge == null || challenge.bytes(1) == null || challenge.bytes(2) == null) {
                throw new IOException("authenticator sent no key challenge");
            }
            final byte[] publicKeyDer = challenge.bytes(1);
            final byte[] nonce = challenge.bytes(2);

            final byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            joiner.join(minecraftDigest(secret, publicKeyDer));

            // -> { 2: { 1: rsa(shared secret), 2: rsa(random_bytes) } }
            final PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyDer));
            ws.send(new ProtoWriter()
                    .message(2, new ProtoWriter().bytes(1, rsaEncrypt(key, secret)).bytes(2, rsaEncrypt(key, nonce)))
                    .toByteArray());

            // <- { 2: { 1: jwt, 2: encrypted_jwt } }, then the server closes.
            final byte[] reply = ws.nextMessage(TIMEOUT_MS);
            final ProtoMessage parsed = ProtoMessage.parse(reply);
            final String jwt = findJwt(parsed.message(2), secret);
            if (jwt == null) {
                throw new IOException("authenticator did not issue a token (reply " + parsed.describe()
                        + ", starts " + prefixes(parsed.message(2)) + ", " + closeReason(ws) + ")");
            }
            return jwt;
        } finally {
            ws.close();
        }
    }

    /** Plaintext {@code jwt}, or AES-128-GCM {@code encrypted_jwt}. */
    private static String findJwt(ProtoMessage issued, byte[] secret) {
        if (issued == null) {
            return null;
        }
        final String plain = asJwt(issued.string(1));
        if (plain != null) {
            return plain;
        }
        final byte[] encrypted = issued.bytes(2);
        if (encrypted == null || encrypted.length <= 28) {
            return null;
        }
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 0, 12)));
            return asJwt(cipher.doFinal(Arrays.copyOfRange(encrypted, 12, encrypted.length)));
        } catch (Exception e) {
            return null;
        }
    }

    /** The first 4 bytes of each byte field, enough to spot a format and too little to leak a token. */
    private static String prefixes(ProtoMessage issued) {
        if (issued == null) {
            return "none";
        }
        final StringBuilder out = new StringBuilder();
        for (byte[] value : issued.allBytes()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            for (int i = 0; i < Math.min(4, value.length); i++) {
                out.append(String.format("%02x", value[i]));
            }
        }
        return out.toString();
    }

    /** {@code data} as a JWT (three base64url parts, starting with an encoded "{"), or null. */
    private static String asJwt(byte[] data) {
        return data == null ? null : asJwt(new String(data, StandardCharsets.US_ASCII).trim());
    }

    private static String asJwt(String s) {
        return s != null && s.startsWith("eyJ") && s.chars().filter(c -> c == '.').count() == 2
                && s.matches("[A-Za-z0-9_\\-.=]+") ? s : null;
    }

    /** Lunar usually closes right after replying; its close reason says why it refused. */
    private static String closeReason(WsConnection ws) {
        try {
            ws.nextMessage(1_000);
            return "socket still open";
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Minecraft's signed SHA-1 hex digest, as used for session server IDs. */
    static String minecraftDigest(byte[]... parts) throws Exception {
        final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        for (byte[] part : parts) {
            sha1.update(part);
        }
        return new BigInteger(sha1.digest()).toString(16);
    }

    private static byte[] rsaEncrypt(PublicKey key, byte[] data) throws Exception {
        final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(data);
    }

}
