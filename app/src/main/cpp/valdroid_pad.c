// ValDroid — virtual evdev gamepad for the guest's SDL2.
//
// Valheim only switches to its gamepad UI and bindings when SDL (statically linked into
// UnityPlayer) finds a joystick. SDL's Linux backend enumerates /dev/input through udev, which
// Android does not have, but it also honours SDL_JOYSTICK_DEVICE: every path listed there is
// open()ed and probed with the evdev ioctls. So the launcher points that hint at
// VD_PAD_PATH, box64's open()/stat()/ioctl() wrappers route that path to this file, and the guest
// reads ordinary `struct input_event` records from a socket whose other end Java feeds from the
// physical controller or the on-screen overlay.
//
// The device identifies as a Microsoft X-Box 360 pad (bus USB, 045e:028e), which SDL's built-in
// controller database maps to a full SDL_GameController without any extra configuration.
//
// Layout of the guest side (x86_64 Linux): struct input_event = { timeval(16) u16 type u16 code
// s32 value } = 24 bytes. Each open has an independent queue (dup shares its open's queue).
// Each socket packet contains ONE record: a reader asking for one event cannot truncate a
// multi-event report. rd_pad_read coalesces records and recovers stalled readers on overflow.

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

#ifdef VD_PAD_TEST
#include <stdio.h>
static void pad_test_log(const char* format, ...) { (void)format; }
#define LOGI(...) pad_test_log(__VA_ARGS__)
#define LOGW(...) pad_test_log(__VA_ARGS__)
#define LOGE(...) pad_test_log(__VA_ARGS__)
#else
#include "logger.h"
#endif

#define VD_PAD_PATH "/dev/input/event-valdroid"

// evdev constants (linux/input-event-codes.h), spelled out so this file needs no kernel headers
#define EV_SYN 0x00
#define EV_KEY 0x01
#define EV_ABS 0x03
#define SYN_REPORT 0
#define SYN_DROPPED 3
#define BTN_A 0x130
#define BTN_B 0x131
#define BTN_X 0x133
#define BTN_Y 0x134
#define BTN_TL 0x136
#define BTN_TR 0x137
#define BTN_SELECT 0x13a
#define BTN_START 0x13b
#define BTN_MODE 0x13c
#define BTN_THUMBL 0x13d
#define BTN_THUMBR 0x13e
#define ABS_X 0x00
#define ABS_Y 0x01
#define ABS_Z 0x02
#define ABS_RX 0x03
#define ABS_RY 0x04
#define ABS_RZ 0x05
#define ABS_HAT0X 0x10
#define ABS_HAT0Y 0x11

static const uint16_t kButtons[] = { BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR, BTN_SELECT,
                                     BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR };
static const uint16_t kAxes[]    = { ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y };

static pthread_mutex_t g_mx = PTHREAD_MUTEX_INITIALIZER;
typedef struct PadClient {
    int host, overflow;
    ino_t ino;
    unsigned id, key_reads, state_queries;
    struct PadClient* next;
} PadClient;
static PadClient* g_clients;
static unsigned g_next_id, g_key_writes;

// Pending report (Java calls axis/button, then sync appends SYN_REPORT and broadcasts it)
#define VD_PAD_BATCH 32
static unsigned char g_batch[VD_PAD_BATCH * 24];
static int g_batch_n = 0;
static int g_last_abs[0x40];
static unsigned char g_buttons[sizeof(kButtons) / sizeof(kButtons[0])];

static void event_put(unsigned char* e, uint16_t type, uint16_t code, int32_t value) {
    struct timeval tv; gettimeofday(&tv, NULL);
    int64_t sec = tv.tv_sec, usec = tv.tv_usec;
    memcpy(e, &sec, 8); memcpy(e + 8, &usec, 8);
    memcpy(e + 16, &type, 2); memcpy(e + 18, &code, 2); memcpy(e + 20, &value, 4);
}

