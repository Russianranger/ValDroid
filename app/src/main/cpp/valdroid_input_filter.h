/* Physical controllers belong to Android's configurable mapper. The guest must not also
 * discover their evdev/js interfaces. Classify capabilities, never a controller name or VID:
 * a real Xbox 360 can have exactly the same identity as our virtual pad.
 */
#ifndef VD_INPUT_FILTER_H
#define VD_INPUT_FILTER_H
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>

typedef int (*vd_input_query_fn)(int fd, unsigned long request, void* data);
#define VD_INPUT_KEY_BYTES 96
#define VD_INPUT_KEY_CAPS _IOC(_IOC_READ, 'E', 0x21, VD_INPUT_KEY_BYTES)
#define VD_INPUT_JS_VERSION _IOC(_IOC_READ, 'j', 0x01, sizeof(int32_t))

static int vd_input_has_controller_buttons(const unsigned char* keys, size_t size) {
    // BTN_JOYSTICK .. BTN_THUMBR. Excludes keyboard keys, BTN_MOUSE, and BTN_DIGI/TOUCH.
    for (unsigned code = 0x120; code <= 0x13f; code++)
        if (code / 8 < size && (keys[code / 8] & (1u << (code % 8)))) return 1;
    return 0;
}

static int vd_input_is_physical_controller(int fd, unsigned long request, vd_input_query_fn query) {
    unsigned type = (request >> 8) & 0xff;
    if (type != 'E' && type != 'j') return 0;
    unsigned char keys[VD_INPUT_KEY_BYTES] = {0};
    if (query(fd, VD_INPUT_KEY_CAPS, keys) >= 0 && vd_input_has_controller_buttons(keys, sizeof(keys)))
        return 1;
    // Legacy /dev/input/jsN doesn't implement evdev capability queries.
    int32_t version = 0;
    return type == 'j' && query(fd, VD_INPUT_JS_VERSION, &version) >= 0 && version > 0;
}
#endif
