package com.ootruffle.clienttag;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which players a client has answered for on this server, so {@code /clienttag who} knows
 * when it can reply (see {@link WhoCommand}). Some clients only ever answer for their own
 * users; for those, no answer {@code settleMs} after asking counts as "not on it".
 * Safe to use from any thread.
 */
public final class Answers {

    private final long settleMs;
    private final Map<UUID, Long> asked = new ConcurrentHashMap<>();
    private final Set<UUID> answered = ConcurrentHashMap.newKeySet();

    /** {@code settleMs} 0: only an answer counts. */
    public Answers(long settleMs) {
        this.settleMs = settleMs;
    }

    /** Whether the client has said if this player is on it. */
    public boolean has(UUID uuid) {
        if (answered.contains(uuid)) {
            return true;
        }
        final Long since = asked.get(uuid);
        return settleMs > 0 && since != null && System.currentTimeMillis() - since >= settleMs;
    }

    public void asked(Collection<UUID> players) {
        final long now = System.currentTimeMillis();
        for (UUID uuid : players) {
            asked.putIfAbsent(uuid, now);
        }
    }

    public void answered(UUID uuid) {
        answered.add(uuid);
    }

    public void answered(Collection<UUID> players) {
        answered.addAll(players);
    }

    /** Forgets players the client stopped watching, so they're asked about afresh. */
    public void forget(Collection<UUID> players) {
        for (UUID uuid : players) {
            asked.remove(uuid);
            answered.remove(uuid);
        }
    }

    public void retain(Set<UUID> players) {
        asked.keySet().retainAll(players);
        answered.retainAll(players);
    }

    public void clear() {
        asked.clear();
        answered.clear();
    }

}
