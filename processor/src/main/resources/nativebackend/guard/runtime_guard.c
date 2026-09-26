#define _GNU_SOURCE
#include "runtime_guard.h"
#include "apk_verify.h"
#include "frida_guard.h"
#include "guard_policy.h"

#include <android/set_abort_message.h>
#include <dirent.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/android/binder.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/types.h>
#include <unistd.h>

/* Deliberate bounds: discover the loaded module, rather than crawling device storage. */
#define LSP_MAX_APKS 96
#define LSP_MAX_ORIGINS 32
#define LSP_MAX_FDS 4096
#define LSP_MAX_MAP_LINES 32768
#define LSP_MAX_DESCRIPTION (128 * 1024)

static JavaVM *lsp_verified_vm;
static jobject lsp_verified_bridge;
static jobject lsp_verified_loader;
static pid_t lsp_verified_pid;
static uid_t lsp_verified_uid;

typedef struct { int fd; dev_t device; ino_t inode; } lsp_candidate;
typedef struct {
    lsp_candidate files[LSP_MAX_APKS];
    size_t count;
    char origins[LSP_MAX_ORIGINS][PATH_MAX];
    size_t origin_count;
    int memory_loader;
} lsp_discovery;

static __attribute__((noreturn, noinline)) void lsp_fail(const char *reason) {
    android_set_abort_message(reason);
    abort();
}

static void lsp_require(int condition, const char *reason) {
    if (!condition) lsp_fail(reason);
}

static void lsp_jni_ok(JNIEnv *env) {
    lsp_require(!(*env)->ExceptionCheck(env), "LSP guard: Android runtime lookup failed");
}

static jclass lsp_class(JNIEnv *env, const char *name) {
    jclass type = (*env)->FindClass(env, name);
    lsp_jni_ok(env);
    lsp_require(type != NULL, "LSP guard: Android class absent");
    /* JNI FindClass starts from the requesting loader. Anchor framework types to the
     * real boot loader, not a module loader's similarly named replacement classes. */
    /* The class of a real JNI jclass is VM-owned java.lang.Class; this anchor does
     * not itself depend on another lookup through the requesting classloader. */
    jclass class_type = (*env)->GetObjectClass(env, type);
    lsp_jni_ok(env);
    lsp_require(class_type != NULL, "LSP guard: core Java class absent");
    jmethodID get_loader = (*env)->GetMethodID(env, class_type, "getClassLoader", "()Ljava/lang/ClassLoader;");
    lsp_jni_ok(env);
    lsp_require(get_loader != NULL, "LSP guard: class origin lookup absent");
    jobject boot = (*env)->CallNonvirtualObjectMethod(env, class_type, class_type, get_loader);
    jobject origin = (*env)->CallNonvirtualObjectMethod(env, type, class_type, get_loader);
    lsp_jni_ok(env);
    lsp_require((*env)->IsSameObject(env, boot, origin), "LSP guard: substituted framework class rejected");
    (*env)->DeleteLocalRef(env, origin);
    (*env)->DeleteLocalRef(env, boot);
    (*env)->DeleteLocalRef(env, class_type);
    return type;
}

static jmethodID lsp_method(JNIEnv *env, jclass type, const char *name, const char *signature) {
    jmethodID id = (*env)->GetMethodID(env, type, name, signature);
    lsp_jni_ok(env);
    lsp_require(id != NULL, "LSP guard: Android method absent");
    return id;
}

static jmethodID lsp_static_method(JNIEnv *env, jclass type, const char *name, const char *signature) {
    jmethodID id = (*env)->GetStaticMethodID(env, type, name, signature);
    lsp_jni_ok(env);
    lsp_require(id != NULL, "LSP guard: Android static method absent");
    return id;
}

static jfieldID lsp_field(JNIEnv *env, jclass type, const char *name, const char *signature) {
    jfieldID id = (*env)->GetFieldID(env, type, name, signature);
    lsp_jni_ok(env);
    lsp_require(id != NULL, "LSP guard: Android field absent");
    return id;
}

static int lsp_java_string(JNIEnv *env, jstring value, char *out, size_t capacity) {
    if (value == NULL || capacity == 0) return 0;
    const char *text = (*env)->GetStringUTFChars(env, value, NULL);
    lsp_jni_ok(env);
    if (text == NULL) return 0;
    size_t size = strlen(text);
    int valid = size < capacity;
    if (valid) memcpy(out, text, size + 1);
    (*env)->ReleaseStringUTFChars(env, value, text);
    return valid;
}