static PadClient* client_for_fd(int fd) {
    struct stat st;
    if (!g_clients || fd < 0 || fstat(fd, &st) != 0 || !S_ISSOCK(st.st_mode)) return NULL;
    for (PadClient* c = g_clients; c; c = c->next) if (c->ino == st.st_ino) return c;
    return NULL;
}

static void collect_closed(void) {
    PadClient** slot = &g_clients;
    while (*slot) {
        PadClient* c = *slot;
        struct pollfd p = { c->host, POLLOUT, 0 };
        if (poll(&p, 1, 0) > 0 && (p.revents & (POLLHUP | POLLERR))) {
            LOGI("pad: close reader=%u", c->id);
            close(c->host); *slot = c->next; free(c);
        } else slot = &c->next;
    }
}

static int send_record(PadClient* c, const unsigned char* event) {
    ssize_t n;
    do { n = send(c->host, event, 24, MSG_DONTWAIT | MSG_NOSIGNAL); } while (n < 0 && errno == EINTR);
    return n == 24;
}

static void send_snapshot(PadClient* c, int dropped) {
    unsigned char e[24];
    c->overflow = 0;
    if (dropped) {
        event_put(e, EV_SYN, SYN_DROPPED, 0); if (!send_record(c, e)) c->overflow = 1;
        event_put(e, EV_SYN, SYN_REPORT, 0); if (!send_record(c, e)) c->overflow = 1;
    }
    // Also send an explicit state report for consumers that don't implement SYN_DROPPED.
    for (size_t i = 0; i < sizeof(kButtons) / sizeof(kButtons[0]); i++) {
        event_put(e, EV_KEY, kButtons[i], g_buttons[i]); if (!send_record(c, e)) c->overflow = 1;
    }
    for (size_t i = 0; i < sizeof(kAxes) / sizeof(kAxes[0]); i++) {
        event_put(e, EV_ABS, kAxes[i], g_last_abs[kAxes[i]]); if (!send_record(c, e)) c->overflow = 1;
    }
    event_put(e, EV_SYN, SYN_REPORT, 0); if (!send_record(c, e)) c->overflow = 1;
}

static void flush_locked(void) {
    if (!g_batch_n) return;
    event_put(g_batch + g_batch_n++ * 24, EV_SYN, SYN_REPORT, 0);
    collect_closed();
    for (PadClient* c = g_clients; c; c = c->next) {
        if (c->overflow) continue; // State stays current; the next read resynchronizes this client.
        for (int i = 0; i < g_batch_n; i++) {
            if (!send_record(c, g_batch + i * 24)) {
                c->overflow = 1;
                LOGW("pad: reader=%u queue full; next read will resync held buttons/axes", c->id);
                break;
            }
        }
    }
    g_batch_n = 0;
}

// ---- guest side (called from box64's libc wrappers) ----------------------------------------

const char* rd_pad_path(void) { return VD_PAD_PATH; }

/** Separate opens must not steal each other's button presses or releases. */
int rd_pad_open(int flags) {
    pthread_mutex_lock(&g_mx);
    flush_locked();
    collect_closed();
    int sv[2];
    PadClient* c = calloc(1, sizeof(*c));
    if (!c || socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sv) != 0) {
        free(c); pthread_mutex_unlock(&g_mx); return -1;
    }
    struct stat st;
    if (fstat(sv[1], &st) != 0) {
        int error = errno; close(sv[0]); close(sv[1]); free(c);
        pthread_mutex_unlock(&g_mx); errno = error; return -1;
    }
    c->host = sv[0]; c->ino = st.st_ino; c->id = ++g_next_id;
    c->next = g_clients; g_clients = c;
    if (!(flags & O_CLOEXEC)) fcntl(sv[1], F_SETFD, 0);
    if (flags & O_NONBLOCK) fcntl(sv[1], F_SETFL, O_NONBLOCK);
    send_snapshot(c, 0);
    LOGI("pad: open reader=%u fd=%d flags=0x%x transport=v3 independent queue", c->id, sv[1], flags);
    pthread_mutex_unlock(&g_mx);
    return sv[1];
}

