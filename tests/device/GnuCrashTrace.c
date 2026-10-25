/* Trace an explicitly supplied GNU child. No attachment to unrelated processes. */
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include <elf.h>

static volatile sig_atomic_t child_id;
static volatile sig_atomic_t attached_target;
static void timeout_child(int ignored) {
    (void)ignored;
    if (child_id > 0 && !attached_target) kill(child_id, SIGKILL);
    _exit(124);
}

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    int first = 1;
    const char *preload = NULL;
    int attached = argc == 3 && !strcmp(argv[1], "--attach");
    if (argc > 3 && !strcmp(argv[1], "--ld-preload")) {preload = argv[2]; first = 3;}
    setbuf(stdout, NULL); setbuf(stderr, NULL);
    pid_t child = attached ? (pid_t)strtol(argv[2], NULL, 10) : fork();
    if (attached) {
        char status_path[64]; snprintf(status_path, sizeof(status_path), "/proc/%d/status", child);
        FILE *status_file = fopen(status_path, "r");
        char line[256]; unsigned uid = (unsigned)-1;
        if (status_file) {while (fgets(line, sizeof(line), status_file)) if (sscanf(line, "Uid: %u", &uid) == 1) break; fclose(status_file);}
        if (child <= 1 || uid != getuid()) {fprintf(stderr, "Target UID mismatch\n"); return 2;}
        if (ptrace(PTRACE_ATTACH, child, NULL, NULL) < 0) {perror("PTRACE_ATTACH"); return 2;}
    }
    if (child == 0) {
        if (ptrace(PTRACE_TRACEME, 0, NULL, NULL) < 0) {perror("PTRACE_TRACEME"); _exit(2);}
        if (preload) setenv("LD_PRELOAD", preload, 1);
        execv(argv[first], argv + first); perror("execv"); _exit(2);
    }
    if (child < 0) {perror("fork"); return 2;}
    child_id = child; attached_target = attached; signal(SIGALRM, timeout_child); alarm(60);
    int status;
    while (waitpid(child, &status, 0) == child) {
        if (WIFEXITED(status)) return WEXITSTATUS(status);
        if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
        if (!WIFSTOPPED(status)) continue;
        int signal = WSTOPSIG(status);
        if (signal == SIGBUS || signal == SIGSEGV || signal == SIGILL || signal == SIGABRT) {
            siginfo_t info;
            if (ptrace(PTRACE_GETSIGINFO, child, NULL, &info) == 0)
                fprintf(stderr, "CHILD_FAULT signal=%d code=%d address=%p\n", signal, info.si_code, info.si_addr);
            unsigned long long registers[34] = {0};
            struct iovec io = {registers, sizeof(registers)};
            if (ptrace(PTRACE_GETREGSET, child, (void *)NT_PRSTATUS, &io) == 0) {
                for (int i = 0; i < 31; i++) fprintf(stderr, "x%d=%016llx%c", i, registers[i], i % 4 == 3 ? '\n' : ' ');
                fprintf(stderr, "\nsp=%016llx pc=%016llx pstate=%016llx\n", registers[31], registers[32], registers[33]);
                unsigned long long frame = registers[29];
                for (int depth = 0; depth < 24 && frame && !(frame & 7); depth++) {
                    errno = 0;
                    unsigned long long next = (unsigned long long)ptrace(PTRACE_PEEKDATA, child, (void *)frame, NULL);
                    unsigned long long lr = (unsigned long long)ptrace(PTRACE_PEEKDATA, child, (void *)(frame + 8), NULL);
                    if (errno) break;
                    fprintf(stderr, "frame%d fp=%016llx lr=%016llx\n", depth, frame, lr);
                    if (next <= frame || next - frame > 8 * 1024 * 1024) break;
                    frame = next;
                }
            }
            char path[64], line[1024];
            snprintf(path, sizeof(path), "/proc/%d/maps", child);
            FILE *maps = fopen(path, "r");
            if (maps) {while (fgets(line, sizeof(line), maps)) fputs(line, stderr); fclose(maps);}
            if (attached) ptrace(PTRACE_DETACH, child, NULL, (void *)(long)signal);
            else {ptrace(PTRACE_KILL, child, NULL, NULL); waitpid(child, &status, 0);}
            return 128 + signal;
        }
        if (ptrace(PTRACE_CONT, child, NULL, (void *)(long)(signal == SIGTRAP || signal == SIGSTOP ? 0 : signal)) < 0) {
            perror("PTRACE_CONT");
            if (attached) ptrace(PTRACE_DETACH, child, NULL, NULL);
            else {kill(child, SIGKILL); waitpid(child, &status, 0);}
            return 2;
        }
    }
    perror("waitpid"); return 2;
}