static int lsp_read_small(const char *path, char *out, size_t capacity) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t count = read(fd, out, capacity - 1);
    close(fd);
    if (count <= 0) return 0;
    out[count] = '\0';
    return 1;
}

static int lsp_link(const char *path, char *out, size_t capacity) {
    ssize_t size = readlink(path, out, capacity - 1);
    if (size <= 0 || (size_t)size >= capacity - 1) return 0;
    out[size] = '\0';
    return 1;
}

static int lsp_process_matches(const char *actual, const char *package, int children) {
    size_t count = strlen(package);
    return !strncmp(actual, package, count) &&
           (actual[count] == '\0' || (children && actual[count] == ':' && actual[count + 1] != '\0'));
}

static void lsp_environment(JavaVM *vm, JNIEnv *env) {
    JavaVM *reported = NULL;
    lsp_require((*env)->GetJavaVM(env, &reported) == JNI_OK && reported == vm,
                "LSP guard: Java VM mismatch");
    lsp_require((*env)->GetVersion(env) >= JNI_VERSION_1_6, "LSP guard: JNI unavailable");

    char status[16384], executable[PATH_MAX], task[96];
    lsp_require(lsp_read_small("/proc/self/status", status, sizeof(status)), "LSP guard: process status absent");
    long tgid = -1, pid = -1;
    unsigned long uid = ULONG_MAX;
    for (char *line = status; line != NULL && *line; ) {
        if (!strncmp(line, "Tgid:", 5)) (void)sscanf(line + 5, "%ld", &tgid);
        if (!strncmp(line, "Pid:", 4)) (void)sscanf(line + 4, "%ld", &pid);
        if (!strncmp(line, "Uid:", 4)) (void)sscanf(line + 4, "%lu", &uid);
        line = strchr(line, '\n');
        if (line != NULL) ++line;
    }
    lsp_require(tgid == getpid() && pid == getpid() && uid == getuid(), "LSP guard: kernel process mismatch");
    snprintf(task, sizeof(task), "/proc/self/task/%ld", (long)gettid());
    struct stat task_info;
    lsp_require(stat(task, &task_info) == 0 && S_ISDIR(task_info.st_mode), "LSP guard: kernel thread absent");
    lsp_require(lsp_link("/proc/self/exe", executable, sizeof(executable)), "LSP guard: process executable absent");
    const char *basename = strrchr(executable, '/');
    lsp_require(basename != NULL && (!strcmp(basename, "/app_process64") || !strcmp(basename, "/app_process")),
                "LSP guard: process is not Android app_process");

    char sdk[PROP_VALUE_MAX];
    lsp_require(__system_property_get("ro.build.version.sdk", sdk) > 0, "LSP guard: Android properties absent");
    char *end = NULL;
    long api = strtol(sdk, &end, 10);
    lsp_require(end != sdk && *end == '\0' && api >= 28 && api < 1000, "LSP guard: unsupported Android environment");
    jclass version = lsp_class(env, "android/os/Build$VERSION");
    jfieldID sdk_field = (*env)->GetStaticFieldID(env, version, "SDK_INT", "I");
    lsp_jni_ok(env);
    lsp_require(sdk_field != NULL && (*env)->GetStaticIntField(env, version, sdk_field) == api,
                "LSP guard: Android API mismatch");
    (*env)->DeleteLocalRef(env, version);
    jclass process = lsp_class(env, "android/os/Process");
    jint java_pid = (*env)->CallStaticIntMethod(env, process, lsp_static_method(env, process, "myPid", "()I"));
    jint java_uid = (*env)->CallStaticIntMethod(env, process, lsp_static_method(env, process, "myUid", "()I"));
    lsp_jni_ok(env);
    lsp_require(java_pid == getpid() && (uid_t)java_uid == getuid(), "LSP guard: Android process mismatch");
    (*env)->DeleteLocalRef(env, process);

    int binder = open("/dev/binder", O_RDONLY | O_CLOEXEC);
    if (binder < 0) binder = open("/dev/binderfs/binder", O_RDONLY | O_CLOEXEC);
    lsp_require(binder >= 0, "LSP guard: Binder unavailable");
    struct binder_version protocol = {0};
    int binder_result = ioctl(binder, BINDER_VERSION, &protocol);
    struct stat binder_info;
    int binder_stat = fstat(binder, &binder_info);
    close(binder);
    lsp_require(binder_result == 0 && binder_stat == 0 && S_ISCHR(binder_info.st_mode) &&
                protocol.protocol_version == BINDER_CURRENT_PROTOCOL_VERSION, "LSP guard: Binder driver mismatch");

    FILE *maps = fopen("/proc/self/maps", "re");
    lsp_require(maps != NULL, "LSP guard: process mappings absent");
    char line[PATH_MAX + 256];
    int art = 0, guest_vdso = 0;
    size_t count = 0;
    while (count++ < LSP_MAX_MAP_LINES && fgets(line, sizeof(line), maps) != NULL) {
        unsigned long start, finish, offset;
        char permissions[5];
        int used = 0;
        if (sscanf(line, "%lx-%lx %4s %lx %*s %*s %n", &start, &finish, permissions, &offset, &used) != 4 ||
            permissions[0] != 'r' || permissions[1] == 'w' || permissions[2] != 'x' || used <= 0) continue;
        char *path = line + used;
        path[strcspn(path, "\r\n")] = '\0';
        if (strstr(path, "/libart.so") || strstr(path, "/libartd.so")) art = 1;
        if (!strcmp(path, "/system/lib64/arm64/libnative_bridge_vdso.so")) guest_vdso = 1;
    }
    fclose(maps);
    /* Google's full Android ARM64 translator exposes guest mappings here, excluding the
     * host ART image. Require its actual guest runtime mapping and both platform properties;
     * an emulator flag alone never substitutes for the JNI, kernel and Binder checks. */
    char native_bridge[PROP_VALUE_MAX], translated_isa[PROP_VALUE_MAX];
    int translated_art = guest_vdso &&
        __system_property_get("ro.dalvik.vm.native.bridge", native_bridge) > 0 &&
        !strcmp(native_bridge, "libndk_translation.so") &&
        __system_property_get("ro.dalvik.vm.isa.arm64", translated_isa) > 0 &&
        !strcmp(translated_isa, "x86_64");
    lsp_require(art || translated_art, "LSP guard: ART executable mapping absent");
}

