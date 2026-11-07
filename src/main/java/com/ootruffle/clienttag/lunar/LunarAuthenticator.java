package com.ootruffle.clienttag.lunar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gets a Lunar-issued JWT from wss://authenticator.lunarclientprod.com/game.
 * <p>
 * The exchange mirrors Minecraft's own login encryption handshake: Lunar sends an RSA
 * public key and a nonce, we prove account ownership through the Mojang session server
 * {@code join} call (hash of a random shared secret + that public key), then send back
 * the secret and nonce RSA-encrypted. Lunar replies with the token and closes the socket.
 */
public final class LunarAuthenticator {

    private static final String AUTH_URL = "wss://authenticator.lunarclientprod.com/game";
    private static final long TIMEOUT_MS = 15_000;

    /** Performs the Mojang session server join for the given server ID hash. */
    @FunctionalInterface
    public interface SessionJoiner {
        void join(String serverId) throws Exception;
    }

    private LunarAuthenticator() {}

    public static String fetchToken(UUID uuid, String name, SessionJoiner joiner) throws Exception {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", LunarSocket.USER_AGENT);
        headers.put("Accept", "application/x-protobuf");
        headers.put("X-Initiator", "GAME_WEBSOCKET");

        final WsConnection ws = WsConnection.open(AUTH_URL, headers);
        try {
            // -> { 1: { 1: { 1: uuid, 2: name }, 2: "GAME_WEBSOCKET" } }
            ws.send(new ProtoWriter()
                    .message(1, new ProtoWriter()
                            .message(1, new ProtoWriter().message(1, ProtoWriter.uuid(uuid)).string(2, name))
                            .string(2, "GAME_WEBSOCKET"))
                    .toByteArray());

            // <- { 1: { 1: RSA public key (DER), 2: 4-byte nonce } }
            final ProtoMessage challenge = ProtoMessage.parse(ws.nextMessage(TIMEOUT_MS)).message(1);
            if (challenge == null || challenge.bytes(1) == null || challenge.bytes(2) == null) {
                throw new IOException("authenticator sent no key challenge");
            }
            final byte[] publicKeyDer = challenge.bytes(1);
            final byte[] nonce = challenge.bytes(2);

            final byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            joiner.join(minecraftDigest(secret, publicKeyDer));

            // -> { 2: { 1: rsa(secret), 2: rsa(nonce) } }
            final PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyDer));
            ws.send(new ProtoWriter()
                    .message(2, new ProtoWriter().bytes(1, rsaEncrypt(key, secret)).bytes(2, rsaEncrypt(key, nonce)))
                    .toByteArray());

            // <- { 2: { 1: jwt } }, then the server closes with "Issued". The JWT may come
            // encrypted with our shared secret (AES/CFB8, as in Minecraft's own login).
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

    /** The first byte field in {@code issued} that is a JWT, as sent or decrypted with {@code secret}. */
    private static String findJwt(ProtoMessage issued, byte[] secret) {
        if (issued == null) {
            return null;
        }
        for (byte[] value : issued.allBytes()) {
            for (byte[] candidate : decodings(value, secret)) {
                final String jwt = asJwt(candidate);
                if (jwt != null) {
                    return jwt;
                }
            }
        }
        return null;
    }

    /** {@code value} as sent, decrypted every way Lunar might plausibly use, and each of those inflated. */
    private static List<byte[]> decodings(byte[] value, byte[] secret) {
        final List<byte[]> decrypted = new ArrayList<>();
        decrypted.add(value);
        tryAdd(decrypted, () -> aes("AES/CFB8/NoPadding", secret, new IvParameterSpec(secret), value));
        tryAdd(decrypted, () -> aes("AES/CTR/NoPadding", secret, new IvParameterSpec(secret), value));
        tryAdd(decrypted, () -> aes("AES/CBC/PKCS5Padding", secret, new IvParameterSpec(secret), value));
        tryAdd(decrypted, () -> aes("AES/ECB/PKCS5Padding", secret, null, value));
        if (value.length > 28) {
            // AES-GCM with the 12-byte IV in front, as most libraries send it.
            tryAdd(decrypted, () -> aes("AES/GCM/NoPadding", secret,
                    new GCMParameterSpec(128, Arrays.copyOfRange(value, 0, 12)), Arrays.copyOfRange(value, 12, value.length)));
        }
        final List<byte[]> out = new ArrayList<>(decrypted);
        for (byte[] d : decrypted) {
            tryAdd(out, () -> inflate(d, true));
            tryAdd(out, () -> inflate(d, false));
        }
        return out;
    }

    private interface Decoding {
        byte[] apply() throws Exception;
    }

    private static void tryAdd(List<byte[]> out, Decoding decoding) {
        try {
            out.add(decoding.apply());
        } catch (Exception ignored) {
            // Not this format.
        }
    }

    private static byte[] aes(String transform, byte[] secret, AlgorithmParameterSpec params, byte[] data) throws Exception {
        final Cipher cipher = Cipher.getInstance(transform);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), params);
        return cipher.doFinal(data);
    }

    /** zlib ({@code gzip == false}) or gzip. */
    private static byte[] inflate(byte[] data, boolean gzip) throws Exception {
        final InputStream in = gzip ? new GZIPInputStream(new ByteArrayInputStream(data))
                : new InflaterInputStream(new ByteArrayInputStream(data));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
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
        final String s = new String(data, StandardCharsets.US_ASCII).trim();
        return s.startsWith("eyJ") && s.chars().filter(c -> c == '.').count() == 2
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
