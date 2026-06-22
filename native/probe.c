/*
 * AndMX exec probe.
 *
 * A tiny binary used to validate that the device allows executing a bundled
 * native binary, and to characterise the W^X policy (exec from
 * nativeLibraryDir vs. from the writable app data dir).
 *
 * It prints an identifiable banner plus a little environment info, echoes its
 * arguments, and exits 0.
 */
#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <sys/utsname.h>

int main(int argc, char **argv) {
    /* Use raw write() first so we get a signal even if libc stdio is unhappy. */
    const char *banner = "ANDMX_PROBE_OK\n";
    write(1, banner, strlen(banner));

    struct utsname u;
    if (uname(&u) == 0) {
        printf("uname: %s %s %s\n", u.sysname, u.release, u.machine);
    }

    printf("pid=%d uid=%d\n", (int) getpid(), (int) getuid());

    char cwd[1024];
    if (getcwd(cwd, sizeof(cwd))) {
        printf("cwd=%s\n", cwd);
    }

    for (int i = 1; i < argc; i++) {
        printf("arg[%d]=%s\n", i, argv[i]);
    }

    fflush(stdout);
    return 0;
}