/* Resolve the package service from the native Binder context manager. Both ActivityThread's
 * package-manager object and ServiceManager's Java cache are mutable application memory. */
static jobject lsp_system_package_manager(JNIEnv *env) {
    jclass binder_internal = lsp_class(env, "com/android/internal/os/BinderInternal");
    jobject context = (*env)->CallStaticObjectMethod(env, binder_internal,
        lsp_static_method(env, binder_internal, "getContextObject", "()Landroid/os/IBinder;"));
    lsp_jni_ok(env);
    jclass binder_proxy = lsp_class(env, "android/os/BinderProxy");
    lsp_require(context != NULL && (*env)->IsInstanceOf(env, context, binder_proxy),
                "LSP guard: native Binder context manager unavailable");
    jclass service_native = lsp_class(env, "android/os/ServiceManagerNative");
    jobject service_manager = (*env)->CallStaticObjectMethod(env, service_native,
        lsp_static_method(env, service_native, "asInterface", "(Landroid/os/IBinder;)Landroid/os/IServiceManager;"), context);
    lsp_jni_ok(env);
    lsp_require(service_manager != NULL, "LSP guard: system service manager unavailable");
    jclass service_proxy = lsp_class(env, "android/os/ServiceManagerProxy");
    jclass actual_service_type = (*env)->GetObjectClass(env, service_manager);
    lsp_require((*env)->IsSameObject(env, actual_service_type, service_proxy),
                "LSP guard: substituted service manager rejected");
    jstring package_service = (*env)->NewStringUTF(env, "package");
    lsp_jni_ok(env);
    lsp_require(package_service != NULL, "LSP guard: service lookup allocation failed");
    jobject package_binder = (*env)->CallNonvirtualObjectMethod(env, service_manager, service_proxy,
        lsp_method(env, service_proxy, "checkService", "(Ljava/lang/String;)Landroid/os/IBinder;"), package_service);
    lsp_jni_ok(env);
    lsp_require(package_binder != NULL && (*env)->IsInstanceOf(env, package_binder, binder_proxy),
                "LSP guard: system package service unavailable");
    jclass package_stub = lsp_class(env, "android/content/pm/IPackageManager$Stub");
    jobject manager = (*env)->CallStaticObjectMethod(env, package_stub,
        lsp_static_method(env, package_stub, "asInterface", "(Landroid/os/IBinder;)Landroid/content/pm/IPackageManager;"), package_binder);
    lsp_jni_ok(env);
    lsp_require(manager != NULL, "LSP guard: package service proxy unavailable");
    (*env)->DeleteLocalRef(env, package_stub);
    (*env)->DeleteLocalRef(env, package_binder);
    (*env)->DeleteLocalRef(env, package_service);
    (*env)->DeleteLocalRef(env, actual_service_type);
    (*env)->DeleteLocalRef(env, service_proxy);
    (*env)->DeleteLocalRef(env, service_manager);
    (*env)->DeleteLocalRef(env, service_native);
    (*env)->DeleteLocalRef(env, binder_proxy);
    (*env)->DeleteLocalRef(env, context);
    (*env)->DeleteLocalRef(env, binder_internal);
    return manager;
}

