#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static void close_pair(int pair[2]) { close(pair[0]); close(pair[1]); }

JNIEXPORT jintArray JNICALL
Java_com_jarves_mh_runtime_NativeSpawn_spawn(JNIEnv *env, jobject self, jobjectArray java_argv,
                                               jobjectArray java_env, jstring java_cwd,
                                               jstring java_output) {
    (void)self;
    jsize argc = (*env)->GetArrayLength(env, java_argv);
    jsize envc = (*env)->GetArrayLength(env, java_env);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!argv || !envp) return NULL;
    for (jsize i = 0; i < argc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_env, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        envp[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    const char *cwd_utf = (*env)->GetStringUTFChars(env, java_cwd, NULL);
    char *cwd = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, java_cwd, cwd_utf);
    const char *output_utf = (*env)->GetStringUTFChars(env, java_output, NULL);
    char *output_path = strdup(output_utf);
    (*env)->ReleaseStringUTFChars(env, java_output, output_utf);

    int in_pipe[2];
    if (pipe(in_pipe) != 0) return NULL;
    pid_t pid = fork();
    if (pid == 0) {
        // Give every runtime launch its own process group so stopping the wrapper
        // also stops Claude Code and commands spawned underneath it.
        setpgid(0, 0);
        close(in_pipe[1]);
        int output_fd = open(output_path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
        if (output_fd < 0) _exit(126);
        dup2(in_pipe[0], STDIN_FILENO);
        dup2(output_fd, STDOUT_FILENO);
        dup2(output_fd, STDERR_FILENO);
        close(in_pipe[0]);
        close(output_fd);
        chdir(cwd);
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        execve(argv[0], argv, envp);
        dprintf(STDERR_FILENO, "Pocket native exec failed: %s\n", strerror(errno));
        _exit(127);
    }
    if (pid > 0) setpgid(pid, pid);
    close(in_pipe[0]);
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(argv); free(envp); free(cwd); free(output_path);
    if (pid < 0) { close_pair(in_pipe); return NULL; }
    jint values[2] = {pid, in_pipe[1]};
    jintArray result = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_jarves_mh_runtime_NativeSpawn_waitFor(JNIEnv *env, jobject self, jint pid, jboolean no_hang) {
    (void)env; (void)self;
    int status = 0;
    pid_t value = waitpid(pid, &status, no_hang ? WNOHANG : 0);
    if (value == 0) return -2;
    if (value < 0) return -128 - errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_jarves_mh_runtime_NativeSpawn_kill(JNIEnv *env, jobject self, jint pid, jint signal) {
    (void)env; (void)self;
    // Negative pid targets the whole runtime process group. Fall back to the
    // wrapper pid for devices where group creation raced with an early exit.
    int result = kill(-pid, signal);
    if (result != 0 && errno == ESRCH) result = kill(pid, signal);
    return result;
}

#include <termios.h>
#include <sys/ioctl.h>

JNIEXPORT jintArray JNICALL
Java_com_jarves_mh_terminal_NativePty_createPty(JNIEnv *env, jobject self, jobjectArray java_argv,
                                                jobjectArray java_env, jstring java_cwd,
                                                jint rows, jint cols) {
    (void)self;
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return NULL;

    if (grantpt(ptm) != 0 || unlockpt(ptm) != 0) {
        close(ptm);
        return NULL;
    }

    char devname[64];
    if (ptsname_r(ptm, devname, sizeof(devname)) != 0) {
        close(ptm);
        return NULL;
    }

    // Set initial window size
    struct winsize sz = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols,
        .ws_xpixel = 0,
        .ws_ypixel = 0
    };
    ioctl(ptm, TIOCSWINSZ, &sz);

    // Set UTF-8
    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
#ifdef IUTF8
        tios.c_iflag |= IUTF8;
#endif
        tcsetattr(ptm, TCSANOW, &tios);
    }

    jsize argc = (*env)->GetArrayLength(env, java_argv);
    jsize envc = (*env)->GetArrayLength(env, java_env);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!argv || !envp) {
        close(ptm);
        free(argv); free(envp);
        return NULL;
    }

    for (jsize i = 0; i < argc; i++) {
        jstring val = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        const char *utf = (*env)->GetStringUTFChars(env, val, NULL);
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, val, utf);
        (*env)->DeleteLocalRef(env, val);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring val = (jstring)(*env)->GetObjectArrayElement(env, java_env, i);
        const char *utf = (*env)->GetStringUTFChars(env, val, NULL);
        envp[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, val, utf);
        (*env)->DeleteLocalRef(env, val);
    }
    const char *cwd_utf = (*env)->GetStringUTFChars(env, java_cwd, NULL);
    char *cwd = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, java_cwd, cwd_utf);

    pid_t pid = fork();
    if (pid == 0) {
        // Child: setsid to break controlling terminal association
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(125);

        // TIOCSCTTY: acquire controlling terminal
        ioctl(pts, TIOCSCTTY, 0);

        dup2(pts, STDIN_FILENO);
        dup2(pts, STDOUT_FILENO);
        dup2(pts, STDERR_FILENO);

        close(pts);
        close(ptm);

        chdir(cwd);
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        execve(argv[0], argv, envp);
        _exit(127);
    }

    for (jsize i = 0; i < argc; i++) free(argv[i]);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(argv); free(envp); free(cwd);

    if (pid < 0) {
        close(ptm);
        return NULL;
    }

    jint values[2] = {ptm, (jint)pid};
    jintArray result = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_jarves_mh_terminal_NativePty_setWindowSize(JNIEnv *env, jobject self, jint ptm_fd, jint rows, jint cols) {
    (void)env; (void)self;
    struct winsize sz = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols,
        .ws_xpixel = 0,
        .ws_ypixel = 0
    };
    return ioctl(ptm_fd, TIOCSWINSZ, &sz);
}

JNIEXPORT jint JNICALL
Java_com_jarves_mh_terminal_NativePty_closePty(JNIEnv *env, jobject self, jint ptm_fd, jint pid) {
    (void)env; (void)self;
    if (pid > 0) {
        kill(-pid, SIGHUP);
        kill(pid, SIGHUP);
    }
    if (ptm_fd >= 0) {
        close(ptm_fd);
    }
    return 0;
}

