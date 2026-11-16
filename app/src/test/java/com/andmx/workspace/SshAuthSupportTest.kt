package com.andmx.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SshAuthSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun spec(
        auth: SshAuthMethod,
        password: String = "pw",
        privateKeyPath: String = "",
    ) = RemoteWorkspaceSpec(
        host = "example.invalid",
        username = "user",
        authMethod = auth.name,
        password = password,
        privateKeyPath = privateKeyPath,
    )

    @Test
    fun askpassScriptPrintsPasswordVerbatim() {
        val content = SshAuthSupport.askpassScriptContent("simple-pw")
        assertTrue(content.startsWith("#!/system/bin/sh\n"))
        assertTrue(content.contains("'simple-pw'"))
        assertTrue(content.endsWith("\n"))
    }

    @Test
    fun askpassScriptEscapesSingleQuotes() {
        val content = SshAuthSupport.askpassScriptContent("a'b")
        assertTrue(content.contains("'a'\\''b'"))
    }

    @Test
    fun askpassScriptRoundTripsPasswordThroughShell() {
        val password = "p'\"$ `\\a b"
        val script = SshAuthSupport.writeAskpassScript(tmp.newFolder("askpass"), password)
        try {
            val proc = ProcessBuilder("sh", script.absolutePath).start()
            val out = proc.inputStream.bufferedReader().readText()
            assertEquals(0, proc.waitFor())
            assertEquals(password + "\n", out)
        } finally {
            script.delete()
        }
        assertFalse(script.exists())
    }

    @Test
    fun askpassScriptIsOwnerOnlyAndDeletable() {
        val dir = tmp.newFolder("askpass-perm")
        val script = SshAuthSupport.writeAskpassScript(dir, "pw")
        assertTrue(script.canRead())
        assertTrue(script.canExecute())
        assertTrue(script.delete())
        assertFalse(script.exists())
    }

    @Test
    fun askpassEnvSetsRequiredVariables() {
        val dir = tmp.newFolder("askpass-env")
        val script = SshAuthSupport.writeAskpassScript(dir, "pw")
        try {
            val env = SshAuthSupport.askpassEnv(script)
            assertEquals(script.absolutePath, env["SSH_ASKPASS"])
            assertEquals("force", env["SSH_ASKPASS_REQUIRE"])
            assertEquals(":0", env["DISPLAY"])
        } finally {
            script.delete()
        }
    }

    @Test
    fun discoversDefaultPrivateKeySkippingPublicKeys() {
        val sshDir = tmp.newFolder(".ssh")
        File(sshDir, "id_ed25519.pub").writeText("pub")
        assertNull(SshAuthSupport.discoverPrivateKey(listOf(sshDir)))
        val key = File(sshDir, "id_ed25519")
        key.writeText("key")
        assertEquals(key.absolutePath, SshAuthSupport.discoverPrivateKey(listOf(sshDir)))
    }

    @Test
    fun discoversKeyAcrossDirectoriesInOrder() {
        val first = tmp.newFolder("first")
        val second = tmp.newFolder("second")
        val key = File(second, "id_rsa")
        key.writeText("key")
        assertEquals(key.absolutePath, SshAuthSupport.discoverPrivateKey(listOf(first, second)))
    }

    @Test
    fun configuredKeyWinsOverDiscoveryAndUsesBatchMode() {
        val sshDir = tmp.newFolder(".ssh-keys")
        File(sshDir, "id_rsa").writeText("key")
        val plan = SshAuthSupport.resolveAuth(
            spec = spec(SshAuthMethod.PRIVATE_KEY, privateKeyPath = "/configured/key"),
            auth = SshAuthMethod.PRIVATE_KEY,
            sshpassPath = null,
            keyDirs = listOf(sshDir),
            askpassDir = tmp.newFolder("askpass-none"),
        )
        val line = plan.commandLine("/usr/bin/ssh", "user@host", "echo ok")
        assertTrue(line.containsAll(listOf("-i", "/configured/key")))
        assertTrue(line.containsAll(listOf("-o", "BatchMode=yes")))
        assertTrue(line.containsAll(listOf("-o", "ConnectTimeout=15")))
        assertTrue(line.containsAll(listOf("-o", "ServerAliveInterval=15")))
        assertTrue(line.containsAll(listOf("-o", "ServerAliveCountMax=3")))
        assertNull(plan.askpassScript)
    }

    @Test
    fun passwordPrefersSshpassWhenAvailable() {
        val plan = SshAuthSupport.resolveAuth(
            spec = spec(SshAuthMethod.PASSWORD),
            auth = SshAuthMethod.PASSWORD,
            sshpassPath = "/usr/bin/sshpass",
            keyDirs = emptyList(),
            askpassDir = tmp.newFolder("askpass-unused"),
        )
        val line = plan.commandLine("/usr/bin/ssh", "user@host", "echo ok")
        assertEquals("/usr/bin/sshpass", line.first())
        assertTrue(line.containsAll(listOf("-p", "pw")))
        assertNull(plan.askpassScript)
        assertTrue(plan.env.isEmpty())
    }

    @Test
    fun passwordFallsBackToAskpassWithoutSshpass() {
        val dir = tmp.newFolder("askpass-fallback")
        val plan = SshAuthSupport.resolveAuth(
            spec = spec(SshAuthMethod.PASSWORD, password = "se'cret"),
            auth = SshAuthMethod.PASSWORD,
            sshpassPath = null,
            keyDirs = emptyList(),
            askpassDir = dir,
        )
        val script = plan.askpassScript
        assertNotNull(script)
        assertTrue(script!!.exists())
        assertEquals(script.absolutePath, plan.env["SSH_ASKPASS"])
        val line = plan.commandLine("/usr/bin/ssh", "user@host", "echo ok")
        assertEquals("/usr/bin/ssh", line.first())
        assertFalse(line.contains("BatchMode=yes"))
        assertTrue(script.delete())
        assertFalse(script.exists())
    }

    @Test
    fun keyAuthWithoutAnyKeyStillUsesBatchMode() {
        val plan = SshAuthSupport.resolveAuth(
            spec = spec(SshAuthMethod.PRIVATE_KEY),
            auth = SshAuthMethod.PRIVATE_KEY,
            sshpassPath = null,
            keyDirs = listOf(tmp.newFolder("empty-ssh")),
            askpassDir = tmp.newFolder("askpass-unused2"),
        )
        val line = plan.commandLine("/usr/bin/ssh", "user@host", "echo ok")
        assertTrue(line.containsAll(listOf("-o", "BatchMode=yes")))
        assertFalse(line.contains("-i"))
    }
}
