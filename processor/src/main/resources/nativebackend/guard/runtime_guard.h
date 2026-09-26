#ifndef LSP_RUNTIME_GUARD_H
#define LSP_RUNTIME_GUARD_H

#include <jni.h>
#include <stddef.h>
#include <stdint.h>

/* The generator pins certificates, never a caller-supplied APK path. */
typedef struct {
    const char *package_name;
    const uint8_t (*signers)[32];
    size_t signer_count;
} lsp_guard_host_policy;

/* Both entry points terminate the process on failure. init precedes RegisterNatives. */
void lsp_guard_init(JavaVM *vm, JNIEnv *env, jclass bridge);
void lsp_guard_check(JNIEnv *env, jclass bridge);

#endif