/* Query the real package-manager Binder service using the kernel process name. UID agreement
 * and the APK certificate are mandatory; a matching mutable ApplicationInfo is insufficient. */
static jobject lsp_installed_application(JNIEnv *env, const char *process) {
    char package[512];
    lsp_require(strlen(process) < sizeof(package), "LSP guard: invalid process name");
    strcpy(package, process);
    char *suffix = strchr(package, ':');
    if (suffix != NULL) *suffix = '\0';
    jobject manager = lsp_system_package_manager(env);
    jclass manager_type = lsp_class(env, "android/content/pm/IPackageManager$Stub$Proxy");
    jclass actual_manager_type = (*env)->GetObjectClass(env, manager);
    lsp_require((*env)->IsSameObject(env, actual_manager_type, manager_type),
                "LSP guard: substituted package manager rejected");
    (*env)->DeleteLocalRef(env, actual_manager_type);
    jobject binder = (*env)->CallNonvirtualObjectMethod(env, manager, manager_type,
        lsp_method(env, manager_type, "asBinder", "()Landroid/os/IBinder;"));
    lsp_jni_ok(env);
    lsp_require(binder != NULL, "LSP guard: package manager Binder absent");
    jclass proxy_type = lsp_class(env, "android/os/BinderProxy");
    jclass binder_type = lsp_class(env, "android/os/IBinder");
    lsp_require((*env)->IsInstanceOf(env, binder, proxy_type) &&
                (*env)->CallBooleanMethod(env, binder, lsp_method(env, binder_type, "pingBinder", "()Z")),
                "LSP guard: package manager is not a live remote Binder");
    lsp_jni_ok(env);
    jstring package_string = (*env)->NewStringUTF(env, package);
    lsp_jni_ok(env);
    lsp_require(package_string != NULL, "LSP guard: package lookup allocation failed");
    jmethodID query = (*env)->GetMethodID(env, manager_type, "getApplicationInfo",
        "(Ljava/lang/String;JI)Landroid/content/pm/ApplicationInfo;");
    jobject info;
    /* The AIDL flags widened from int to long in Android 13. */
    if (query != NULL && !(*env)->ExceptionCheck(env)) {
        info = (*env)->CallNonvirtualObjectMethod(env, manager, manager_type, query, package_string, (jlong)0, (jint)(getuid() / 100000));
    } else {
        (*env)->ExceptionClear(env);
        query = lsp_method(env, manager_type, "getApplicationInfo",
            "(Ljava/lang/String;II)Landroid/content/pm/ApplicationInfo;");
        info = (*env)->CallNonvirtualObjectMethod(env, manager, manager_type, query, package_string, (jint)0, (jint)(getuid() / 100000));
    }
    lsp_jni_ok(env);
    lsp_require(info != NULL, "LSP guard: installed host application absent");
    (*env)->DeleteLocalRef(env, package_string);
    (*env)->DeleteLocalRef(env, binder_type);
    (*env)->DeleteLocalRef(env, proxy_type);
    (*env)->DeleteLocalRef(env, binder);
    (*env)->DeleteLocalRef(env, manager_type);
    (*env)->DeleteLocalRef(env, manager);
    return info;
}

/* Native discovery reads the bound application even during attachBaseContext, before
 * currentApplication() is populated, and compares it to the real package-manager service. */
