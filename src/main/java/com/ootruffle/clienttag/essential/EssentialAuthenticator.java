package com.ootruffle.clienttag.essential;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Proves account ownership to Essential's connection manager through the Mojang "server
 * join" proof: we join a server ID derived from a random secret, then hand Essential the
 * secret (in the websocket's Basic auth header) so it can check Mojang's {@code hasJoined}.
 * The socket has to be opened within a few seconds of the join.
 */
public final class EssentialAuthenticator {

    /** Mixed into the server ID hash after the secret. */
    private static final byte[] SERVER_ID_SALT = hex("173be201d4e5591dcef37bcaf701d136");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Performs the Mojang session server join for the given server ID. */
    @FunctionalInterface
    public interface SessionJoiner {
        void join(String serverId) throws Exception;
    }

    private EssentialAuthenticator() {}

    /** Joins with a fresh secret and returns the {@code Authorization} header value that proves it. */
    public static String authorize(String name, SessionJoiner joiner) throws Exception {
        final byte[] secret = new byte[16];
        RANDOM.nextBytes(secret);
        joiner.join(serverId(secret));

        // name ":" raw secret bytes - the secret is not hex encoded.
        final byte[] user = (name + ":").getBytes(StandardCharsets.UTF_8);
        final byte[] credentials = new byte[user.length + secret.length];
        System.arraycopy(user, 0, credentials, 0, user.length);
        System.arraycopy(secret, 0, credentials, user.length, secret.length);
        return "Basic " + Base64.getEncoder().encodeToString(credentials);
    }

    /** Minecraft's signed SHA-1 hex digest of secret + salt. */
    static String serverId(byte[] secret) throws Exception {
        final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(secret);
        sha1.update(SERVER_ID_SALT);
        return new BigInteger(sha1.digest()).toString(16);
    }

    private static byte[] hex(String s) {
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

}
