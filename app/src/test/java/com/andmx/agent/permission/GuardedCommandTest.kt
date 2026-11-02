package com.andmx.agent.permission

import com.andmx.agent.permission.guarded.DangerousCommandResult
import com.andmx.agent.permission.guarded.OptionOrdering
import com.andmx.agent.permission.guarded.OptionScanPolicy
import com.andmx.agent.permission.guarded.ShellDialect
import com.andmx.agent.permission.guarded.UnknownOption
import com.andmx.agent.permission.guarded.analyzeCmdCommand
import com.andmx.agent.permission.guarded.literalPosixWord
import com.andmx.agent.permission.guarded.matchDangerousCommand
import com.andmx.agent.permission.guarded.optionTable
import com.andmx.agent.permission.guarded.parseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedCommandTest {
    private val shellDialects = listOf(ShellDialect.POSIX, ShellDialect.GIT_BASH, ShellDialect.CMD)
    private val posixLike = listOf(ShellDialect.POSIX, ShellDialect.GIT_BASH)

    @Test
    fun recognizesForceRefspecs() {
        val commands = listOf(
            "git push origin +refs/heads/*:refs/heads/*",
            "git push origin \"+refs/heads/*:refs/heads/*\"",
            "git push origin \"+main\"",
            "git push --force origin \"refs/heads/*:refs/heads/*\"",
            "git push --repo origin +refs/heads/*:refs/heads/*",
        )
        for (dialect in shellDialects) {
            for (command in commands) assertMatched(command, dialect, "git-push-force")
        }
    }

    @Test
    fun preservesForceRefspecBoundaries() {
        val commands = listOf(
            "git push --dry-run origin +refs/heads/*:refs/heads/*",
            "git push -n origin +refs/heads/*:refs/heads/*",
            "git push origin refs/heads/*:refs/heads/*",
            "git push --push-option \"+refs/heads/*:refs/heads/*\" origin main",
            "git push --repo \"+refs/heads/*:refs/heads/*\" main",
            "git push \"+refs/heads/*:refs/heads/*\" main",
        )
        for (dialect in shellDialects) {
            for (command in commands) assertNotDangerous(command, dialect)
        }
    }

    @Test
    fun matchesFiniteCommandCatalog() {
        val catalog = listOf(
            "remove-critical-path" to listOf(
                "rm -rf build",
                "rm -f -R build",
                "rm --recursive --force build",
                "rm -rf \"\$target\"",
                "rm -rf -- -i",
                "rm -rfi build",
            ),
            "system-storage" to listOf(
                "dd if=in of=out",
                "mount /dev/disk /mnt",
                "umount /mnt",
                "mkfs /dev/disk",
                "mkfs.ext4 /dev/disk",
            ),
            "git-reset-hard" to listOf(
                "git reset --hard",
                "git -C repo reset --hard HEAD",
                "git --git-dir=repo reset --hard",
            ),
            "git-clean-force" to listOf(
                "git clean -fd",
                "git clean -dfx",
                "git clean -f file",
                "git clean -f -- --dry-run",
            ),
            "git-push-force" to listOf(
                "git push -f origin main",
                "git push --force-with-lease",
                "git push --mirror",
                "git push origin +HEAD:main",
                "git push --force origin 'refs/heads/*:refs/heads/*'",
                "git push --force --no-dry-run",
                "git push --force-with-lease --no-force",
            ),
            "git-discard-tree" to listOf(
                "git checkout -f main",
                "git checkout HEAD -- .",
                "git restore .",
                "git restore --staged --worktree ./",
                "git restore --source=HEAD :/",
            ),
            "git-stash-clear" to listOf("git stash clear"),
            "git-worktree-force-remove" to listOf(
                "git worktree remove -ff path",
                "git worktree remove --force path",
            ),
            "rsync-delete" to listOf(
                "rsync -a --delete src/ dest/",
                "rsync --del src/ dest/",
                "rsync --delete-before src/ dest/",
                "rsync --delete-during src/ dest/",
                "rsync --delete-delay src/ dest/",
                "rsync --delete-after src/ dest/",
                "rsync --delete-excluded src/ dest/",
            ),
        )
        for ((rule, commands) in catalog) {
            for (command in commands) assertMatched(command, ShellDialect.POSIX, rule)
        }
    }

    @Test
    fun recognizesReliableCommandNodes() {
        val commands = listOf(
            "env A=b rm -rf build",
            "env -u A rm -rf build",
            "time -o file rm -rf build",
            "/usr/bin/time --output=file rm -rf build",
            "sudo -u root rm -rf build",
            "env -C repo time -f '%e' sudo --user=root rm -rf build",
            "echo hello; rm -rf build",
            "false || rm -rf build",
            "true && rm -rf build",
            "echo hi | rm -rf build",
            "\"/bin/rm\" -rf build",
            "r\\m -rf build",
            "for x in a; do echo hi; done; rm -rf build",
        )
        for (command in commands) assertStatus(command, ShellDialect.POSIX, "matched")
    }

    @Test
    fun doesNotInventADestructiveEffect() {
        val commands = listOf(
            "rm -f file",
            "rm -- -rf",
            "rm --help",
            "rm --version",
            "mount",
            "dd --help",
            "mount --version",
            "mkfs --help",
            "git reset --soft HEAD",
            "git reset -- --hard",
            "git clean -nfd",
            "git clean -fd --dry-run",
            "git clean -fd -e --dry-run --dry-run",
            "git push -nf",
            "git push --force --no-force",
            "git push --force-if-includes",
            "git push --force-with-lease --no-force-with-lease",
            "git push --mirror --no-mirror",
            "git push --repo +not-a-refspec",
            "git push --push-option=--force",
            "git restore --staged .",
            "git restore file",
            "git checkout -b branch",
            "git checkout -b .",
            "git stash drop",
            "git worktree remove path",
            "rsync --delete -n src/ dest/",
            "rsync --exclude --delete src/ dest/",
            "rsync --filter=--delete src/ dest/",
            "echo 'rm -rf build'",
            "echo hi # rm -rf build",
            "echo hi > 'rm -rf build'",
            "time -o 'rm -rf build' echo ok",
            "sudo -u rm echo -rf build",
            "env A='rm -rf build' echo ok",
        )
        for (command in commands) assertStatus(command, ShellDialect.POSIX, "notMatched")
    }

    @Test
    fun keepsExemptionWordsDistinctFromValues() {
        val rows = listOf(
            Triple("git clean -fd -e --dry-run", "git clean -fd --dry-run", "git-clean-force"),
            Triple("git clean -nfd --no-dry-run", "git clean -fd --no-dry-run -n", "git-clean-force"),
            Triple("git push -f --push-option --dry-run", "git push -f --dry-run", "git-push-force"),
            Triple("git push -nf --no-dry-run", "git push -f --no-dry-run -n", "git-push-force"),
            Triple("git push --mirror --no-force", "git push --mirror --no-mirror", "git-push-force"),
            Triple("rsync --delete --exclude --help src/ dest/", "rsync --delete --help", "rsync-delete"),
            Triple("rsync --delete --filter --dry-run src/ dest/", "rsync --delete -n src/ dest/", "rsync-delete"),
        )
        for ((dangerous, exempt, rule) in rows) {
            assertMatched(dangerous, ShellDialect.POSIX, rule)
            assertEquals(exempt, "notMatched", matchDangerousCommand(exempt, ShellDialect.POSIX).status)
        }
    }

    @Test
    fun labelsUnsupportedBoundaries() {
        val commands = listOf(
            "bash -c 'rm -rf build'",
            "powershell -Command 'rm -rf build'",
            "eval 'rm -rf build'",
            "\$exe -rf build",
            "git -c alias.wipe='reset --hard' wipe",
            "env -S 'rm -rf build'",
            "time --unknown rm -rf build",
            "for x in a; do rm -rf build; done",
            "rm 'unterminated",
            "x".repeat(10_001),
        )
        for (command in commands) assertStatus(command, ShellDialect.POSIX, "unsupported")
    }

    @Test
    fun keepsRecognizedDangerousOptionsWhenUnknownAppears() {
        val rows = listOf(
            "rm -rfx build" to "remove-critical-path",
            "rm -rfP build" to "remove-critical-path",
            "rm -rf -W build" to "remove-critical-path",
            "rm -rf --preserve-root=all build" to "remove-critical-path",
            "rm -rf --totally-unknown build" to "remove-critical-path",
            "rsync -a --delete --info=progress2 src/ dest/" to "rsync-delete",
            "rsync -a --delete --stats src/ dest/" to "rsync-delete",
            "rsync -ai --delete src/ dest/" to "rsync-delete",
            "rsync -a --delete --partial-dir=.rsync-partial src/ dest/" to "rsync-delete",
            "rsync --unknown-flag --delete src/ dest/" to "rsync-delete",
            "git push --force --no-verify origin main" to "git-push-force",
            "git push --force --no-recurse-submodules origin main" to "git-push-force",
            "git push --force --totally-unknown origin main" to "git-push-force",
            "git -c core.pager=cat reset --hard HEAD~1" to "git-reset-hard",
            "git -p reset --hard" to "git-reset-hard",
            "git --exec-path=/opt/git reset --hard" to "git-reset-hard",
            "git reset --hard --totally-unknown" to "git-reset-hard",
            "git checkout -f -t origin/main" to "git-discard-tree",
            "git checkout -f --guess main" to "git-discard-tree",
            "git stash clear --totally-unknown" to "git-stash-clear",
            "git worktree remove --force --totally-unknown path" to "git-worktree-force-remove",
        )
        for ((command, rule) in rows) assertMatched(command, ShellDialect.POSIX, rule)
    }

    @Test
    fun reportsUnsupportedWhenUnknownOptionsLeaveNoHit() {
        val commands = listOf(
            "rm --totally-unknown build",
            "rsync --unknown-flag src/ dest/",
            "git push --totally-unknown origin main",
            "git reset --totally-unknown",
        )
        for (command in commands) assertStatus(command, ShellDialect.POSIX, "unsupported")
    }

    @Test
    fun unknownInterspersedOptionsDoNotConsumeTheNextWord() {
        val words = analyzeBashCommand("rm --unknown -rf target").commands[0].words.drop(1)
        val parsed = parseOptions(
            words,
            optionTable("-r -f"),
            OptionScanPolicy(OptionOrdering.INTERSPERSED, UnknownOption.CONTINUE),
        )
        assertTrue(parsed.unsupported)
        assertEquals(listOf("-r", "-f"), parsed.flags)
        assertEquals(listOf("target"), parsed.operands.map { it.value })
    }

    @Test
    fun cmdTrailingRedirectionKeepsTheCommandMatched() {
        val commands = listOf(
            "git reset --hard 2>nul",
            "git reset --hard > out.txt",
            "git reset --hard >> out.txt",
            "git push --force origin main 2>nul",
            "rm -rf build 2>nul",
            "rm -rf build 2> nul",
        )
        for (command in commands) assertStatus(command, ShellDialect.CMD, "matched")
    }

    @Test
    fun cmdRedirectionWithoutATargetStaysUnsupported() {
        for (command in listOf("git reset --hard >", "git reset --hard 2>", "git reset --hard > ")) {
            assertStatus(command, ShellDialect.CMD, "unsupported")
        }
    }

    @Test
    fun cmdRecognizesQuotedGitAndSeparators() {
        val commands = listOf(
            "rm -rf build",
            "\"C:\\tools\\git.exe\" reset --hard",
            "echo ok & git stash clear",
            "git clean -fd && echo ok",
        )
        for (command in commands) assertStatus(command, ShellDialect.CMD, "matched")
    }

    @Test
    fun cmdRedirectionTargetIsNotARemComment() {
        for (redirection in listOf(">rem", "> rem", "2>rem", ">>rem", "<rem", ">\"rem\"")) {
            assertMatched("$redirection git reset --hard", ShellDialect.CMD, "git-reset-hard")
        }
    }

    @Test
    fun cmdPreservesActualRemComments() {
        val commands = listOf(
            "rem git reset --hard",
            ">rem rem git reset --hard",
            ">rem rem ignored & git reset --hard",
        )
        for (command in commands) {
            assertEquals(command, "notMatched", matchDangerousCommand(command, ShellDialect.CMD).status)
        }
    }

    @Test
    fun cmdQuotesAndCommentsAreNotDangerous() {
        val commands = listOf(
            "rem rm -rf build",
            ":: rm -rf build",
            "echo \"rm -rf build\"",
            "'rm' -rf build",
            "git reset '--hard'",
            "(echo hi & (rm -rf build))",
        )
        for (command in commands) assertNotDangerous(command, ShellDialect.CMD)
    }

    @Test
    fun legacyShellIsUnsupported() {
        assertStatus("rm -rf build", ShellDialect.LEGACY_SHELL, "unsupported")
    }

    @Test
    fun rmRecursiveForceIgnoresHelpPositionAndWrappers() {
        val flags = listOf("-rf", "-f -R", "--recursive --force")
        val queries = listOf("--help", "--version")
        val wrappers = listOf("", "env A=b ", "time -o timing.txt ", "sudo -u root ")
        for (dialect in shellDialects) {
            for (flag in flags) {
                for (query in queries) {
                    val forms = listOf(
                        "$flag target $query",
                        "$query $flag target",
                        "$flag $query",
                        "$flag -- $query",
                    )
                    for (args in forms) {
                        for (wrapper in wrappers) {
                            assertMatched("${wrapper}rm $args", dialect, "remove-critical-path")
                        }
                    }
                }
            }
            for (command in listOf("rm --help", "rm -- -rf")) {
                assertEquals(command, "notMatched", matchDangerousCommand(command, dialect).status)
            }
        }
    }

    @Test
    fun operandScanningIsCallerOwned() {
        val words = analyzeBashCommand("rm -rf target --help").commands[0].words.drop(1)
        val table = optionTable("-r -f --help")
        val stopped = parseOptions(words, table, OptionScanPolicy(OptionOrdering.STOP_AT_OPERAND, UnknownOption.ABORT))
        assertEquals(listOf("-r", "-f"), stopped.flags)
        assertEquals(listOf("target", "--help"), stopped.operands.map { it.value })
        val interspersed = parseOptions(
            words,
            table,
            OptionScanPolicy(OptionOrdering.INTERSPERSED, UnknownOption.CONTINUE),
        )
        assertEquals(listOf("-r", "-f", "--help"), interspersed.flags)
        assertEquals(listOf("target"), interspersed.operands.map { it.value })
    }

    @Test
    fun abortPolicyStopsAtTheFirstUnknownOption() {
        val words = analyzeBashCommand("time --unknown rm -rf target").commands[0].words.drop(1)
        val parsed = parseOptions(
            words,
            optionTable("-p"),
            OptionScanPolicy(OptionOrdering.STOP_AT_OPERAND, UnknownOption.ABORT),
        )
        assertTrue(parsed.unsupported)
        assertEquals(emptyList<String>(), parsed.flags)
    }

    @Test
    fun trailingHelpStillMatchesWithoutNativeRm() {
        assertStatus("rm -rf target --help --version", ShellDialect.POSIX, "matched")
    }

    @Test
    fun countsBsdRmTrailingOptionShapedFilenames() {
        for (command in listOf("rm first --help", "rm first --version", "rm first -f", "rm first --")) {
            assertMatched(command, ShellDialect.POSIX, "remove-critical-path")
        }
    }

    @Test
    fun recursiveAndBatchRmOnPosixAndGitBash() {
        val matched = listOf(
            "rm -r dir",
            "rm -R dir",
            "rm --recursive dir",
            "rm -ri dir",
            "rm -r --help",
            "rm a b",
            "rm \"one file\" \"two files\"",
            "rm -- -r -f",
            "rm *.log",
            "rm file?.txt",
            "rm [ab].txt",
            "rm \"prefix\"*.log",
            "rm -f *.log",
            "rm -r --unknown dir",
            "env A=b rm -r dir",
            "time -o timing.txt rm a b",
            "sudo -u root rm *.log",
            "echo ready; rm a b",
            "true && rm -r dir",
            "false || rm *.log",
            "rm a b | cat",
            "rm a b > output",
            "rm a b # keep comment",
        )
        val safe = listOf(
            "rm one",
            "rm -f one",
            "rm \"one file\"",
            "rm -- -r",
            "rm --help",
            "rm \"*.log\"",
            "rm '*.log'",
            "rm \\*.log",
            "rm file\\?.txt",
            "rm \\[ab].txt",
            "rm \$TARGET",
            "rm \"\$TARGET\"",
            "rm {a,b}",
            "rm \$(echo target)",
            "rm \$(echo *)",
            "rm \${TARGET:-*}",
            "echo \"rm a b\"",
            "echo ok # rm a b",
            "echo ok > rm",
            "rmdir empty",
            "rmdir /s folder",
            "del file",
            "xargs rm -r",
            "npx rimraf dir",
            "sh -c 'rm -r dir'",
        )
        for (dialect in posixLike) {
            for (command in matched) assertMatched(command, dialect, "remove-critical-path")
            for (command in safe) assertNotDangerous(command, dialect)
        }
    }

    @Test
    fun bsdFindKeepsTheStartingPathPhase() {
        for (flags in listOf("-d", "-x", "-d -x")) {
            for (command in listOf(
                "find $flags . -type f -delete",
                "find $flags \"space dir\" another -delete",
                "time -o timing.txt find $flags . -delete",
            )) assertMatched(command, ShellDialect.POSIX, "find-delete")
            for (command in listOf(
                "find $flags . -type f -print",
                "find $flags . -name '-delete'",
                "find $flags . -exec echo -delete \\;",
            )) assertEquals(command, "notMatched", matchDangerousCommand(command, ShellDialect.POSIX).status)
            assertEquals(
                "find $flags . -unknown -delete",
                "unsupported",
                matchDangerousCommand("find $flags . -unknown -delete", ShellDialect.POSIX).status,
            )
        }
    }

    @Test
    fun findActionArity() {
        val matched = listOf(
            "find . -delete",
            "find . -type f -name '*.log' -delete",
            "find . \\( -name a -o -name b \\) -delete",
            "find . -false -a -delete",
            "find . -exec echo {} \\; -delete",
            "find . -execdir echo {} + -delete",
            "find . -ok echo {} \\; -delete",
            "find . -okdir echo {} \\; -delete",
            "find -L . -maxdepth 2 -mtime +1 -delete",
            "find . -delete -unknown value",
            "time -o file find . -delete",
            "find . -unknown value; rm -r dir",
        )
        val safe = listOf(
            "find . -name '-delete'",
            "find . -path -delete",
            "find . -printf '-delete'",
            "find . -fprintf out '-delete'",
            "find . -fprint -delete",
            "find . -exec echo -delete \\;",
            "find . -exec rm -rf {} +",
            "find . -okdir echo -delete \\;",
            "find . -print",
            "find . -name '*.log'",
        )
        val unsupported = listOf(
            "find . -unknown -delete",
            "find . -name",
            "find . -exec echo -delete",
        )
        for (dialect in posixLike) {
            for (command in matched) assertStatus(command, dialect, "matched")
            for (command in safe) assertNotDangerous(command, dialect)
            for (command in unsupported) {
                assertEquals(command, "unsupported", matchDangerousCommand(command, dialect).status)
            }
        }
    }

    @Test
    fun cmdDeletionCatalog() {
        val rows = listOf(
            "rd /s folder" to "cmd-remove-tree",
            "RMDIR /S /Q \"space dir\"" to "cmd-remove-tree",
            "rmdir folder /s" to "cmd-remove-tree",
            "rd /s folder /?" to "cmd-remove-tree",
            "rd /s/q folder" to "cmd-remove-tree",
            "rmdir /Q/S folder" to "cmd-remove-tree",
            "rd target/s/q" to "cmd-remove-tree",
            "rd/s/q target" to "cmd-remove-tree",
            "RMDIR/Q/S \"space dir\"" to "cmd-remove-tree",
            "rd target^/s/q" to "cmd-remove-tree",
            "del/q target\\one.txt" to "cmd-delete-files",
            "erase/P file" to "cmd-delete-files",
            ">rem rd/s/q target 2>nul" to "cmd-remove-tree",
            "echo ready && rd target/s/q | more" to "cmd-remove-tree",
            "del file" to "cmd-delete-files",
            "ERASE /P \"space dir\"" to "cmd-delete-files",
            "del /s /q *.txt" to "cmd-delete-files",
            "erase dir /A:-R" to "cmd-delete-files",
            "del file /?" to "cmd-delete-files",
            ">rem rd /s dir" to "cmd-remove-tree",
            "echo ready & del file" to "cmd-delete-files",
            "echo ready && rd /s dir" to "cmd-remove-tree",
            "del file 2>nul" to "cmd-delete-files",
            "rd /s dir | more" to "cmd-remove-tree",
        )
        for ((command, rule) in rows) assertMatched(command, ShellDialect.CMD, rule)
    }

    @Test
    fun cmdNonDeletion() {
        val commands = listOf(
            "rd dir",
            "rmdir /?",
            "rd /?",
            "del",
            "del /q /s",
            "del /?",
            "erase /?",
            "rem del file",
            "echo \"rd /s dir\"",
            "find file -delete",
            "rd \"target/s/q\"",
            "rd target > output/s/q",
            "rd/?",
            "del/q",
            "del/?",
            "echo rd/s/q target",
            "rem rd/s/q target",
        )
        for (command in commands) assertNotDangerous(command, ShellDialect.CMD)
    }

    @Test
    fun cmdBuiltinSwitchSplittingKeepsSourcePositions() {
        val command = "echo ready & rd/s/q target"
        val result = matchDangerousCommand(command, ShellDialect.CMD)
        assertTrue(result is DangerousCommandResult.Matched)
        val matched = result as DangerousCommandResult.Matched
        assertEquals(command.indexOf("rd/"), matched.start)
        assertEquals(command.length, matched.end)
        for (source in listOf("echo target/s/q", "C:/bin/tool.exe target/s/q", "git -C /s status")) {
            assertEquals(source.split(" "), analyzeCmdCommand(source).commands[0].map { it.value })
        }
        assertEquals(listOf("rd", "target/s/q"), analyzeCmdCommand("rd \"target/s/q\"").commands[0].map { it.value })
    }

    @Test
    fun gitBashRobocopyDrivePaths() {
        val matched = listOf(
            "robocopy /c/src /c/dst /MIR",
            "env MSYS2_ARG_CONV_EXCL=/MIR robocopy /c/src /c/dst /MIR",
            "robocopy \"/c/space src\" \"/d/space dst\" /PURGE",
            "robocopy /MIR /c/src /d/dst /R:0 /W:0",
        )
        val safe = listOf(
            "robocopy /c/src /c/dst /MIR /L",
            "robocopy /c/src /c/dst /PURGE /XD /c/excluded /L",
            "robocopy /c/src /c/dst /LOG:/MIR",
            "robocopy /c/src /MIR",
        )
        for (command in matched) assertMatched(command, ShellDialect.GIT_BASH, "robocopy-delete")
        for (command in safe) assertNotDangerous(command, ShellDialect.GIT_BASH)
        assertEquals(
            "unsupported",
            matchDangerousCommand("robocopy /unknown /c/src /c/dst /MIR", ShellDialect.GIT_BASH).status,
        )
        assertEquals(
            "unsupported",
            matchDangerousCommand("robocopy /JOB:job /c/src /c/dst /MIR", ShellDialect.GIT_BASH).status,
        )
        assertStatus("robocopy /c/src /c/dst /MIR", ShellDialect.CMD, "unsupported")
    }

    @Test
    fun robocopyOptionOwnership() {
        val matched = listOf(
            "robocopy src dst /MIR",
            "robocopy /MIR src dst",
            "robocopy src /PURGE dst",
            "ROBOCOPY.EXE src dst /purge",
            "robocopy \"space src\" \"space dst\" /E /PURGE",
            "robocopy src dst /MIR /LOG:out",
            "robocopy src dst /MIR /unknown /L",
            "robocopy src dst /MIR /JOB:job",
            "robocopy src dst /XF /MIR",
        )
        val safe = listOf(
            "robocopy src dst /E",
            "robocopy src dst /MIR /L",
            "robocopy src dst /l /PURGE",
            "robocopy src dst /LOG:/MIR",
            "robocopy src dst /MIR /XF /L",
            "robocopy src dst /MIR /XD /L",
            "robocopy /MIR dst",
            "robocopy src /PURGE",
            "robocopy /?",
            "robocopy src dst /JOB:job",
        )
        for (dialect in listOf(ShellDialect.CMD, ShellDialect.GIT_BASH)) {
            for (command in matched) assertMatched(command, dialect, "robocopy-delete")
            for (command in safe) assertNotDangerous(command, dialect)
            assertStatus("robocopy src dst /unknown /L /MIR", dialect, "unsupported")
        }
    }

    @Test
    fun posixSlashPathsAreNotWindowsSwitches() {
        assertStatus("robocopy src dst /MIR", ShellDialect.POSIX, "notMatched")
        assertStatus("rm /s", ShellDialect.POSIX, "notMatched")
    }

    @Test
    fun gitCleanEffectiveForce() {
        val matched = listOf(
            "git clean -f",
            "git clean -fx",
            "git clean -fX",
            "git clean --force",
            "git clean -nf --no-dry-run",
            "git clean -f -e --dry-run",
        )
        val safe = listOf(
            "git clean",
            "git clean -fn",
            "git clean -f --dry-run",
            "git clean -f --help",
            "git clean -n --no-dry-run -f -n",
        )
        for (dialect in shellDialects) {
            for (command in matched) assertMatched(command, dialect, "git-clean-force")
            for (command in safe) assertNotDangerous(command, dialect)
        }
    }

    @Test
    fun literalPosixWordFactsStaySeparate() {
        val rows = listOf(
            Triple("*.log", true, true),
            Triple("\"*.log\"", false, false),
            Triple("\\*.log", false, false),
            Triple("\$TARGET", true, false),
            Triple("{a,b}", true, false),
            Triple("file?.txt", true, true),
        )
        for ((source, dynamic, glob) in rows) {
            val word = literalPosixWord(source, 3, 3 + source.length)
            assertEquals(source, dynamic, word.dynamic)
            assertEquals(source, glob, word.hasUnquotedGlob)
            assertEquals(3, word.start)
            assertEquals(3 + source.length, word.end)
        }
    }

    @Test
    fun guardedRmSetsApprovalRuleAndGuardedLsStaysReadonly() {
        val base = PermissionToolCapability(
            readOnly = false,
            destructive = false,
            needsApproval = true,
            riskLevel = "high",
            sideEffectScope = "system",
            permissionName = "bash",
        )
        val rm = bashRuntimeCapability("rm -rf /", base, CollaborationMode.GUARDED)
        assertTrue(rm.userApprovalRule?.startsWith("safety.bash.") == true)
        assertEquals("safety.bash.remove-critical-path", rm.userApprovalRule)
        assertEquals(false, rm.destructive)
        assertEquals(base.copy(userApprovalRule = "safety.bash.remove-critical-path"), rm)
        val ls = bashRuntimeCapability("ls", base, CollaborationMode.GUARDED)
        assertEquals(null, ls.userApprovalRule)
        assertEquals(true, ls.readOnly)
        assertEquals(false, ls.destructive)
        assertEquals(false, ls.needsApproval)
        assertEquals("low", ls.riskLevel)
        assertEquals("none", ls.sideEffectScope)
        assertEquals(base, bashRuntimeCapability("rm -rf /", base))
        assertEquals(ShellDialect.POSIX, GUARDED_DEFAULT_DIALECT)
    }

    private fun assertMatched(command: String, dialect: ShellDialect, rule: String) {
        val result = matchDangerousCommand(command, dialect)
        assertEquals("$dialect $command", "matched", result.status)
        assertTrue("$dialect $command", result is DangerousCommandResult.Matched)
        assertEquals("$dialect $command", "safety.bash.$rule", (result as DangerousCommandResult.Matched).ruleId)
    }

    private fun assertStatus(command: String, dialect: ShellDialect, status: String) {
        assertEquals("$dialect $command", status, matchDangerousCommand(command, dialect).status)
    }

    private fun assertNotDangerous(command: String, dialect: ShellDialect) {
        assertNotEquals("$dialect $command", "matched", matchDangerousCommand(command, dialect).status)
    }
}
