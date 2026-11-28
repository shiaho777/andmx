#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    void *library = dlopen("libqemu-system-aarch64.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        fprintf(stderr, "%s\n", dlerror());
        return EXIT_FAILURE;
    }
    void (*init)(int, char **) = (void (*)(int, char **))dlsym(library, "qemu_init");
    int (*run)(void) = (int (*)(void))dlsym(library, "qemu_main_loop");
    void (*cleanup)(int) = (void (*)(int))dlsym(library, "qemu_cleanup");
    if (!init || !run || !cleanup) {
        fputs("Unsupported QEMU entry points\n", stderr);
        return EXIT_FAILURE;
    }
    init(argc, argv);
    int result = run();
    cleanup(result);
    return result;
}
