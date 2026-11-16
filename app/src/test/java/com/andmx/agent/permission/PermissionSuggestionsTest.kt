package com.andmx.agent.permission

import com.andmx.agent.ApprovalRuleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSuggestionsTest {

    private fun keys(suggestions: List<PermissionSuggestion>): List<String> = suggestions.map { it.ruleKey }

    @Test
    fun commandYieldsFirstWordPrefix() {
        val suggestions = PermissionSuggestions.suggest("Bash", mapOf("command" to "ls -la /tmp"))
        assertEquals(listOf("shell:prefix:ls"), keys(suggestions))
        assertEquals("ls:*", suggestions.single().ruleContent)
        assertEquals("Bash", suggestions.single().wireToolName)
    }

    @Test
    fun npmRunYieldsSubcommandPrefix() {
        val suggestions = PermissionSuggestions.suggest("Bash", mapOf("command" to "npm run build --watch"))
        assertEquals(listOf("shell:prefix:npm", "shell:prefix:npm run"), keys(suggestions))
        assertEquals("npm run:*", suggestions.last().ruleContent)
    }

    @Test
    fun gitSafeSubcommandIsSuggestedButDestructiveIsNot() {
        val status = PermissionSuggestions.suggest("Bash", mapOf("command" to "git status --short"))
        assertEquals(listOf("shell:prefix:git", "shell:prefix:git status"), keys(status))

        val push = PermissionSuggestions.suggest("Bash", mapOf("command" to "git push --force origin main"))
        assertEquals(listOf("shell:prefix:git"), keys(push))
    }

    @Test
    fun dangerousCommandsGetNoSuggestion() {
        assertTrue(PermissionSuggestions.suggest("Bash", mapOf("command" to "rm -rf build")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Bash", mapOf("command" to "sudo apt install x")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Bash", mapOf("command" to "dd if=/dev/zero of=/dev/sda")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Bash", mapOf("command" to "kill -9 123")).isEmpty())
    }

    @Test
    fun compoundOrRedirectedCommandsGetNoSuggestion() {
        assertTrue(PermissionSuggestions.suggest("Bash", mapOf("command" to "npm run build && rm -rf /")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Bash", mapOf("command" to "cat a | sh")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Bash", mapOf("command" to "echo hi > out.txt")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Bash", mapOf("command" to "echo $(rm x)")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Bash", mapOf("command" to "ls\nrm x")).isEmpty())
    }

    @Test
    fun envAssignmentsAndPathsAreStrippedFromHead() {
        val suggestions = PermissionSuggestions.suggest("Bash", mapOf("command" to "FOO=bar /usr/bin/npm run test"))
        assertEquals(listOf("shell:prefix:npm", "shell:prefix:npm run"), keys(suggestions))
    }

    @Test
    fun filePathYieldsDirectoryWildcard() {
        val suggestions = PermissionSuggestions.suggest("Edit", mapOf("file_path" to "/repo/src/main/App.kt"))
        assertEquals(listOf("edit:file:/repo/src/main/*"), keys(suggestions))
        assertEquals("/repo/src/main/*", suggestions.single().ruleContent)
        assertTrue(
            ApprovalRuleStore.matchContent("/repo/src/main/Other.kt", suggestions.single().ruleContent!!),
        )
    }

    @Test
    fun rootAndRelativeParentDirectoriesAreExcluded() {
        assertTrue(PermissionSuggestions.suggest("Write", mapOf("file_path" to "/file.txt")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Write", mapOf("file_path" to "file.txt")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Edit", mapOf("file_path" to "../escape/x.kt")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Read", mapOf("file_path" to "C:/x.txt")).isEmpty())
    }

    @Test
    fun urlYieldsDomainRuleMatchingPermissionSubjects() {
        val suggestions = PermissionSuggestions.suggest("WebFetch", mapOf("url" to "https://docs.example.com/guide?a=1"))
        assertEquals(listOf("webfetch:domain:docs.example.com"), keys(suggestions))
        assertEquals("domain:docs.example.com", suggestions.single().ruleContent)
        assertEquals(
            "domain:docs.example.com",
            WebFetchPreapproved.domainSubject("https://docs.example.com/other"),
        )
        assertTrue(PermissionSuggestions.suggest("WebFetch", mapOf("url" to "not a url")).isEmpty())
    }

    @Test
    fun duplicatesAreDeduplicated() {
        val suggestions = PermissionSuggestions.suggest("Bash", mapOf("command" to "npm npm"))
        assertEquals(listOf("shell:prefix:npm"), keys(suggestions))
    }

    @Test
    fun unknownToolsAndBlankInputsYieldNothing() {
        assertTrue(PermissionSuggestions.suggest("TodoWrite", mapOf("todos" to "x")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Bash", mapOf("command" to "   ")).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Bash", null).isEmpty())
        assertTrue(PermissionSuggestions.suggest("Edit", mapOf("path" to "")).isEmpty())
    }

    @Test
    fun generatedRulesMatchTheirOwnSubject() {
        val command = "npm run build"
        val suggestions = PermissionSuggestions.suggest("Bash", mapOf("command" to command))
        assertTrue(
            suggestions.all { ApprovalRuleStore.matchContent(command, it.ruleContent!!) },
        )
        val service = PermissionService()
        suggestions.forEach { service.grantSessionAllow(it.wireToolName, it.ruleContent) }
        val decision = service.checkPermission(
            PermissionContext(
                toolName = "Bash",
                input = mapOf("command" to command),
                mode = CollaborationMode.BUILD,
            ),
            toolCapability = PermissionToolCapability(alwaysAsk = true),
        )
        assertEquals("allow", decision.decision)
    }
}