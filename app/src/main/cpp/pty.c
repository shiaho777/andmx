/*
 * AndMX PTY bridge.
 *
 * Opens a pseudo-terminal master, forks a child attached to the slave as its
 * controlling terminal, and execve()s the requested program (in our case the
 * proot launcher entering the guest shell). The Java side reads/writes the
 * returned master fd to drive a fully interactive terminal — colours, line
 * editing, curses apps, password prompts, the lot.
 *
 * Modeled on the well-trodden Termux approach; no root required.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <android/log.h>

#define LOG_TAG "AndmxPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static char **jarray_to_cstrs(JNIEnv *env, jobjectArray array) {
    if (array == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, array);
    char **out = calloc((size_t) n + 1, sizeof(char *));
    if (!out) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    out[n] = NULL;
    return out;
}

static void free_cstrs(char **arr) {
    if (!arr) return;
    for (char **p = arr; *p; p++) free(*p);
    free(arr);
}

JNIEXPORT jint JNICALL
Java_com_andmx_exec_pty_PtyNative_createSubprocess(
        JNIEnv *env, jclass clazz,
        jstring cmd_, jstring cwd_,
        jobjectArray argv_, jobjectArray envp_,
        jintArray pidOut_, jint rows, jint cols) {

    const char *cmd = (*env)->GetStringUTFChars(env, cmd_, NULL);
    const char *cwd = cwd_ ? (*env)->GetStringUTFChars(env, cwd_, NULL) : NULL;
    char **argv = jarray_to_cstrs(env, argv_);
    char **envp = jarray_to_cstrs(env, envp_);

    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        LOGE("open /dev/ptmx failed");
        goto fail;
    }
    if (grantpt(ptm) || unlockpt(ptm)) {
        LOGE("grantpt/unlockpt failed");
        close(ptm);
        goto fail;
    }

    char devname[64];
    if (ptsname_r(ptm, devname, sizeof(devname))) {
        LOGE("ptsname_r failed");
        close(ptm);
        goto fail;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed");
        close(ptm);
        goto fail;
    }

    if (pid == 0) {
        // ---- child ----
        setsid();
        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(126);

        struct winsize sz;
        memset(&sz, 0, sizeof(sz));
        sz.ws_row = (unsigned short) rows;
        sz.ws_col = (unsigned short) cols;
        ioctl(pts, TIOCSWINSZ, &sz);
        ioctl(pts, TIOCSCTTY, 0);

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);
        if (pts > 2) close(pts);
        close(ptm);

        if (cwd) chdir(cwd);
        execve(cmd, argv, envp);
        _exit(127);
    }

    // ---- parent ----
    if (pidOut_) {
        jint p = (jint) pid;
        (*env)->SetIntArrayRegion(env, pidOut_, 0, 1, &p);
    }

    if (cmd) (*env)->ReleaseStringUTFChars(env, cmd_, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, cwd_, cwd);
    free_cstrs(argv);
    free_cstrs(envp);
    return ptm;

fail:
    if (cmd) (*env)->ReleaseStringUTFChars(env, cmd_, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, cwd_, cwd);
    free_cstrs(argv);
    free_cstrs(envp);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_andmx_exec_pty_PtyNative_setPtyWindowSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize sz;
    memset(&sz, 0, sizeof(sz));
    sz.ws_row = (unsigned short) rows;
    sz.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT jint JNICALL
Java_com_andmx_exec_pty_PtyNative_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status;
    if (waitpid(pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_andmx_exec_pty_PtyNative_closeFd(JNIEnv *env, jclass clazz, jint fd) {
    if (fd >= 0) close(fd);
}
