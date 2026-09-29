/* Optional native-Mono boundary tracing. Invoked before the existing runtime_invoke; it never
 * consumes or changes events, registers callbacks, invokes managed code, or rewrites bindings.
 */
#include <pthread.h>
#include <sys/time.h>
#include "rd_unity_input_trace.h"

static int rd_input_trace_on;
static pthread_mutex_t rd_input_trace_mutex = PTHREAD_MUTEX_INITIALIZER;
static rd_input_trace_state rd_input_trace;
static const char* (*rd_input_method_name)(void*);
static void* (*rd_input_method_class)(void*);
static const char* (*rd_input_class_name)(void*);
static const char* (*rd_input_class_namespace)(void*);
static void* (*rd_input_method_signature)(void*);
static unsigned (*rd_input_param_count)(void*);
static int (*rd_input_string_length)(void*);
static const uint16_t* (*rd_input_string_chars)(void*);
typedef struct { void* method; unsigned kind; } rd_input_method_cache;
static __thread rd_input_method_cache rd_input_methods[64];

static void rd_input_trace_init(void) {
    const char* enabled = getenv("VALDROID_INPUT_TRACE");
    if (!enabled || strcmp(enabled, "1")) return;
    rd_input_method_name = rd_native_sym("mono_method_get_name");
    rd_input_method_class = rd_native_sym("mono_method_get_class");
    rd_input_class_name = rd_native_sym("mono_class_get_name");
    rd_input_class_namespace = rd_native_sym("mono_class_get_namespace");
    rd_input_method_signature = rd_native_sym("mono_method_signature");
    rd_input_param_count = rd_native_sym("mono_signature_get_param_count");
    rd_input_string_length = rd_native_sym("mono_string_length");
    rd_input_string_chars = rd_native_sym("mono_string_chars");
    rd_input_trace_on = rd_input_method_name && rd_input_method_class && rd_input_class_name
        && rd_input_class_namespace && rd_input_method_signature && rd_input_param_count
        && rd_input_string_length && rd_input_string_chars;
    printf_log(LOG_NONE, "[VD-INPUT] Unity boundary trace %s (read-only, gamepads only, 512 changes/device)\n",
        rd_input_trace_on ? "enabled" : "unavailable: missing Mono metadata API");
}

static unsigned rd_input_trace_method(void* method) {
    rd_input_method_cache* slot = &rd_input_methods[((uintptr_t)method >> 4) % 64];
    if (slot->method == method) return slot->kind;
    slot->method = method; slot->kind = 0;
    const char* name = rd_input_method_name(method);
    if (!name) return 0;
    unsigned kind = !strcmp(name, "NotifyDeviceDiscovered") ? 1 : !strcmp(name, "NotifyUpdate") ? 2 : 0;
    if (!kind) return 0;
    void* klass = rd_input_method_class(method);
    if (!klass) return 0;
    const char* class_name = rd_input_class_name(klass);
    const char* class_namespace = rd_input_class_namespace(klass);
    void* signature = rd_input_method_signature(method);
    if (!class_name || strcmp(class_name, "NativeInputSystem") || !class_namespace
        || strcmp(class_namespace, "UnityEngineInternal.Input") || !signature
        || rd_input_param_count(signature) != 2) return 0;
    slot->kind = kind;
    return kind;
}

static void rd_input_trace_log(int device, uint32_t format, uint32_t event_id, double time,
    int update, const unsigned char* state, size_t size, int decoded, uint32_t buttons,
    unsigned triggers, void* context) {
    (void)context;
    static const char* names[] = {"Up", "Down", "Left", "Right", "Y", "B", "A", "X",
        "L3", "R3", "LB", "RB", "Start", "Select"};
    char held[128] = "", hex[129];
    if (decoded) {
        for (unsigned i = 0; i < sizeof(names) / sizeof(names[0]); i++)
            if (buttons & (1u << i)) { strcat(held, names[i]); strcat(held, " "); }
        if (triggers & 1) strcat(held, "LT ");
        if (triggers & 2) strcat(held, "RT ");
        if (!held[0]) strcpy(held, "none");
    } else strcpy(held, "raw format; no button labels inferred");
    for (size_t i = 0; i < size; i++) snprintf(hex + i * 2, 3, "%02x", state[i]);
    hex[size * 2] = 0;
    struct timeval now; gettimeofday(&now, NULL);
    printf_log(LOG_NONE, "[VD-INPUT] wall=%lld.%03ld device=%d format=0x%08x event=%u time=%.6f update=%d buttons=0x%x held=[%s] raw=%s\n",
        (long long)now.tv_sec, (long)(now.tv_usec / 1000), device, format, event_id, time, update, buttons, held, hex);
}

static void rd_input_trace_invoke(void* method, void* params) {
    if (!rd_input_trace_on || !method || !params) return;
    unsigned kind = rd_input_trace_method(method);
    if (!kind) return;
    void** args = params;
    if (!args[0] || !args[1]) return;
    pthread_mutex_lock(&rd_input_trace_mutex);
    static unsigned observed, invalid_buffers;
    if (!(observed & (1u << kind))) {
        observed |= 1u << kind;
        printf_log(LOG_NONE, "[VD-INPUT] observing NativeInputSystem.%s\n",
            kind == 1 ? "NotifyDeviceDiscovered" : "NotifyUpdate");
    }
    if (kind == 1) {
        int id; memcpy(&id, args[0], 4);
        // mono_runtime_invoke passes reference arguments directly, value arguments by address.
        int length = rd_input_string_length(args[1]);
        const uint16_t* chars = rd_input_string_chars(args[1]);
        char descriptor[8193];
        int n = length < 8192 ? length : 8192;
        if (n < 0 || !chars) n = 0;
        for (int i = 0; i < n; i++) descriptor[i] = chars[i] >= 32 && chars[i] < 127 ? (char)chars[i] : '?';
        descriptor[n] = 0;
        if (strstr(descriptor, "Gamepad") || strstr(descriptor, "Joystick")
            || strstr(descriptor, "X-Box") || strstr(descriptor, "Xbox")) {
            rd_input_trace_add_device(&rd_input_trace, id);
            printf_log(LOG_NONE, "[VD-INPUT] device=%d descriptor=%s%s\n", id, descriptor,
                length > n ? " [truncated]" : "");
        }
    } else {
        const unsigned char* header;
        int update; memcpy(&update, args[0], 4); memcpy(&header, args[1], sizeof(header));
        if (header) {
            const void* events;
            int count, size, capacity;
            memcpy(&events, header, sizeof(events)); memcpy(&count, header + 8, 4);
            memcpy(&size, header + 12, 4); memcpy(&capacity, header + 16, 4);
            int result = -1;
            if (size >= 0 && capacity >= size)
                result = rd_input_trace_events(&rd_input_trace, events, (size_t)size, count, update, rd_input_trace_log, NULL);
            if (result < 0 && invalid_buffers < 8) {
                invalid_buffers++;
                printf_log(LOG_NONE, "[VD-INPUT] skipped invalid event buffer count=%d size=%d capacity=%d\n",
                    count, size, capacity);
            }
        }
    }
    pthread_mutex_unlock(&rd_input_trace_mutex);
}
