// ADFA-4128 Tier 1 — ART JVMTI hot-swap agent.
//
// Attached to the (debuggable) Mini-Stubby shell via `am attach-agent`. Watches a
// drop directory (passed as the agent options string); when a `trigger` file
// appears it reads `redefine.dex` and calls JVMTI RedefineClasses on the loaded
// payload class(es), swapping method bodies IN THE RUNNING PROCESS — no reload,
// state preserved. Writes `result` = 0 (redefined) / 1 (structural, caller falls
// back to full reload).
//
// This is the same mechanism Android Studio Live Edit / Compose Hot Reload use.
// ART's RedefineClasses takes a DEX file's bytes as class_bytes.

#include "jvmti.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/stat.h>
#include <android/log.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "HotSwap", __VA_ARGS__)

static JavaVM *g_vm;
static jvmtiEnv *g_jvmti;
static char g_dir[512];

static char *read_file(const char *path, long *out_len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = (char *) malloc(n + 1);          // +1 for NUL
    if (buf && fread(buf, 1, n, f) != (size_t) n) { free(buf); buf = NULL; }
    if (buf) buf[n] = 0;                          // null-terminate (name parsing)
    fclose(f);
    if (out_len) *out_len = n;
    return buf;
}

static void write_result(int code) {
    char p[600];
    snprintf(p, sizeof(p), "%s/result", g_dir);
    FILE *f = fopen(p, "w");
    if (f) { fprintf(f, "%d\n", code); fclose(f); }
}

// Redefine, in ONE call, every loaded class whose signature starts with
// prefix_sig (e.g. "Lapp/payload/"), all from the same dex. ART's RedefineClasses
// wants a class definition for every class present in the dex, so redefining a
// single class out of a multi-class dex fails ILLEGAL_ARGUMENT — we hand it the
// whole set the way Apply Changes does. Only ONE class actually changed body;
// the rest (R, the lambda) redefine to identical bytes (harmless no-op).
static int do_redefine(const char *prefix_sig, unsigned char *dex, long dex_len) {
    jint count = 0;
    jclass *classes = NULL;
    if ((*g_jvmti)->GetLoadedClasses(g_jvmti, &count, &classes) != JVMTI_ERROR_NONE) {
        LOG("GetLoadedClasses failed");
        return 1;
    }
    // Redefine each matching loaded class INDIVIDUALLY. Multiple generations of
    // the payload's Main can be loaded (the shell keeps the previous gen's
    // classloader); a stale gen may have a different schema. Redefining one at a
    // time means a stale-gen failure doesn't block the CURRENT gen's success —
    // and the current (visible) Main is what the user sees change.
    size_t plen = strlen(prefix_sig);
    int matched = 0, ok = 0;
    for (jint i = 0; i < count; i++) {
        char *sig = NULL;
        if ((*g_jvmti)->GetClassSignature(g_jvmti, classes[i], &sig, NULL) != JVMTI_ERROR_NONE)
            continue;
        if (sig && strncmp(sig, prefix_sig, plen) == 0) {
            matched++;
            jvmtiClassDefinition def;
            def.klass = classes[i];
            def.class_byte_count = (jint) dex_len;
            def.class_bytes = dex;
            jvmtiError e = (*g_jvmti)->RedefineClasses(g_jvmti, 1, &def);
            if (e == JVMTI_ERROR_NONE) {
                ok++;
            } else {
                char *en = NULL;
                (*g_jvmti)->GetErrorName(g_jvmti, e, &en);
                LOG("RedefineClasses(%s) -> error %d (%s)", sig, e, en ? en : "?");
                if (en) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *) en);
            }
        }
        if (sig) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *) sig);
    }
    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *) classes);
    LOG("redefine %s: %d/%d succeeded (dex len=%ld)", prefix_sig, ok, matched, dex_len);
    return ok > 0 ? 0 : 1; // any success (the live gen) → OK; else fall back
}

