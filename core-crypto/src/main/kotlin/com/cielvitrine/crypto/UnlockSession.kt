package com.cielvitrine.crypto

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * PRIV-007: Unlock-session generation and lock cleanup.
 * On lock, outstanding private requests are cancelled, decoded caches cleared,
 * and any asynchronous result whose session generation is stale is rejected.
 */
data class UnlockSession(
    val sessionId: String,
    val generation: Long,
    val vaultRoot: VaultRootSecret
)

class UnlockSessionManager {
    private val generation = AtomicLong(0L)
    private val activeSession = AtomicReference<UnlockSession?>(null)

    fun unlock(vaultRoot: VaultRootSecret): UnlockSession {
        val newGen = generation.incrementAndGet()
        val session = UnlockSession(
            sessionId = UUID.randomUUID().toString(),
            generation = newGen,
            vaultRoot = vaultRoot
        )
        activeSession.set(session)
        return session
    }

    fun lock() {
        generation.incrementAndGet()
        activeSession.set(null)
    }

    fun isUnlocked(): Boolean = activeSession.get() != null

    fun getActiveSession(): UnlockSession? = activeSession.get()

    fun isSessionValid(session: UnlockSession): Boolean {
        val current = activeSession.get() ?: return false
        return current.sessionId == session.sessionId && current.generation == session.generation
    }

    fun <T> executeInSession(session: UnlockSession, block: (VaultRootSecret) -> T): T {
        if (!isSessionValid(session)) {
            throw StaleSessionException("Session ${session.sessionId} (gen ${session.generation}) is stale or locked")
        }
        val result = block(session.vaultRoot)
        if (!isSessionValid(session)) {
            throw StaleSessionException("Session locked during operation execution")
        }
        return result
    }
}

class StaleSessionException(message: String) : IllegalStateException(message)
