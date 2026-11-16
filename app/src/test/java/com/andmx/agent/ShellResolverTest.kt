package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellResolverTest {
    @Test
    fun prootWithRootfsUsesGuestShell() {
        assertEquals("/bin/sh", ShellResolver.resolve("proot", rootfsAvailable = true))
        assertEquals("/bin/sh", ShellResolver.resolve("local_proot", rootfsAvailable = true))
    }

    @Test
    fun prootWithoutRootfsFallsBackToSystemShell() {
        assertEquals("/system/bin/sh", ShellResolver.resolve("proot", rootfsAvailable = false))
        assertEquals("/system/bin/sh", ShellResolver.resolve("", rootfsAvailable = false))
        assertEquals("/system/bin/sh", ShellResolver.resolve("unknown-runtime", rootfsAvailable = false))
    }

    @Test
    fun remoteRuntimeAlwaysUsesGuestShell() {
        assertEquals("/bin/sh", ShellResolver.resolve("remote", rootfsAvailable = false))
        assertEquals("/bin/sh", ShellResolver.resolve("ssh", rootfsAvailable = false))
        assertEquals("/bin/sh", ShellResolver.resolve("remote_ssh", rootfsAvailable = true))
    }

    @Test
    fun hostRuntimeAlwaysUsesSystemShell() {
        assertEquals("/system/bin/sh", ShellResolver.resolve("host", rootfsAvailable = true))
        assertEquals("/system/bin/sh", ShellResolver.resolve("no_sandbox", rootfsAvailable = true))
        assertEquals("/system/bin/sh", ShellResolver.resolve("android", rootfsAvailable = true))
    }

    @Test
    fun runtimeStringIsTrimmedAndCaseInsensitive() {
        assertEquals("/bin/sh", ShellResolver.resolve(" Remote ", rootfsAvailable = false))
        assertEquals("/bin/sh", ShellResolver.resolve("PROOT", rootfsAvailable = true))
        assertEquals("/system/bin/sh", ShellResolver.resolve(" HOST ", rootfsAvailable = true))
    }
}