static int lsp_host(JNIEnv *env, char source[PATH_MAX]) {
    char package[512], process[512];
    lsp_require(lsp_read_small("/proc/self/cmdline", process, sizeof(process)), "LSP guard: process name unavailable");
    jclass thread_type = lsp_class(env, "android/app/ActivityThread");
    jobject thread = (*env)->CallStaticObjectMethod(env, thread_type,
        lsp_static_method(env, thread_type, "currentActivityThread", "()Landroid/app/ActivityThread;"));
    lsp_jni_ok(env);
    lsp_require(thread != NULL, "LSP guard: Android application is not bound yet");
    jobject bound = NULL;
    jobject info = NULL;
    if (thread != NULL) bound = (*env)->GetObjectField(env, thread,
        lsp_field(env, thread_type, "mBoundApplication", "Landroid/app/ActivityThread$AppBindData;"));
    if (bound != NULL) {
        jclass bound_type = lsp_class(env, "android/app/ActivityThread$AppBindData");
        info = (*env)->GetObjectField(env, bound,
            lsp_field(env, bound_type, "appInfo", "Landroid/content/pm/ApplicationInfo;"));
        (*env)->DeleteLocalRef(env, bound_type);
    }
    lsp_require(info != NULL, "LSP guard: Android application binding absent");
    /* Bound ApplicationInfo is mutable app memory. The Binder query is authoritative;
     * treating the bound object's uid field as independent evidence permits shell replay. */
    jobject installed_info = lsp_installed_application(env, process);
    jobject bound_info = info;
    info = installed_info;
    lsp_require(info != NULL, "LSP guard: application information absent");
    jclass info_type = lsp_class(env, "android/content/pm/ApplicationInfo");
    jstring package_string = (*env)->GetObjectField(env, info, lsp_field(env, info_type, "packageName", "Ljava/lang/String;"));
    jstring source_string = (*env)->GetObjectField(env, info, lsp_field(env, info_type, "sourceDir", "Ljava/lang/String;"));
    jint uid = (*env)->GetIntField(env, info, lsp_field(env, info_type, "uid", "I"));
    lsp_jni_ok(env);
    lsp_require(lsp_java_string(env, package_string, package, sizeof(package)) &&
                lsp_java_string(env, source_string, source, PATH_MAX) && source[0] == '/' &&
                (uid_t)uid == getuid(),
                "LSP guard: bound application identity mismatch");
    char bound_package[512], bound_source[PATH_MAX];
    jstring bound_package_string = (*env)->GetObjectField(env, bound_info,
        lsp_field(env, info_type, "packageName", "Ljava/lang/String;"));
    jstring bound_source_string = (*env)->GetObjectField(env, bound_info,
        lsp_field(env, info_type, "sourceDir", "Ljava/lang/String;"));
    jint bound_uid = (*env)->GetIntField(env, bound_info, lsp_field(env, info_type, "uid", "I"));
    lsp_jni_ok(env);
    lsp_require(lsp_java_string(env, bound_package_string, bound_package, sizeof(bound_package)) &&
                lsp_java_string(env, bound_source_string, bound_source, sizeof(bound_source)) &&
                !strcmp(bound_package, package) && !strcmp(bound_source, source) && bound_uid == uid,
                "LSP guard: bound application differs from package manager");
    (*env)->DeleteLocalRef(env, bound_package_string);
    (*env)->DeleteLocalRef(env, bound_source_string);
    (*env)->DeleteLocalRef(env, bound_info);

    int own_app = !strcmp(package, LSP_GUARD_APPLICATION_ID);
    const uint8_t (*signers)[32] = NULL;
    size_t signer_count = 0;
    if (own_app && lsp_process_matches(process, package, 1)) {
        signers = lsp_guard_module_signers;
        signer_count = LSP_GUARD_MODULE_SIGNER_COUNT;
    } else {
        for (size_t index = 0; index < LSP_GUARD_HOST_COUNT; ++index) {
            const lsp_guard_host_policy *host = &lsp_guard_hosts[index];
            if (!strcmp(package, host->package_name) &&
                lsp_process_matches(process, host->package_name, 1)) {
                signers = host->signers;
                signer_count = host->signer_count;
                break;
            }
        }
    }
    lsp_require(signers != NULL && signer_count != 0, "LSP guard: unauthorized application host");
    /* Independently anchor the configured package to the kernel's application UID. This
     * also rejects a forged framework ApplicationInfo paired with a genuine host APK. */
    char data_path[PATH_MAX];
    struct stat data_info;
    snprintf(data_path, sizeof(data_path), "/data/user/%lu/%s", (unsigned long)getuid() / 100000, package);
    int owns_data = stat(data_path, &data_info) == 0 && S_ISDIR(data_info.st_mode) && data_info.st_uid == getuid();
    if (!owns_data) {
        snprintf(data_path, sizeof(data_path), "/data/user_de/%lu/%s", (unsigned long)getuid() / 100000, package);
        owns_data = stat(data_path, &data_info) == 0 && S_ISDIR(data_info.st_mode) && data_info.st_uid == getuid();
    }
    lsp_require(owns_data, "LSP guard: package data owner does not match process UID");
    int fd = open(source, O_RDONLY | O_CLOEXEC);
    lsp_require(fd >= 0, "LSP guard: host APK unavailable");
    int verified = lsp_apk_verify(fd, signers, signer_count);
    close(fd);
    lsp_require(verified, "LSP guard: host APK signature or contents rejected");

    (*env)->DeleteLocalRef(env, source_string);
    (*env)->DeleteLocalRef(env, package_string);
    (*env)->DeleteLocalRef(env, info_type);
    (*env)->DeleteLocalRef(env, info);
    (*env)->DeleteLocalRef(env, bound);
    (*env)->DeleteLocalRef(env, thread);
    (*env)->DeleteLocalRef(env, thread_type);
    return own_app;
}

