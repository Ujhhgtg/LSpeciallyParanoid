/* LSpeciallyParanoid format v1. UTF-16LE + RFC 8439 ChaCha20-Poly1305. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "monocypher.h"

typedef struct {
    uint64_t id;
    uint32_t length;
    uint64_t offset;
    uint8_t nonce[12];
    uint8_t domain;
} lsp_record;

#include "payload.h"

static void lsp_throw(JNIEnv *env, const char *type, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass exception = (*env)->FindClass(env, type);
    if (exception != NULL) {
        (*env)->ThrowNew(env, exception, message);
        (*env)->DeleteLocalRef(env, exception);
    }
}

static void lsp_store32(uint8_t *p, uint32_t x) {
    for (unsigned i = 0; i != 4; ++i) p[i] = (uint8_t)(x >> (8 * i));
}

static __attribute__((noinline)) const lsp_record *lsp_resolve(uint64_t id) {
    size_t low = 0, high = LSP_RECORD_COUNT;
    while (low < high) {
        const size_t mid = low + (high - low) / 2;
        if (lsp_records[mid].id < id) low = mid + 1;
        else high = mid;
    }
    return low < LSP_RECORD_COUNT && lsp_records[low].id == id ? &lsp_records[low] : NULL;
}

static __attribute__((noinline)) int lsp_authenticate(const lsp_record *record, uint8_t *plain, size_t size) {
    uint8_t ad[20];
    lsp_store32(ad, LSP_FORMAT_VERSION);
    lsp_store32(ad + 4, record->domain);
    lsp_store32(ad + 8, (uint32_t)record->id);
    lsp_store32(ad + 12, (uint32_t)(record->id >> 32));
    lsp_store32(ad + 16, record->length);
    const uint8_t *ciphertext = lsp_payload + (size_t)record->offset;
    crypto_aead_ctx ctx;
    crypto_aead_init_ietf(&ctx, record->domain ? lsp_key_1 : lsp_key_0, record->nonce);
    const int result = crypto_aead_read(&ctx, plain, ciphertext + size, ad, sizeof(ad), ciphertext, size);
    crypto_wipe(&ctx, sizeof(ctx));
    return result;
}

static jstring lsp_decode(JNIEnv *env, jclass unused, jlong java_id) {
    (void)unused;
    if ((*env)->ExceptionCheck(env)) return NULL;
    const lsp_record *record = lsp_resolve((uint64_t)java_id);
    if (record == NULL) {
        lsp_throw(env, "java/lang/IllegalArgumentException", "Unknown LSP protected string ID");
        return NULL;
    }
    if (record->length > LSP_MAX_UTF16_LENGTH || record->domain > 1 ||
        ((record->id >> 63) != record->domain) || record->offset > sizeof(lsp_payload)) {
        lsp_throw(env, "java/lang/IllegalStateException", "Invalid LSP protected string metadata");
        return NULL;
    }
    const size_t size = (size_t)record->length * 2;
    if (sizeof(lsp_payload) - (size_t)record->offset < 16 ||
        size > sizeof(lsp_payload) - (size_t)record->offset - 16) {
        lsp_throw(env, "java/lang/IllegalStateException", "Invalid LSP protected string bounds");
        return NULL;
    }
    uint8_t *plain = (uint8_t *)malloc(size ? size : sizeof(jchar));
    if (plain == NULL) {
        lsp_throw(env, "java/lang/OutOfMemoryError", "LSP protected string allocation failed");
        return NULL;
    }
    const int result = lsp_authenticate(record, plain, size);
    if (result != 0) {
        crypto_wipe(plain, size);
        free(plain);
        lsp_throw(env, "java/lang/IllegalStateException", "LSP protected string authentication failed");
        return NULL;
    }
    // Reconstruct code units explicitly: NewString preserves NUL and lone surrogates.
    jchar *chars = (jchar *)plain;
    for (size_t i = 0; i < record->length; ++i) {
        const uint16_t unit = (uint16_t)plain[2 * i] | ((uint16_t)plain[2 * i + 1] << 8);
        chars[i] = (jchar)unit;
    }
    jstring decoded = (*env)->NewString(env, chars, (jsize)record->length);
    crypto_wipe(plain, size);
    free(plain);
    return decoded; // A pending NewString OOM propagates after the native temporary is erased.
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass bridge = (*env)->FindClass(env, LSP_BRIDGE_CLASS);
    if (bridge == NULL) return JNI_ERR;
    JNINativeMethod methods[] = {{LSP_NATIVE_METHOD, "(J)Ljava/lang/String;", (void *)lsp_decode}};
    const jint result = (*env)->RegisterNatives(env, bridge, methods, 1);
    (*env)->DeleteLocalRef(env, bridge);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