static void handle_trigger() {
    char trg[600], dexp[600], namep[600];
    snprintf(trg, sizeof(trg), "%s/trigger", g_dir);
    snprintf(dexp, sizeof(dexp), "%s/redefine.dex", g_dir);

    long nlen = 0;
    char *name = read_file(trg, &nlen);           // trigger holds the class name (NUL-terminated)
    if (!name) return;
    for (int i = 0; name[i]; i++)                  // trim any trailing whitespace
        if (name[i] == '\n' || name[i] == '\r' || name[i] == ' ' || name[i] == '\t') { name[i] = 0; break; }

    // build an EXACT JVMTI signature: "app.payload.Main" -> "Lapp/payload/Main;"
    // (the redefine dex is single-class, so match exactly one loaded class).
    char sig[600];
    sig[0] = 'L';
    int j = 1;
    for (int i = 0; name[i] && j < 590; i++) sig[j++] = (name[i] == '.') ? '/' : name[i];
    sig[j++] = ';';
    sig[j] = 0;

    long dlen = 0;
    unsigned char *dex = (unsigned char *) read_file(dexp, &dlen);
    if (!dex) { write_result(1); remove(trg); free(name); return; }

    int code = do_redefine(sig, dex, dlen);
    write_result(code);
    remove(trg);
    free(dex);
    free(name);
}

static void *watch_thread(void *arg) {
    (void) arg;
    JNIEnv *env = NULL;
    (*g_vm)->AttachCurrentThread(g_vm, &env, NULL); // make this a VM thread
    char trg[600];
    snprintf(trg, sizeof(trg), "%s/trigger", g_dir);
    LOG("watch thread up, polling %s", trg);
    for (;;) {
        if (access(trg, F_OK) == 0) handle_trigger();
        usleep(10 * 1000); // 10 ms poll
    }
    return NULL;
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    (void) reserved;
    g_vm = vm;
    // options = absolute path of the drop dir (passed by am attach-agent =<opts>)
    // am attach-agent uses '=' to split path from options, but the /data/app
    // native-lib path contains '=' (base64 padding), so options can't be passed.
    // Hardcode the watch dir instead (the shell's private files/hotswap).
    strncpy(g_dir, (options && options[0]) ? options
            : "/data/data/com.adfa.ministubby.host/files/hotswap", sizeof(g_dir) - 1);
    mkdir(g_dir, 0700); // its existence signals "agent attached" to the service
    LOG("Agent_OnAttach, dir=%s", g_dir);

    if ((*vm)->GetEnv(vm, (void **) &g_jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
        LOG("GetEnv(JVMTI) failed");
        return JNI_ERR;
    }
    jvmtiCapabilities pot;
    memset(&pot, 0, sizeof(pot));
    (*g_jvmti)->GetPotentialCapabilities(g_jvmti, &pot);
    LOG("potential: redefine=%d redefine_any=%d retransform=%d",
        pot.can_redefine_classes, pot.can_redefine_any_class, pot.can_retransform_classes);

    // Request only what ART actually offers (can_redefine_any_class is often not
    // available in the live phase → JVMTI_ERROR_NOT_AVAILABLE for the whole set).
    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_redefine_classes = pot.can_redefine_classes;
    caps.can_redefine_any_class = pot.can_redefine_any_class;
    jvmtiError e = (*g_jvmti)->AddCapabilities(g_jvmti, &caps);
    if (e != JVMTI_ERROR_NONE) {
        LOG("AddCapabilities failed: %d — retrying with redefine_classes only", e);
        memset(&caps, 0, sizeof(caps));
        caps.can_redefine_classes = 1;
        e = (*g_jvmti)->AddCapabilities(g_jvmti, &caps);
        if (e != JVMTI_ERROR_NONE) { LOG("AddCapabilities(redefine) failed: %d", e); return JNI_ERR; }
    }
    LOG("capabilities acquired");
    pthread_t t;
    pthread_create(&t, NULL, watch_thread, NULL);
    return JNI_OK;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    return Agent_OnAttach(vm, options, reserved);
}