static void lsp_add_fd(lsp_discovery *discovery, int fd) {
    if (fd < 0) return;
    struct stat info;
    unsigned char magic[4];
    if (fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size < 22 ||
        pread(fd, magic, sizeof(magic), 0) != sizeof(magic) || memcmp(magic, "PK\003\004", 4)) {
        close(fd);
        return;
    }
    for (size_t index = 0; index < discovery->count; ++index) {
        if (discovery->files[index].device == info.st_dev && discovery->files[index].inode == info.st_ino) {
            close(fd);
            return;
        }
    }
    if (discovery->count >= LSP_MAX_APKS) {
        close(fd);
        lsp_fail("LSP guard: too many APK candidates");
    }
    discovery->files[discovery->count++] = (lsp_candidate){ fd, info.st_dev, info.st_ino };
}

static void lsp_add_path(lsp_discovery *discovery, const char *path) {
    if (path != NULL && path[0] == '/') lsp_add_fd(discovery, open(path, O_RDONLY | O_CLOEXEC));
}

static jobject lsp_bridge_loader(JNIEnv *env, jclass bridge, lsp_discovery *discovery) {
    jclass type = lsp_class(env, "java/lang/Class");
    jobject loader = (*env)->CallObjectMethod(env, bridge, lsp_method(env, type, "getClassLoader", "()Ljava/lang/ClassLoader;"));
    lsp_jni_ok(env);
    lsp_require(loader != NULL, "LSP guard: bootstrap bridge loader rejected");
    jclass dex_loader = lsp_class(env, "dalvik/system/BaseDexClassLoader");
    lsp_require((*env)->IsInstanceOf(env, loader, dex_loader), "LSP guard: non-Android bridge loader rejected");
    jclass memory_loader = lsp_class(env, "dalvik/system/InMemoryDexClassLoader");
    discovery->memory_loader = (*env)->IsInstanceOf(env, loader, memory_loader);
    /* Call the platform implementation, not a subclass's overridable description. */
    jstring value = (*env)->CallNonvirtualObjectMethod(env, loader, dex_loader,
        lsp_method(env, dex_loader, "toString", "()Ljava/lang/String;"));
    lsp_jni_ok(env);
    lsp_require(value != NULL, "LSP guard: bridge classpath absent");
    const char *description = (*env)->GetStringUTFChars(env, value, NULL);
    lsp_jni_ok(env);
    lsp_require(description != NULL && strlen(description) <= LSP_MAX_DESCRIPTION, "LSP guard: invalid bridge classpath");
    /* LSPosed's ByteBufferDexClassLoader directly subclasses BaseDexClassLoader. Its
     * DexFile instances are in-memory too, without an InMemoryDexClassLoader superclass. */
    if (strstr(description, "InMemoryDexFile[") != NULL) discovery->memory_loader = 1;
    const char *cursor = description;
    while ((cursor = strchr(cursor, '"')) != NULL) {
        const char *finish = strchr(++cursor, '"');
        if (finish == NULL) break;
        size_t size = (size_t)(finish - cursor);
        if (size > 4 && size < PATH_MAX && *cursor == '/' && !memcmp(finish - 4, ".apk", 4)) {
            lsp_require(discovery->origin_count < LSP_MAX_ORIGINS, "LSP guard: too many bridge origins");
            char *path = discovery->origins[discovery->origin_count++];
            memcpy(path, cursor, size);
            path[size] = '\0';
            lsp_add_path(discovery, path);
        }
        cursor = finish + 1;
    }
    (*env)->ReleaseStringUTFChars(env, value, description);
    (*env)->DeleteLocalRef(env, value);
    (*env)->DeleteLocalRef(env, memory_loader);
    (*env)->DeleteLocalRef(env, dex_loader);
    (*env)->DeleteLocalRef(env, type);
    return loader;
}

