#define _GNU_SOURCE
#include "frida_guard.h"

#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/set_abort_message.h>
#endif

#define LSP_FRIDA_INTERVAL_NS UINT64_C(1000000000)
#define LSP_FRIDA_MAX_IMAGE (128u * 1024u * 1024u)
#define LSP_FRIDA_CACHE_SIZE 512

/* Hashes of complete GObject type names, not embedded plaintext signatures that
 * would make the detector identify its own ELF. See artifacts/frida-detection.md.
 * Require interceptor + scheduler + a JS engine in the SAME mapped ELF image.
 * The reversed scheduler prefix accounts for the inspected strongR rodata patch. */
static unsigned lsp_gum_type(uint64_t hash) {
    switch (hash) {
        case UINT64_C(0xabc8a65d4d0bd197): return 1; /* GumInterceptor */
        case UINT64_C(0x34bf49d0182aaf4c): /* GumScriptScheduler */
        case UINT64_C(0x0913d56bfd9b908c): return 2; /* tpircSmuGScheduler */
        case UINT64_C(0x08211230d77718d4): /* GumQuickScript */
        case UINT64_C(0x0791a8bc7585a989): return 4; /* GumV8Script */
        default: return 0;
    }
}

typedef struct {
    uintptr_t start, end;
    unsigned long long inode, offset;
    unsigned major, minor;
} lsp_image_key;
static lsp_image_key lsp_clean_images[LSP_FRIDA_CACHE_SIZE];
static size_t lsp_cache_count, lsp_cache_next;
static atomic_flag lsp_scanning = ATOMIC_FLAG_INIT;
static _Atomic uint64_t lsp_next_scan;

static int lsp_open_proc(const char *path, int flags) {
    return (int)syscall(SYS_openat, AT_FDCWD, path, flags | O_CLOEXEC, 0);
}

static ssize_t lsp_read_memory(pid_t process, void *data, size_t size, uintptr_t at) {
    struct iovec local = {data, size}, remote = {(void *)at, size};
    ssize_t count;
    do { count = syscall(SYS_process_vm_readv, process, &local, 1, &remote, 1, 0); } while (count < 0 && errno == EINTR);
    return count;
}

static int lsp_frida_name(const char *path) {
    const char *name = strrchr(path, '/');
    name = name != NULL ? name + 1 : path;
    if (!strncmp(name, "memfd:", 6)) name += 6;
    if (!strncmp(name, "lib", 3)) name += 3;
    const char *prefixes[] = {"frida-agent", "frida-gadget"};
    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); ++i) {
        size_t n = strlen(prefixes[i]);
        if (!strncmp(name, prefixes[i], n) && (name[n] == '.' || name[n] == '-')) return 1;
    }
    return 0;
}

static unsigned lsp_scan_segment(pid_t process, uintptr_t address, size_t size) {
    uint8_t bytes[16384 + 31];
    unsigned found = 0;
    size_t carry = 0;
    while (size > 0) {
        size_t request = size < 16384 ? size : 16384;
        ssize_t count = lsp_read_memory(process, bytes + carry, request, address);
        if (count <= 0) break; /* A module may unload between the maps snapshot and this read. */
        size_t available = carry + (size_t)count;
        for (size_t i = 0; i < available; ++i) {
            if (bytes[i] != 'G' && bytes[i] != 't') continue;
            uint64_t hash = UINT64_C(14695981039346656037);
            for (size_t j = i; j < available && j - i < 32; ++j) {
                if (bytes[j] == 0) { found |= lsp_gum_type(hash); break; }
                hash = (hash ^ bytes[j]) * UINT64_C(1099511628211);
            }
        }
        if (found == 7) return found;
        carry = available < 31 ? available : 31;
        memmove(bytes, bytes + available - carry, carry);
        address += (size_t)count;
        size -= (size_t)count;
    }
    return found;
}

static int lsp_scan_image(pid_t process, const lsp_image_key *key) {
    for (size_t i = 0; i < lsp_cache_count; ++i) {
        const lsp_image_key *old = &lsp_clean_images[i];
        if (old->start == key->start && old->end == key->end && old->inode == key->inode &&
            old->offset == key->offset && old->major == key->major && old->minor == key->minor) return 0;
    }
    Elf64_Ehdr header;
    if (lsp_read_memory(process, &header, sizeof(header), key->start) != sizeof(header) ||
        memcmp(header.e_ident, ELFMAG, SELFMAG) || header.e_ident[EI_CLASS] != ELFCLASS64 ||
        header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_type != ET_DYN ||
        header.e_phnum == 0 || header.e_phnum > 128 || header.e_phentsize != sizeof(Elf64_Phdr)) return 0;
    Elf64_Phdr segments[128];
    size_t headers_size = (size_t)header.e_phnum * sizeof(Elf64_Phdr);
    if (header.e_phoff > key->end - key->start || headers_size > key->end - key->start - header.e_phoff ||
        lsp_read_memory(process, segments, headers_size, key->start + header.e_phoff) != (ssize_t)headers_size) return 0;
    uintptr_t base = key->start;
    int executable = 0, has_header = 0, separate_rodata = 0;
    for (size_t i = 0; i < header.e_phnum; ++i) {
        Elf64_Phdr *p = &segments[i];
        if (p->p_type != PT_LOAD) continue;
        if (p->p_flags & PF_X) executable = 1;
        if ((p->p_flags & (PF_R | PF_W | PF_X)) == PF_R) separate_rodata = 1;
        if (p->p_offset == 0 && p->p_filesz >= sizeof(header) && p->p_vaddr <= key->start) {
            base = key->start - p->p_vaddr;
            has_header = 1;
        }
    }
    if (!executable || !has_header) return 0;
    unsigned found = 0;
    size_t total = 0;
    for (size_t i = 0; i < header.e_phnum; ++i) {
        Elf64_Phdr *p = &segments[i];
        if (p->p_type != PT_LOAD || !(p->p_flags & PF_R) || (p->p_flags & PF_W)) continue;
        // Modern linkers separate rodata from machine code. Older RX layouts still work.
        if (separate_rodata && (p->p_flags & PF_X)) continue;
        if (p->p_filesz > p->p_memsz || p->p_filesz > LSP_FRIDA_MAX_IMAGE - total ||
            p->p_vaddr > UINTPTR_MAX - base || p->p_filesz > UINTPTR_MAX - base - p->p_vaddr) return -6;
        total += (size_t)p->p_filesz;
        found |= lsp_scan_segment(process, base + p->p_vaddr, (size_t)p->p_filesz);
        if (found == 7) return 2;
    }
    lsp_clean_images[lsp_cache_next] = *key;
    lsp_cache_next = (lsp_cache_next + 1) % LSP_FRIDA_CACHE_SIZE;
    if (lsp_cache_count < LSP_FRIDA_CACHE_SIZE) ++lsp_cache_count;
    return 0;
}

