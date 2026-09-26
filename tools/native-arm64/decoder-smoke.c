/* Executes the actual generated decoder with a minimal JNI stand-in. This is not ART. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <jni.h>
#include "decoder.c"

typedef struct {
    jsize length;
    jchar chars[];
} test_string;

static const char *pending_exception;
static int fail_new_string;

static jclass test_find_class(JNIEnv *env, const char *name) {
    (void)env;
    return (jclass)name;
}

static jint test_throw_new(JNIEnv *env, jclass type, const char *message) {
    (void)env;
    (void)message;
    pending_exception = (const char *)type;
    return 0;
}

static jboolean test_exception_check(JNIEnv *env) {
    (void)env;
    return pending_exception != NULL;
}

static void test_delete_local_ref(JNIEnv *env, jobject object) {
    (void)env;
    (void)object;
}

static jstring test_new_string(JNIEnv *env, const jchar *chars, jsize length) {
    (void)env;
    if (fail_new_string) {
        pending_exception = "java/lang/OutOfMemoryError";
        return NULL;
    }
    if (length < 0 || (uint32_t)length > LSP_MAX_UTF16_LENGTH) abort();
    test_string *string = malloc(sizeof(*string) + (size_t)length * sizeof(jchar));
    if (!string) abort();
    string->length = length;
    memcpy(string->chars, chars, (size_t)length * sizeof(jchar));
    return (jstring)string;
}

static const struct JNINativeInterface test_functions = {
    .FindClass = test_find_class,
    .ThrowNew = test_throw_new,
    .DeleteLocalRef = test_delete_local_ref,
    .NewString = test_new_string,
    .ExceptionCheck = test_exception_check,
};

static uint64_t read_unsigned(FILE *file, unsigned bytes) {
    uint64_t value = 0;
    while (bytes--) {
        int byte = fgetc(file);
        if (byte == EOF) { fputs("Truncated fixture\n", stderr); exit(2); }
        value = (value << 8) | (unsigned)byte;
    }
    return value;
}

static void require_exception(const char *wanted) {
    if (!pending_exception || strcmp(pending_exception, wanted)) {
        fprintf(stderr, "Expected exception %s; got %s\n", wanted, pending_exception ? pending_exception : "none");
        exit(3);
    }
    pending_exception = NULL;
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    FILE *file = fopen(argv[1], "rb");
    if (!file) { perror("fixture"); return 2; }
    if (read_unsigned(file, 4) != UINT32_C(0x4c535454)) return 2;
    unsigned count = (unsigned)read_unsigned(file, 4);
    uint64_t unknown_id = read_unsigned(file, 8);
    if (count == 0 || count > 1000) return 2;
    JNIEnv env = &test_functions;
    uint64_t valid_id = 0;
    unsigned successes = 0, rejected = 0;
    for (unsigned i = 0; i < count; ++i) {
        uint64_t id = read_unsigned(file, 8);
        unsigned damaged = (unsigned)read_unsigned(file, 1);
        unsigned length = (unsigned)read_unsigned(file, 4);
        if (length > LSP_MAX_UTF16_LENGTH) return 2;
        jchar *wanted = malloc(length ? (size_t)length * sizeof(jchar) : sizeof(jchar));
        if (!wanted) return 2;
        for (unsigned j = 0; j < length; ++j) wanted[j] = (jchar)read_unsigned(file, 2);
        test_string *actual = (test_string *)lsp_decode(&env, NULL, (jlong)id);
        if (damaged) {
            if (actual != NULL) { fputs("Tampered ciphertext decoded\n", stderr); return 3; }
            require_exception("java/lang/IllegalStateException");
            ++rejected;
        } else {
            if (!actual || pending_exception || actual->length != (jsize)length ||
                memcmp(actual->chars, wanted, (size_t)length * sizeof(jchar))) {
                fprintf(stderr, "UTF-16 mismatch at input %u\n", i);
                return 3;
            }
            valid_id = id;
            ++successes;
            free(actual);
        }
        free(wanted);
    }
    if (fgetc(file) != EOF) return 2;
    fclose(file);
    if (lsp_decode(&env, NULL, (jlong)unknown_id) != NULL) return 3;
    require_exception("java/lang/IllegalArgumentException");
    fail_new_string = 1;
    if (lsp_decode(&env, NULL, (jlong)valid_id) != NULL) return 3;
    require_exception("java/lang/OutOfMemoryError");
    fail_new_string = 0;
    pending_exception = "existing/Exception";
    if (lsp_decode(&env, NULL, (jlong)valid_id) != NULL) return 3;
    require_exception("existing/Exception");
    printf("PASS: %u UTF-16 values, %u tampered record, unknown ID, NewString failure, pending exception\n", successes, rejected);
    return 0;
}
