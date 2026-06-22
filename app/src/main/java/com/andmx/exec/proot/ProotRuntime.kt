package com.andmx.exec.proot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the on-device proot installation: locates the bundled bootstrap
 * binaries (proot + loader + libtalloc) in [nativeLibraryDir] — the only
 * directory Android reliably allows exec from (even on API 37+ where W^X
 * blocks exec from the app data dir).
 *
 * The binaries are packaged as jniLibs (libproot.so / libproot_loader.so /
 * libtalloc.so) so AGP extracts them to nativeLibraryDir on install with
 * `useLegacyPackaging = true`.
 */
class ProotRuntime(private val context: Context) {

    /**
     * nativeLibraryDir — where AGP extracts .so files. This is the only path
     * the kernel allows exec from on modern Android (W^X enforcement).
     */
    private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    /** tmp/ is proot's scratch space (stays in the writable data dir). */
    val tmpDir: File get() = File(context.filesDir, "tmp").also { it.mkdirs() }
    val rootfsDir: File get() = File(context.filesDir, "rootfs")

    /** proot binary lives in nativeLibraryDir as libproot.so. */
    val prootBin: File get() = File(nativeLibDir, "libproot.so")
    /** proot loader lives in nativeLibraryDir as libproot_loader.so. */
    val loaderBin: File get() = File(nativeLibDir, "libproot_loader.so")
    /** libtalloc lives in nativeLibraryDir as libtalloc.so. */
    val tallocLib: File get() = File(nativeLibDir, "libtalloc.so")

    // Keep usrDir pointing at nativeLibDir for backward-compat with env()/LD_LIBRARY_PATH
    val usrDir: File get() = nativeLibDir

    /** True if the proot binary is present in nativeLibraryDir. */
    fun isBundled(): Boolean = prootBin.exists() && loaderBin.exists()

    /**
     * No-op now that binaries are in nativeLibraryDir (AGP extracts them at
     * install time). Creates the libtalloc.so.2 symlink needed by proot in the
     * writable data dir, since AGP only accepts `.so` suffix in jniLibs but
     * proot's ELF links against `libtalloc.so.2`.
     */
    suspend fun install(force: Boolean = false): InstallResult = withContext(Dispatchers.IO) {
        if (!isBundled()) {
            InstallResult(false, "proot 二进制未在 nativeLibraryDir 中找到")
        } else {
            tmpDir.mkdirs()
            // Create libtalloc.so.2 symlink/copy in the data dir so LD can
            // resolve it (nativeLibDir is read-only).
            val tallocLink = File(context.filesDir, "libtalloc.so.2")
            if (!tallocLink.exists()) {
                runCatching { tallocLink.delete() }
                // Try symlink first; if it fails (some devices), copy the file.
                val linked = runCatching {
                    @Suppress("UnsafeNewApiCall")
                    java.nio.file.Files.createSymbolicLink(
                        tallocLink.toPath(),
                        tallocLib.toPath(),
                    )
                }.isSuccess
                if (!linked) {
                    tallocLib.copyTo(tallocLink, overwrite = true)
                    tallocLink.setExecutable(true, false)
                }
            }
            // LD_LIBRARY_PATH must include both nativeLibDir (for proot/loader)
            // and filesDir (for libtalloc.so.2).
            InstallResult(true, "proot 就绪 (${prootBin.path})")
        }
    }

    /** Environment proot needs to find its loader and libtalloc. */
    fun env(): Map<String, String> = mapOf(
        "PROOT_LOADER" to loaderBin.path,
        "PROOT_TMP_DIR" to tmpDir.path,
        // Include both nativeLibDir (proot/loader/libtalloc.so) and filesDir
        // (libtalloc.so.2 symlink) so the dynamic linker resolves everything.
        "LD_LIBRARY_PATH" to "${nativeLibDir.path}:${context.filesDir.path}",
        "HOME" to (rootfsDir.takeIf { it.exists() }?.let { "/root" } ?: "/"),
        "PATH" to "/usr/bin:/bin:/system/bin:/system/xbin",
        "TERM" to "xterm-256color",
    )

    /**
     * Build a proot argv. When [rootfs] is null, proot runs against the host
     * filesystem ("-r /") which is enough to validate proot itself; once a
     * guest distro is installed, [rootfs] points at it.
     *
     * If [extraBind] is set (host path → guest path), it's added as a proot
     * bind mount so the guest can see a phone-side directory. This is how the
     * user's chosen project directory (e.g. /sdcard/Documents/my-app) is mapped
     * into the guest at /root/project.
     */
    fun prootArgv(
        command: List<String>,
        rootfs: File? = null,
        fakeRoot: Boolean = true,
    ): List<String> = buildList {
        add(prootBin.path)
        if (fakeRoot) add("-0")
        add("-r"); add((rootfs ?: File("/")).path)
        // Kernel interfaces every guest needs.
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        // In host mode the guest *is* Android, so expose its runtime too.
        if (rootfs == null) {
            add("-b"); add("/system")
            add("-b"); add("/apex")
        } else {
            // Working dir inside the guest.
            add("-w"); add("/root")
            // Bind-mount the user's chosen project directory (phone storage)
            // into the guest so the agent operates on real files.
            extraBind?.let { (host, guest) ->
                add("-b"); add("$host:$guest")
            }
        }
        addAll(command)
    }

    companion object {
        /**
         * Process-level bind mount config: (hostPath, guestPath). Set by
         * [com.andmx.workspace.ProjectManager] when the user picks a project
         * directory. Read by every [prootArgv] call so all guest processes
         * (shell, terminal, git) see the user's real project files.
         */
        @Volatile
        var extraBind: Pair<String, String>? = null
    }
}

data class InstallResult(val ok: Boolean, val message: String)
