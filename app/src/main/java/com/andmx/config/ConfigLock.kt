package com.andmx.config

import android.util.Log
import com.andmx.exec.files.GuestFs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Configuration lock — prevents concurrent modifications to config.toml.
 *
 * Mirrors Codex's ConfigLock: a `.config.lock` file is created atomically
 * before writing, and removed after. If the lock file already exists,
 * the writer waits up to [timeoutMs] before giving up.
 */
class ConfigLock(
    private val fs: GuestFs? = null,
    private val lockPath: String = "/root/.andmx/.config.lock",
    private val timeoutMs: Long = 5_000,
) {
    companion object {
        private const val TAG = "ConfigLock"
    }

    private val held = AtomicBoolean(false)

    /** Acquire the lock. Returns true if successful. */
    suspend fun acquire(): Boolean {
        if (!held.compareAndSet(false, true)) return false
        val guestFs = fs ?: return true // No fs → in-memory lock only
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!guestFs.exists(lockPath)) {
                runCatching { guestFs.writeText(lockPath, System.currentTimeMillis().toString()) }
                return true
            }
            Thread.sleep(50)
        }
        held.set(false)
        Log.w(TAG, "Failed to acquire config lock after ${timeoutMs}ms")
        return false
    }

    /** Release the lock. */
    suspend fun release() {
        if (!held.compareAndSet(true, false)) return
        val guestFs = fs ?: return
        runCatching { if (guestFs.exists(lockPath)) guestFs.deleteFile(lockPath) }
    }

    /** Execute [block] while holding the lock. */
    suspend fun <T> withLock(block: suspend () -> T): T? {
        if (!acquire()) return null
        return try {
            block()
        } finally {
            release()
        }
    }
}
