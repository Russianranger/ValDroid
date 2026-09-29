/* Read-only Unity 6000 NativeInputEventBuffer decoder. No engine/Mono calls or input writes.
 * ABI: UnityCsReference/6000.0/Modules/Input/Private/Input.cs and InputSystem GamepadState.
 * FourCC values are big-endian character constants stored in native little-endian integers.
 */
#ifndef RD_UNITY_INPUT_TRACE_H
#define RD_UNITY_INPUT_TRACE_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>

#define RD_INPUT_STAT 0x53544154u
#define RD_INPUT_DELTA 0x444c5441u
#define RD_INPUT_GPAD 0x47504144u
#define RD_INPUT_TRACE_DEVICES 8

typedef struct {
    int used, id;
    uint32_t format, buttons;
    unsigned triggers, logged, unknown_logged;
    uint64_t valid;
    unsigned char state[64];
    int reported;
} rd_input_trace_device;

typedef struct { rd_input_trace_device devices[RD_INPUT_TRACE_DEVICES]; } rd_input_trace_state;
typedef void (*rd_input_trace_emit)(int device, uint32_t format, uint32_t event_id,
    double event_time, int update_type, const unsigned char* state, size_t size,
    int decoded, uint32_t buttons, unsigned triggers, void* context);

static rd_input_trace_device* rd_input_trace_device_for(rd_input_trace_state* trace, int id) {
    for (int i = 0; i < RD_INPUT_TRACE_DEVICES; i++)
        if (trace->devices[i].used && trace->devices[i].id == id) return &trace->devices[i];
    return NULL;
}

static void rd_input_trace_add_device(rd_input_trace_state* trace, int id) {
    rd_input_trace_device* d = rd_input_trace_device_for(trace, id);
    if (!d) for (int i = 0; i < RD_INPUT_TRACE_DEVICES; i++)
        if (!trace->devices[i].used) { d = &trace->devices[i]; break; }
    if (d) { memset(d, 0, sizeof(*d)); d->used = 1; d->id = id; }
}

static uint32_t rd_input_u32(const void* p) { uint32_t v; memcpy(&v, p, 4); return v; }

/* Return the number of inspected events, or -1 for an invalid/truncated buffer. The caller
 * provides the byte extent; every header and payload read is bounded by that extent.
 */
static int rd_input_trace_events(rd_input_trace_state* trace, const void* buffer, size_t size,
    int count, int update_type, rd_input_trace_emit emit, void* context) {
    if (count < 0 || size > 16u * 1024 * 1024 || (count && !buffer)) return -1;
    const unsigned char* p = buffer;
    for (int i = 0; i < count; i++) {
        if (size < 20) return -1;
        uint32_t type = rd_input_u32(p), event_id = rd_input_u32(p + 16);
        uint16_t length, id;
        double time;
        memcpy(&length, p + 4, 2); memcpy(&id, p + 6, 2); memcpy(&time, p + 8, 8);
        if (length < 20 || length > size) return -1;
        rd_input_trace_device* d = rd_input_trace_device_for(trace, id);
        if (d && type == 0x4452454du) memset(d, 0, sizeof(*d)); // DREM
        if (d && d->used && (type == RD_INPUT_STAT || type == RD_INPUT_DELTA)) {
            size_t header = type == RD_INPUT_STAT ? 24 : 28;
            if (length < header) return -1;
            uint32_t format = rd_input_u32(p + 20);
            size_t offset = type == RD_INPUT_STAT ? 0 : rd_input_u32(p + 24);
            size_t bytes = length - header;
            if (format != d->format || type == RD_INPUT_STAT) {
                d->valid = 0;
                if (format != d->format) { d->reported = 0; memset(d->state, 0, sizeof(d->state)); }
                d->format = format;
            }
            for (size_t j = 0; j < bytes && offset < sizeof(d->state) && j < sizeof(d->state) - offset; j++) {
                d->state[offset + j] = p[header + j];
                d->valid |= UINT64_C(1) << (offset + j);
            }
            // Only decode the documented GPAD format. Other layouts get bounded raw samples;
            // never label arbitrary HID or joystick bytes as Xbox buttons.
            int decoded = format == RD_INPUT_GPAD && (d->valid & 0xfffffffu) == 0xfffffffu;
            uint32_t buttons = 0;
            unsigned triggers = 0;
            if (decoded) {
                float lt, rt;
                buttons = rd_input_u32(d->state);
                memcpy(&lt, d->state + 20, 4); memcpy(&rt, d->state + 24, 4);
                triggers = (lt > 0.5f ? 1u : 0u) | (rt > 0.5f ? 2u : 0u);
            }
            int changed = !d->reported || buttons != d->buttons || triggers != d->triggers;
            if (d->logged < 512 && ((decoded && changed) || (!decoded && d->unknown_logged < 32))) {
                size_t captured = 0;
                while (captured < sizeof(d->state) && (d->valid & (UINT64_C(1) << captured))) captured++;
                emit(id, format, event_id, time, update_type, d->state, captured,
                     decoded, buttons, triggers, context);
                d->logged++;
                if (!decoded) d->unknown_logged++;
            }
            if (decoded) { d->reported = 1; d->buttons = buttons; d->triggers = triggers; }
        }
        size_t stride = ((size_t)length + 3) & ~(size_t)3;
        if (stride > size) return i + 1 == count ? count : -1;
        p += stride; size -= stride;
    }
    return count;
}
#endif