int rd_pad_is_fd(int fd) {
    pthread_mutex_lock(&g_mx);
    int found = client_for_fd(fd) != NULL;
    pthread_mutex_unlock(&g_mx);
    return found;
}

/** evdev reads return whole records, leave unread records queued, and recover after a load stall. */
ssize_t rd_pad_read(int fd, void* buf, size_t count) {
    if (!count) return 0;
    if (count < 24) { errno = EINVAL; return -1; }
    if (!buf) { errno = EFAULT; return -1; }
    for (;;) {
        pthread_mutex_lock(&g_mx);
        PadClient* c = client_for_fd(fd);
        if (!c) { pthread_mutex_unlock(&g_mx); errno = EBADF; return -1; }
        if (c->overflow) {
            unsigned char discard[24];
            while (recv(fd, discard, sizeof(discard), MSG_DONTWAIT) > 0 || errno == EINTR) {}
            send_snapshot(c, 1);
            LOGI("pad: resync reader=%u with current state after queue overflow", c->id);
        }
        size_t n = 0;
        while (count - n >= 24) {
            unsigned char* e = (unsigned char*)buf + n;
            ssize_t got = recv(fd, e, 24, MSG_DONTWAIT);
            if (got < 0 && errno == EINTR) continue;
            if (got != 24) break;
            uint16_t type, code; int32_t value;
            memcpy(&type, e + 16, 2); memcpy(&code, e + 18, 2); memcpy(&value, e + 20, 4);
            if (type == EV_KEY && c->key_reads++ < 512)
                LOGI("pad: read reader=%u button=0x%x down=%d", c->id, code, value);
            n += 24;
        }
        int nonblock = fcntl(fd, F_GETFL) & O_NONBLOCK;
        pthread_mutex_unlock(&g_mx);
        if (n) return (ssize_t)n;
        if (nonblock) { errno = EAGAIN; return -1; }
        // Never hold the input mutex while waiting; Java must be able to enqueue the next press.
        struct pollfd p = { fd, POLLIN, 0 };
        if (poll(&p, 1, -1) < 0) return -1;
        if (p.revents & (POLLHUP | POLLERR | POLLNVAL)) { errno = EBADF; return -1; }
    }
}

static void set_bit(unsigned char* bits, size_t len, unsigned n) {
    if (n / 8 < len) bits[n / 8] |= (unsigned char)(1u << (n % 8));
}

