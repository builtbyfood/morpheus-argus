package com.morpheusdata.iloconsole.services

import groovy.util.logging.Slf4j

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-use nonces for the console launch flow.
 *
 * Why this exists: the iLO HTML5 console authenticates from a cookie set on
 * the iLO origin (not the Redfish X-Auth-Token), so the user's browser has to
 * POST credentials to the iLO directly. To avoid inlining the password in the
 * launch HTML — where it would be visible to View Source, cached as part of
 * the document, and replayable from the URL — we hand the browser a random
 * nonce, and only when the browser presents that nonce back to a separate
 * endpoint do we release the credential payload. The nonce is then consumed
 * (atomic, single-use) and the entry is deleted.
 *
 * Properties:
 *   - 256-bit nonce from SecureRandom
 *   - TTL: 15 seconds from issue
 *   - Single-use: atomic mark-consumed on retrieve
 *   - Background sweeper purges expired entries every 30 seconds
 */
@Slf4j
class LaunchNonceStore {

    private static final long TTL_MILLIS = 15_000L
    private static final long SWEEP_PERIOD_SECONDS = 30L

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>()
    private final SecureRandom rng = new SecureRandom()
    private final ScheduledExecutorService sweeper
    private final AtomicBoolean active = new AtomicBoolean(true)

    LaunchNonceStore() {
        this.sweeper = Executors.newSingleThreadScheduledExecutor({ r ->
            Thread t = new Thread(r, "iloConsole-nonce-sweeper")
            t.daemon = true
            return t
        } as java.util.concurrent.ThreadFactory)
        this.sweeper.scheduleAtFixedRate(this.&sweep, SWEEP_PERIOD_SECONDS, SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Issue a new nonce holding the supplied launch payload. Returns the nonce
     * string the caller should hand to the browser.
     */
    String issue(LaunchPayload payload) {
        if (!active.get()) throw new IllegalStateException("store shut down")
        String nonce = newNonce()
        entries.put(nonce, new Entry(
                payload: payload,
                expiresAt: System.currentTimeMillis() + TTL_MILLIS,
                consumed: new AtomicBoolean(false)
        ))
        return nonce
    }

    /**
     * Atomically consume a nonce. Returns the payload exactly once for any
     * given valid nonce; subsequent calls return null. Also returns null if
     * the nonce is expired or never existed.
     */
    LaunchPayload consume(String nonce) {
        if (nonce == null) return null
        Entry e = entries.get(nonce)
        if (e == null) return null
        if (System.currentTimeMillis() > e.expiresAt) {
            entries.remove(nonce)
            return null
        }
        if (!e.consumed.compareAndSet(false, true)) {
            // already consumed
            return null
        }
        // Remove on consumption so a leaked entry can't be re-read later.
        entries.remove(nonce)
        return e.payload
    }

    void shutdown() {
        active.set(false)
        try {
            sweeper.shutdownNow()
        } catch (Throwable ignored) {}
        entries.clear()
    }

    private void sweep() {
        long now = System.currentTimeMillis()
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator()
        int purged = 0
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next()
            if (now > e.value.expiresAt) {
                it.remove()
                purged++
            }
        }
        if (purged > 0) log.debug("Swept ${purged} expired launch nonces")
    }

    private String newNonce() {
        byte[] bytes = new byte[32]
        rng.nextBytes(bytes)
        // URL-safe base64 without padding
        return Base64.urlEncoder.withoutPadding().encodeToString(bytes)
    }

    static class LaunchPayload {
        Long serverId
        Long userId
        String iloHost
        String username
        String password
        boolean verifySsl
        Date issuedAt = new Date()
    }

    private static class Entry {
        LaunchPayload payload
        long expiresAt
        AtomicBoolean consumed
    }
}