static void lsp_find_apks(lsp_discovery *discovery, const char *library, const char *host_source) {
    lsp_add_path(discovery, host_source);
    char path[PATH_MAX];
    if (library != NULL && strlen(library) < sizeof(path)) {
        strcpy(path, library);
        char *apk = strstr(path, ".apk!");
        if (apk != NULL) { apk[4] = '\0'; lsp_add_path(discovery, path); }
        else {
            /* Standard PackageManager extraction: <install>/lib/<isa>/<library>. */
            char *lib = strstr(path, "/lib/");
            if (lib != NULL) {
                size_t prefix = (size_t)(lib - path);
                if (prefix + sizeof("/base.apk") <= sizeof(path)) {
                    strcpy(path + prefix, "/base.apk");
                    lsp_add_path(discovery, path);
                }
            }
        }
    }
    DIR *directory = opendir("/proc/self/fd");
    lsp_require(directory != NULL, "LSP guard: descriptor discovery unavailable");
    struct dirent *entry;
    size_t visited = 0;
    while ((entry = readdir(directory)) != NULL && visited++ < LSP_MAX_FDS) {
        char *end = NULL;
        long fd = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end != '\0' || fd < 0 || fd > INT_MAX || fd == dirfd(directory)) continue;
        /* Opening the proc descriptor produces an independent offset and pins the inode. */
        snprintf(path, sizeof(path), "/proc/self/fd/%ld", fd);
        struct stat info;
        if (stat(path, &info) == 0 && S_ISREG(info.st_mode)) lsp_add_path(discovery, path);
    }
    closedir(directory);
    lsp_require(visited < LSP_MAX_FDS, "LSP guard: descriptor discovery exceeded bound");

    FILE *maps = fopen("/proc/self/maps", "re");
    lsp_require(maps != NULL, "LSP guard: APK mappings absent");
    char line[PATH_MAX + 256];
    size_t lines = 0;
    while (lines++ < LSP_MAX_MAP_LINES && fgets(line, sizeof(line), maps) != NULL) {
        char *begin = strchr(line, '/');
        if (begin == NULL) continue;
        char *finish = strstr(begin, ".apk");
        if (finish == NULL || (finish[4] != '\n' && finish[4] != '\0' && finish[4] != ' ' && finish[4] != '!')) continue;
        finish[4] = '\0';
        lsp_add_path(discovery, begin);
    }
    fclose(maps);
}

static int lsp_origin_matches(const lsp_discovery *discovery, const lsp_candidate *candidate, int own_app) {
    if (discovery->memory_loader) return !own_app;
    for (size_t index = 0; index < discovery->origin_count; ++index) {
        struct stat info;
        if (stat(discovery->origins[index], &info) == 0 && info.st_dev == candidate->device &&
            info.st_ino == candidate->inode) return 1;
    }
    return 0;
}

static int lsp_file_matches(const char *path, const uint8_t *contents, size_t size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    struct stat info;
    int matches = fstat(fd, &info) == 0 && S_ISREG(info.st_mode) && info.st_size >= 0 && (uint64_t)info.st_size == size;
    uint8_t buffer[16384];
    for (size_t offset = 0; matches && offset < size; ) {
        size_t count = size - offset < sizeof(buffer) ? size - offset : sizeof(buffer);
        ssize_t received = pread(fd, buffer, count, (off_t)offset);
        matches = received == (ssize_t)count && !memcmp(buffer, contents + offset, count);
        offset += count;
    }
    close(fd);
    return matches;
}

static int lsp_memory_readable(uintptr_t address, size_t size) {
    if (size == 0 || address > UINTPTR_MAX - size) return 0;
    uintptr_t finish = address + size;
    FILE *maps = fopen("/proc/self/maps", "re");
    if (maps == NULL) return 0;
    char line[PATH_MAX + 256];
    size_t count = 0;
    while (address < finish && count++ < LSP_MAX_MAP_LINES && fgets(line, sizeof(line), maps) != NULL) {
        unsigned long start, end;
        char permissions[5];
        if (sscanf(line, "%lx-%lx %4s", &start, &end, permissions) != 3) continue;
        if (address >= start && address < end && permissions[0] == 'r' && permissions[1] != 'w') {
            address = end;
        }
    }
    fclose(maps);
    return address >= finish;
}

