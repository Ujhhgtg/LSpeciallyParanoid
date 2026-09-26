#define _GNU_SOURCE
#include "frida_guard.h"
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* Exercises the unchanged production scanner in its own Android process.
 * No injection into another app or patches to the supplied Gadget. */
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    int control = !strcmp(argv[1], "control");
    int late = !strcmp(argv[1], "late");
    printf("LSP_FRIDA_TEST pid=%d mode=%s\n", getpid(), argv[1]);
    fflush(stdout);
    if (!control) {
        lsp_frida_check(1);
        puts("CLEAN_BOOTSTRAP"); fflush(stdout);
    }
    void *library = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
    if (library == NULL) { fprintf(stderr, "GADGET_ERROR: %s\n", dlerror()); return 3; }
    puts("GADGET_LOADED"); fflush(stdout);
    struct timespec pause = {late ? 1 : 3, late ? 200000000 : 0};
    if (late || control) nanosleep(&pause, NULL);
    if (!control) lsp_frida_check(late ? 0 : 1);
    puts("ALIVE"); fflush(stdout);
    _exit(0);
}
