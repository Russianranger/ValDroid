#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int queries;
static int test_fstat(int fd, struct stat* st) {
    if (fd < 400 || fd > 406) return fstat(fd, st);
    memset(st, 0, sizeof(*st)); st->st_mode = fd == 406 ? S_IFREG : S_IFCHR; st->st_rdev = fd;
    return 0;
}
static int test_ioctl(int fd, unsigned long req, void* data) {
    queries++;
    unsigned type = (req >> 8) & 0xff, nr = req & 0xff, size = (req >> 16) & 0x3fff;
    if (type == 'E' && nr == 0x21 && fd != 405) {
        memset(data, 0, size);
        // Gamepad, joystick, mouse, keyboard, touchscreen, legacy js, regular file.
        unsigned codes[] = {0x130, 0x120, 0x110, 30, 0x14a, 0, 0x130};
        unsigned key = codes[fd - 400];
        if (key / 8 < size) ((unsigned char*)data)[key / 8] |= 1u << (key % 8);
        return (int)size;
    }
    if (type == 'j' && nr == 1 && fd == 405) { *(int32_t*)data = 0x020100; return 0; }
    if ((type == 'E' && nr == 6) || (type == 'j' && nr == 0x13)) {
        snprintf(data, size, "Same name on every fake device"); return 0;
    }
    errno = ENOTTY; return -1;
}

#define VD_PAD_TEST
#define fstat test_fstat
#define ioctl test_ioctl
#include "../../main/cpp/valdroid_pad.c"
#undef ioctl
#undef fstat

int main(void) {
    unsigned long name = _IOC(_IOC_READ, 'E', 6, 128), js = VD_INPUT_JS_VERSION;
    unsetenv("VALDROID_VIRTUAL_GAMEPAD_ONLY");
    assert(!rd_pad_block_guest_controller_ioctl(400, name) && queries == 0);
    setenv("VALDROID_VIRTUAL_GAMEPAD_ONLY", "1", 1);
    assert(rd_pad_block_guest_controller_ioctl(400, name) && errno == ENODEV);
    assert(rd_pad_block_guest_controller_ioctl(401, VD_INPUT_KEY_CAPS));
    // Even the legacy probe against an evdev controller must fail; fd/name/path don't matter.
    assert(rd_pad_block_guest_controller_ioctl(400, js));
    assert(rd_pad_block_guest_controller_ioctl(405, js));
    for (int fd = 402; fd <= 404; fd++) { // retain mouse, keyboard, touchscreen
        errno = EDOM;
        assert(!rd_pad_block_guest_controller_ioctl(fd, name) && errno == EDOM);
        assert(!rd_pad_block_guest_controller_ioctl(fd, js) && errno == EDOM);
    }
    assert(!rd_pad_block_guest_controller_ioctl(406, name)); // not a device
    assert(!rd_pad_block_guest_controller_ioctl(-1, name));
    int before = queries;
    assert(!rd_pad_block_guest_controller_ioctl(400, _IOC(_IOC_READ, 'T', 1, 4)));
    assert(queries == before); // non-input ioctl untouched
    int virtual_fd = rd_pad_open(O_NONBLOCK);
    assert(virtual_fd >= 0 && !rd_pad_block_guest_controller_ioctl(virtual_fd, name));
    unsigned char keys[96];
    assert(rd_pad_ioctl(virtual_fd, VD_INPUT_KEY_CAPS, keys) == 96);
    assert(vd_input_has_controller_buttons(keys, sizeof(keys))); // mapped pad remains available
    close(virtual_fd);
    setenv("VALDROID_VIRTUAL_GAMEPAD_ONLY", "0", 1);
    before = queries;
    assert(!rd_pad_block_guest_controller_ioctl(400, name) && queries == before);
    puts("PASS: physical evdev/js blocked; virtual pad, keyboard, mouse, touch and unrelated ioctls preserved");
    return 0;
}
