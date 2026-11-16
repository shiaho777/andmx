package com.andmx.agent

object ShellResolver {
    fun resolve(runtime: String, rootfsAvailable: Boolean): String = when (runtime.trim().lowercase()) {
        "remote", "ssh", "remote_ssh" -> "/bin/sh"
        "host", "android", "no_sandbox", "local" -> "/system/bin/sh"
        else -> if (rootfsAvailable) "/bin/sh" else "/system/bin/sh"
    }
}