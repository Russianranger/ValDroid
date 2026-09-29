#include <assert.h>
#include <stdio.h>
#include "../../../../box64/src/wrapped/rd_unity_input_trace.h"

static int lines, last_decoded;
static uint32_t last_buttons;
static unsigned last_triggers;
static void record(int device, uint32_t format, uint32_t id, double time, int update,
    const unsigned char* state, size_t size, int decoded, uint32_t buttons, unsigned triggers, void* context) {
    (void)device; (void)format; (void)id; (void)time; (void)update; (void)state; (void)size; (void)context;
    lines++; last_decoded = decoded; last_buttons = buttons; last_triggers = triggers;
}
static void put32(unsigned char* p, uint32_t value) { memcpy(p, &value, 4); }
static void event(unsigned char* p, uint32_t type, uint16_t size, uint16_t device, uint32_t format) {
    memset(p, 0, size); put32(p, type); memcpy(p + 4, &size, 2);
    memcpy(p + 6, &device, 2); put32(p + 20, format);
}

int main(void) {
    rd_input_trace_state trace = {0};
    rd_input_trace_add_device(&trace, 7);
    unsigned char e[128], original[128];
    event(e, RD_INPUT_STAT, 52, 7, RD_INPUT_GPAD);
    put32(e + 24, 1u << 6); // Unity South/A is bit 6, NOT evdev/SDL raw button 0.
    float lt = 1; memcpy(e + 44, &lt, 4);
    memcpy(original, e, 52);
    assert(rd_input_trace_events(&trace, e, 52, 1, 1, record, NULL) == 1);
    assert(lines == 1 && last_decoded && last_buttons == 64 && last_triggers == 1);
    assert(!memcmp(e, original, 52)); // tracing must not consume/mutate the input
    for (int i = 0; i < 2000; i++) { // axis motion during menus/loading must not exhaust button logs
        float x = i / 2000.f; memcpy(e + 28, &x, 4);
        assert(rd_input_trace_events(&trace, e, 52, 1, 2, record, NULL) == 1);
    }
    assert(lines == 1);
    event(e, RD_INPUT_DELTA, 32, 7, RD_INPUT_GPAD); put32(e + 24, 0); put32(e + 28, 1u << 12);
    assert(rd_input_trace_events(&trace, e, 32, 1, 1, record, NULL) == 1);
    assert(lines == 2 && last_buttons == 4096 && last_triggers == 1); // Start, retained LT
    event(e, RD_INPUT_DELTA, 36, 7, RD_INPUT_GPAD); put32(e + 24, 20);
    assert(rd_input_trace_events(&trace, e, 36, 1, 1, record, NULL) == 1);
    assert(lines == 3 && last_triggers == 0);
    event(e, RD_INPUT_STAT, 52, 8, RD_INPUT_GPAD); // untracked device (including keyboards) ignored
    assert(rd_input_trace_events(&trace, e, 52, 1, 1, record, NULL) == 1 && lines == 3);
    event(e, RD_INPUT_STAT, 52, 7, 0x48494420); // unknown HID format cannot be labeled GPAD
    assert(rd_input_trace_events(&trace, e, 52, 1, 1, record, NULL) == 1 && !last_decoded);
    for (int i = 0; i < 100; i++) rd_input_trace_events(&trace, e, 52, 1, 1, record, NULL);
    assert(lines == 35); // 32 raw samples, separately bounded

    rd_input_trace_add_device(&trace, 7); lines = 0;
    event(e, RD_INPUT_STAT, 52, 7, RD_INPUT_GPAD);
    for (int i = 0; i < 1000; i++) { put32(e + 24, i & 1); rd_input_trace_events(&trace, e, 52, 1, 1, record, NULL); }
    assert(lines == 512);
    // Truncation, impossible counts, partial state, large delta offsets and unaligned buffers.
    assert(rd_input_trace_events(&trace, e, 19, 1, 1, record, NULL) == -1);
    assert(rd_input_trace_events(&trace, e, 51, 1, 1, record, NULL) == -1);
    assert(rd_input_trace_events(&trace, e, 52, 2, 1, record, NULL) == -1);
    assert(rd_input_trace_events(&trace, e, 52, -1, 1, record, NULL) == -1);
    assert(rd_input_trace_events(&trace, NULL, 52, 1, 1, record, NULL) == -1);
    assert(rd_input_trace_events(&trace, NULL, 0, 0, 1, record, NULL) == 0);
    rd_input_trace_add_device(&trace, 7);
    event(e, RD_INPUT_DELTA, 32, 7, RD_INPUT_GPAD); put32(e + 24, UINT32_MAX);
    assert(rd_input_trace_events(&trace, e, 32, 1, 1, record, NULL) == 1 && !last_decoded);
    event(e + 1, RD_INPUT_STAT, 52, 7, RD_INPUT_GPAD);
    assert(rd_input_trace_events(&trace, e + 1, 52, 1, 1, record, NULL) == 1 && last_decoded);
    event(e, 0x4452454d, 24, 7, 0);
    assert(rd_input_trace_events(&trace, e, 24, 1, 1, record, NULL) == 1);
    assert(!rd_input_trace_device_for(&trace, 7));
    puts("PASS: Unity input trace decoding, partial events, bounds, privacy filter and log limits");
    return 0;
}