static int lsp_library_matches(const Dl_info *loaded, const uint8_t *bytes, size_t size) {
    if (size < sizeof(Elf64_Ehdr) || loaded->dli_fbase == NULL || loaded->dli_fname == NULL) return 0;
    Elf64_Ehdr header;
    memcpy(&header, bytes, sizeof(header));
    if (memcmp(header.e_ident, ELFMAG, SELFMAG) || header.e_ident[EI_CLASS] != ELFCLASS64 ||
        header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_type != ET_DYN || header.e_machine != EM_AARCH64 ||
        header.e_phnum == 0 || header.e_phnum > 128 || header.e_phentsize != sizeof(Elf64_Phdr) ||
        header.e_phoff > size || (uint64_t)header.e_phnum * sizeof(Elf64_Phdr) > size - header.e_phoff) return 0;
    int executable = 0;
    for (size_t index = 0; index < header.e_phnum; ++index) {
        Elf64_Phdr segment;
        memcpy(&segment, bytes + header.e_phoff + index * sizeof(segment), sizeof(segment));
        if (segment.p_type != PT_LOAD || (segment.p_flags & PF_W) || segment.p_filesz == 0) continue;
        if (!(segment.p_flags & PF_R) || segment.p_filesz > segment.p_memsz || segment.p_offset > size ||
            segment.p_filesz > size - segment.p_offset || segment.p_vaddr > UINTPTR_MAX - (uintptr_t)loaded->dli_fbase) return 0;
        uintptr_t address = (uintptr_t)loaded->dli_fbase + segment.p_vaddr;
        if (!lsp_memory_readable(address, segment.p_filesz) ||
            memcmp((const void *)address, bytes + segment.p_offset, segment.p_filesz)) return 0;
        if (segment.p_flags & PF_X) executable = 1;
    }
    if (!executable) return 0;
    /* APK-mapped code is compared above. Extracted code additionally matches every on-disk byte. */
    return strstr(loaded->dli_fname, ".apk!") != NULL || lsp_file_matches(loaded->dli_fname, bytes, size);
}

void lsp_guard_init(JavaVM *vm, JNIEnv *env, jclass bridge) {
    lsp_require(vm != NULL && env != NULL && bridge != NULL && lsp_verified_vm == NULL,
                "LSP guard: invalid initialization");
    lsp_environment(vm, env);
    lsp_frida_check(1);
    char host_source[PATH_MAX];
    int own_app = lsp_host(env, host_source);
    /* Heap allocation avoids consuming ~130 KiB on Android's smaller managed-thread stacks. */
    lsp_discovery *discovery = calloc(1, sizeof(*discovery));
    lsp_require(discovery != NULL, "LSP guard: discovery allocation failed");
    jobject loader = lsp_bridge_loader(env, bridge, discovery);
    Dl_info loaded;
    memset(&loaded, 0, sizeof(loaded));
    lsp_require(dladdr((const void *)&lsp_guard_init, &loaded) != 0 && loaded.dli_fname != NULL,
                "LSP guard: own native library not mapped");
    lsp_find_apks(discovery, loaded.dli_fname, host_source);
    int verified = 0;
    for (size_t index = 0; index < discovery->count && !verified; ++index) {
        lsp_candidate *candidate = &discovery->files[index];
        if (!lsp_origin_matches(discovery, candidate, own_app)) continue;
        if (!lsp_apk_verify(candidate->fd, lsp_guard_module_signers, LSP_GUARD_MODULE_SIGNER_COUNT)) continue;
        uint8_t *library = NULL;
        size_t size = 0;
        if (lsp_apk_read_entry(candidate->fd, LSP_GUARD_LIBRARY_ENTRY, &library, &size))
            verified = lsp_library_matches(&loaded, library, size);
        free(library);
    }
    for (size_t index = 0; index < discovery->count; ++index) close(discovery->files[index].fd);
    free(discovery);
    lsp_require(verified, "LSP guard: module signer, classpath or native integrity rejected");
    lsp_verified_bridge = (*env)->NewGlobalRef(env, bridge);
    lsp_verified_loader = (*env)->NewGlobalRef(env, loader);
    (*env)->DeleteLocalRef(env, loader);
    lsp_jni_ok(env);
    lsp_require(lsp_verified_bridge != NULL && lsp_verified_loader != NULL, "LSP guard: bridge retention failed");
    lsp_verified_pid = getpid();
    lsp_verified_uid = getuid();
    lsp_verified_vm = vm;
}

void lsp_guard_check(JNIEnv *env, jclass bridge) {
    lsp_require(lsp_verified_vm != NULL && env != NULL && bridge != NULL &&
                getpid() == lsp_verified_pid && getuid() == lsp_verified_uid &&
                (*env)->IsSameObject(env, bridge, lsp_verified_bridge), "LSP guard: unauthorized decoder caller");
    lsp_frida_check(0);
}
