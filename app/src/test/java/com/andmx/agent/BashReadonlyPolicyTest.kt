package com.andmx.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BashReadonlyPolicyTest {

    private fun ro(cmd: String) = BashReadonlyPolicy.isReadOnly(cmd)

    @Test
    fun plainReadCommandsPass() {
        for (cmd in listOf(
            "ls -la", "pwd", "cat file.txt", "grep -rn foo .", "find . -name '*.kt'",
            "git status", "git log --oneline -5", "git diff HEAD~1", "git branch -a",
            "git tag -l 'v*'", "git remote -v", "git config --get user.name",
            "git config user.email", "git stash list", "du -sh .", "head -20 a.md",
            "npm list", "pip show requests", "go version", "node --version",
            "docker ps", "kubectl get pods", "echo hello", "FOO=1 env", "getprop ro.build",
        )) assertTrue("expected readonly: $cmd", ro(cmd))
    }

    @Test
    fun pipelinesAndChainsRequireAllSegmentsReadonly() {
        assertTrue(ro("cat a.txt | grep x | wc -l"))
        assertTrue(ro("git status && ls"))
        assertFalse(ro("cat a.txt | tee out.txt"))
        assertFalse(ro("ls && rm x"))
        assertFalse(ro("ls; echo hi > f"))
    }

    @Test
    fun writeCommandsRejected() {
        for (cmd in listOf(
            "rm -rf /", "rm x", "mv a b", "cp a b", "mkdir d", "touch f",
            "echo hi > f", "echo hi >> f", "sed -i 's/a/b/' f", "sed -ni 'p' f",
            "find . -delete", "find . -exec rm {} ;", "awk 'system(\"rm x\")' f",
            "git checkout .", "git reset --hard", "git clean -fd", "git stash",
            "git stash pop", "git commit -m x", "git push", "git tag v1.0",
            "git branch -d x", "git branch new", "git remote add x y",
            "git config user.email a@b.c", "git config --global user.email x",
            "npm install", "npm publish", "pip install x", "cargo build",
            "docker rm x", "kubectl delete pod x", "sh -c 'ls'", "bash x.sh",
            "sudo ls", "curl example.com", "wget x", "ssh host ls", "tee f",
            "node -e 'x()'", "python -c 'x'", "logcat -c", "pm install x.apk",
            "am start x", "chmod +x f", "chown u f", "kill 1", "mount x",
        )) assertFalse("expected denied: $cmd", ro(cmd))
    }

    @Test
    fun commandSubstitutionCheckedRecursively() {
        assertTrue(ro("cat $(ls)"))
        assertTrue(ro("echo `pwd`"))
        assertFalse(ro("cat $(rm -f x)"))
        assertFalse(ro("echo `curl x`"))
    }

    @Test
    fun wrappersUnwrap() {
        assertTrue(ro("timeout 5 ls"))
        assertTrue(ro("nice -n 10 cat f"))
        assertTrue(ro("env git status"))
        assertFalse(ro("timeout 5 rm x"))
    }

    @Test
    fun malformedOrBackgroundRejected() {
        assertFalse(ro("ls &"))
        assertFalse(ro("ls >"))
        assertFalse(ro("(ls > f)"))
        assertFalse(ro("echo 'unclosed"))
        assertTrue(ro("ls > /dev/null 2>&1").not()) // 重定向一律拒绝
    }
}