/** The evdev ioctls SDL issues while probing and using a joystick. Anything else: EINVAL. */
int rd_pad_ioctl(int fd, unsigned long req, void* arg) {
    unsigned type = (req >> 8) & 0xff, nr = req & 0xff, size = (req >> 16) & 0x3fff;
    if (type != 'E') { errno = EINVAL; return -1; }   // e.g. JSIOCGNAME ('j'): not a /dev/input/js device
    if (nr == 0x90 || nr == 0x91) return 0;          // GRAB / REVOKE take scalar arguments, including 0
    if (!arg) { errno = EFAULT; return -1; }
    unsigned char* out = (unsigned char*)arg;
    switch (nr) {
        case 0x01: { *(int32_t*)arg = 0x010001; return 0; }                       // EVIOCGVERSION
        case 0x02: {                                                             // EVIOCGID
            uint16_t id[4] = { 0x0003 /*BUS_USB*/, 0x045e, 0x028e, 0x0114 };
            memcpy(arg, id, sizeof(id)); return 0;
        }
        case 0x03: { ((int32_t*)arg)[0] = 0; ((int32_t*)arg)[1] = 0; return 0; }  // EVIOCGREP
        case 0x06: {                                                             // EVIOCGNAME(len)
            const char* name = "Microsoft X-Box 360 pad";
            size_t n = strlen(name) + 1; if (n > size) n = size;
            memcpy(arg, name, n); return (int)n;
        }
        case 0x07: case 0x08: { errno = ENOENT; return -1; }                     // EVIOCGPHYS / EVIOCGUNIQ
        case 0x18: {                                                           // EVIOCGKEY: current holds
            memset(out, 0, size);
            pthread_mutex_lock(&g_mx);
            unsigned held = 0;
            for (size_t i = 0; i < sizeof(kButtons) / sizeof(kButtons[0]); i++) {
                if (g_buttons[i]) { set_bit(out, size, kButtons[i]); held |= 1u << i; }
            }
            PadClient* c = client_for_fd(fd);
            if (c && c->state_queries++ < 16) LOGI("pad: query reader=%u held=0x%x", c->id, held);
            pthread_mutex_unlock(&g_mx);
            return (int)size;
        }
        case 0x09: case 0x19: case 0x1a: case 0x1b:                              // GPROP, GLED, GSND, GSW
            memset(out, 0, size); return (int)size;
        case 0x84: { *(int32_t*)arg = 0; return 0; }                              // EVIOCGEFFECTS: no rumble
        default: break;
    }
    if (nr >= 0x20 && nr < 0x40) {                                               // EVIOCGBIT(ev, len)
        unsigned ev = nr - 0x20;
        memset(out, 0, size);
        if (ev == 0) { set_bit(out, size, EV_SYN); set_bit(out, size, EV_KEY); set_bit(out, size, EV_ABS); }
        else if (ev == EV_KEY) { for (size_t i = 0; i < sizeof(kButtons) / 2; i++) set_bit(out, size, kButtons[i]); }
        else if (ev == EV_ABS) { for (size_t i = 0; i < sizeof(kAxes) / 2; i++) set_bit(out, size, kAxes[i]); }
        return (int)size;
    }
    if (nr >= 0x40 && nr < 0x80) {                                               // EVIOCGABS(abs)
        unsigned abs = nr - 0x40;
        int32_t info[6] = { 0, -32768, 32767, 16, 128, 0 };                       // value,min,max,fuzz,flat,res
        if (abs == ABS_Z || abs == ABS_RZ) { info[1] = 0; info[2] = 255; info[3] = 0; info[4] = 0; }
        else if (abs == ABS_HAT0X || abs == ABS_HAT0Y) { info[1] = -1; info[2] = 1; info[3] = 0; info[4] = 0; }
        else if (abs > ABS_RZ) { errno = EINVAL; return -1; }
        pthread_mutex_lock(&g_mx);
        info[0] = g_last_abs[abs];
        pthread_mutex_unlock(&g_mx);
        memcpy(arg, info, sizeof(info)); return 0;
    }
    if (nr >= 0xc0) return 0;                                                    // EVIOCSABS
    errno = EINVAL; return -1;
}

// ---- host side (JNI) ---------------------------------------------------------------------

static void batch_put(uint16_t type, uint16_t code, int32_t value) {
    if (g_batch_n >= VD_PAD_BATCH - 1) flush_locked();
    event_put(g_batch + g_batch_n++ * 24, type, code, value);
}

void rd_pad_button(int code, int down) {
    pthread_mutex_lock(&g_mx);
    for (size_t i = 0; i < sizeof(kButtons) / sizeof(kButtons[0]); i++) {
        if (kButtons[i] != code || g_buttons[i] == !!down) continue;
        g_buttons[i] = !!down;
        batch_put(EV_KEY, (uint16_t)code, !!down);
        if (g_key_writes++ < 512) LOGI("pad: write button=0x%x down=%d", code, !!down);
        break;
    }
    pthread_mutex_unlock(&g_mx);
}

void rd_pad_axis(int code, int value) {
    pthread_mutex_lock(&g_mx);
    if (code >= 0 && code < 0x40 && g_last_abs[code] != value) {
        g_last_abs[code] = value;
        batch_put(EV_ABS, (uint16_t)code, value);
    }
    pthread_mutex_unlock(&g_mx);
}

/** Flush a report to every reader without blocking Android's UI thread. */
void rd_pad_sync(void) {
    pthread_mutex_lock(&g_mx);
    flush_locked();
    pthread_mutex_unlock(&g_mx);
}
