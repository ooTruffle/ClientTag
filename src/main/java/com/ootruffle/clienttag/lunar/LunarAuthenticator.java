package com.ootruffle.clienttag.lunar;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;

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

            // <- { 2: { 1: jwt } }, then the server closes with "Issued"
            final ProtoMessage issued = ProtoMessage.parse(ws.nextMessage(TIMEOUT_MS)).message(2);
            final String jwt = issued == null ? null : issued.string(1);
            if (jwt == null || jwt.isEmpty()) {
                throw new IOException("authenticator did not issue a token");
            }
            return jwt;
        } finally {
            ws.close();
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