static int lsp_scan_mappings(void) {
    int maps_fd = lsp_open_proc("/proc/self/maps", O_RDONLY);
    if (maps_fd < 0) return -1;
    FILE *maps = fdopen(maps_fd, "r");
    if (maps == NULL) { close(maps_fd); return -2; }
    /* Android denies /proc/self/mem to ordinary apps. A self process_vm_readv
     * reads only this process and safely handles mappings unloaded during the scan. */
    pid_t process = getpid();
    unsigned char probe;
    if (lsp_read_memory(process, &probe, 1, (uintptr_t)&lsp_scan_mappings) != 1) {
        fclose(maps); return -4;
    }
    char line[PATH_MAX + 256];
    int result = 0;
    size_t lines = 0;
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (++lines > 32768) { result = -5; break; }
        lsp_image_key key = {0};
        char permissions[5];
        int used = 0;
        if (sscanf(line, "%lx-%lx %4s %llx %x:%x %llu %n", &key.start, &key.end,
            permissions, &key.offset, &key.major, &key.minor, &key.inode, &used) != 7 || key.end <= key.start) continue;
        if (permissions[2] == 'x' && used > 0 && lsp_frida_name(line + used)) { result = 1; break; }
        if (permissions[0] != 'r' || permissions[1] == 'w') continue;
        result = lsp_scan_image(process, &key);
        if (result != 0) break;
    }
    if (ferror(maps)) result = -7;
    fclose(maps);
    return result;
}

static int lsp_scan_threads(void) {
    /* Frida's DirListCloaker intercepts libc readdir. Enumerate kernel entries directly. */
    int directory = lsp_open_proc("/proc/self/task", O_RDONLY | O_DIRECTORY);
    if (directory < 0) return -8;
    unsigned char entries[8192];
    int result = 0;
    size_t visited = 0;
    for (;;) {
        long count = syscall(SYS_getdents64, directory, entries, sizeof(entries));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { if (count < 0) result = -9; break; }
        for (size_t at = 0; at < (size_t)count; ) {
            uint16_t length;
            if ((size_t)count - at < 20) { result = -10; goto done; }
            memcpy(&length, entries + at + 16, sizeof(length));
            if (length < 20 || length > (size_t)count - at) { result = -10; goto done; }
            char *name = (char *)entries + at + 19;
            size_t size = strnlen(name, length - 19);
            if (++visited > 32768 || size == (size_t)length - 19) { result = -10; goto done; }
            if (size > 0 && size < 20 && strspn(name, "0123456789") == size) {
                char path[80], comm[32];
                snprintf(path, sizeof(path), "/proc/self/task/%s/comm", name);
                int fd = lsp_open_proc(path, O_RDONLY);
                if (fd >= 0) {
                    ssize_t n = syscall(SYS_read, fd, comm, sizeof(comm) - 1);
                    close(fd);
                    if (n > 0) {
                        comm[n] = '\0'; comm[strcspn(comm, "\n")] = '\0';
                        if (!strcmp(comm, "gum-js-loop") || !strcmp(comm, "frida-gadget") ||
                            !strcmp(comm, "frida-agent")) { result = 3; goto done; }
                    }
                } /* A thread may exit normally during enumeration. */
            }
            at += length;
        }
    }
done:
    close(directory);
    return result;
}

void lsp_frida_check(int force) {
    struct timespec time;
    if (clock_gettime(CLOCK_MONOTONIC, &time) != 0) abort();
    uint64_t now = (uint64_t)time.tv_sec * UINT64_C(1000000000) + (uint64_t)time.tv_nsec;
    if (!force && now < atomic_load_explicit(&lsp_next_scan, memory_order_relaxed)) return;
    if (atomic_flag_test_and_set_explicit(&lsp_scanning, memory_order_acquire)) return;
    int result = lsp_scan_mappings();
    if (result == 0) result = lsp_scan_threads();
    if (result != 0) {
#ifdef __ANDROID__
        char failure[96];
        snprintf(failure, sizeof(failure), "LSP guard: Frida inspection unavailable (%d, errno %d)", result, errno);
        android_set_abort_message(result == 1 ? "LSP guard: Frida agent mapping detected" :
            result == 2 ? "LSP guard: Frida Gum runtime detected" :
            result == 3 ? "LSP guard: Frida thread detected" : failure);
#endif
        abort();
    }
    atomic_store_explicit(&lsp_next_scan, now + LSP_FRIDA_INTERVAL_NS, memory_order_relaxed);
    atomic_flag_clear_explicit(&lsp_scanning, memory_order_release);
}
