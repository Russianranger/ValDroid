/* Native evdev contract tests: run the real transport without Android/JNI. */
#define _GNU_SOURCE
#define VD_PAD_TEST
#include "../../main/cpp/valdroid_pad.c"
#include <assert.h>
#include <time.h>

typedef struct Event { int64_t sec, usec; uint16_t type, code; int32_t value; } Event;
_Static_assert(sizeof(Event) == 24, "x86_64 input_event ABI");
#define QUERY_KEYS ((2UL << 30) | (96UL << 16) | ('E' << 8) | 0x18)

static int drain(int fd, Event* events, int capacity) {
    int count = 0;
    while (count < capacity) {
        ssize_t n = rd_pad_read(fd, events + count, (size_t)(capacity - count) * sizeof(Event));
        if (n < 0) { assert(errno == EAGAIN); break; }
        assert(n > 0 && n % sizeof(Event) == 0);
        count += (int)(n / sizeof(Event));
    }
    return count;
}

static int open_empty(void) {
    int fd = rd_pad_open(O_NONBLOCK | O_CLOEXEC);
    assert(fd >= 0 && rd_pad_is_fd(fd));
    Event events[64];
    assert(drain(fd, events, 64) == 20); // 11 buttons, 8 axes, SYN_REPORT
    return fd;
}

static int key_state(int fd, int code) {
    unsigned char bits[96];
    memset(bits, 0xff, sizeof(bits));
    assert(rd_pad_ioctl(fd, QUERY_KEYS, bits) == 96);
    return !!(bits[code / 8] & (1u << (code % 8)));
}

static int has_event(const Event* e, int n, int type, int code, int value) {
    for (int i = 0; i < n; i++) if (e[i].type == type && e[i].code == code && e[i].value == value) return 1;
    return 0;
}

static void current_held_buttons(void) {
    int fd = open_empty();
    rd_pad_button(BTN_TL, 1); rd_pad_button(BTN_B, 1); rd_pad_sync();
    assert(key_state(fd, BTN_TL) && key_state(fd, BTN_B) && !key_state(fd, BTN_A));
    rd_pad_button(BTN_TL, 0); rd_pad_sync();
    assert(!key_state(fd, BTN_TL) && key_state(fd, BTN_B));
    rd_pad_button(BTN_B, 0); rd_pad_sync();
    close(fd);
}

static void independent_opens_receive_both_edges(void) {
    int a = open_empty(), b = open_empty();
    rd_pad_button(BTN_A, 1); rd_pad_sync();
    rd_pad_button(BTN_A, 0); rd_pad_sync();
    Event ea[16], eb[16];
    int na = drain(a, ea, 16), nb = drain(b, eb, 16);
    assert(na == 4 && nb == na && !memcmp(ea, eb, (size_t)na * sizeof(Event)));
    assert(has_event(ea, na, EV_KEY, BTN_A, 1) && has_event(ea, na, EV_KEY, BTN_A, 0));
    close(a); close(b);
}

static void small_reads_preserve_remainder_and_poll(void) {
    int fd = open_empty();
    rd_pad_button(BTN_X, 1); rd_pad_button(BTN_X, 0); rd_pad_sync();
    Event e;
    // Even a plain libc read cannot truncate a report now: one socket packet is one record.
    assert(read(fd, &e, sizeof(e)) == sizeof(e) && e.code == BTN_X && e.value == 1);
    struct pollfd p = { fd, POLLIN, 0 };
    assert(poll(&p, 1, 0) == 1 && (p.revents & POLLIN));
    assert(rd_pad_read(fd, &e, sizeof(e)) == sizeof(e) && e.code == BTN_X && e.value == 0);
    assert(rd_pad_read(fd, &e, sizeof(e)) == sizeof(e) && e.type == EV_SYN);
    assert(poll(&p, 1, 0) == 0);
    close(fd);
}

static void world_load_stall_recovers_lost_release(void) {
    int slow = open_empty(), fast = open_empty();
    Event e[128];
    rd_pad_button(BTN_TL, 1); rd_pad_sync();
    assert(drain(slow, e, 128) == 2);
    assert(drain(fast, e, 128) == 2);
    // Stop polling one consumer while Android continues sending reports during a load.
    for (int i = 0; i < 4000; i++) {
        rd_pad_axis(ABS_X, i + 1); rd_pad_sync();
        assert(drain(fast, e, 128) == 2); // another reader keeps working throughout
    }
    rd_pad_button(BTN_TL, 0); rd_pad_button(BTN_TR, 1); rd_pad_axis(ABS_X, 0); rd_pad_sync();
    int n = drain(slow, e, 128);
    assert(has_event(e, n, EV_SYN, SYN_DROPPED, 0));
    assert(has_event(e, n, EV_KEY, BTN_TL, 0) && has_event(e, n, EV_KEY, BTN_TR, 1));
    assert(has_event(e, n, EV_ABS, ABS_X, 0));
    assert(!key_state(slow, BTN_TL) && key_state(slow, BTN_TR));
    rd_pad_button(BTN_TR, 0); rd_pad_sync();
    n = drain(slow, e, 128);
    assert(n == 2 && has_event(e, n, EV_KEY, BTN_TR, 0));
    close(slow); close(fast);
}

static void reopen_has_current_state_not_old_taps(void) {
    int old = open_empty();
    rd_pad_button(BTN_A, 1); rd_pad_sync(); rd_pad_button(BTN_A, 0); rd_pad_sync();
    rd_pad_button(BTN_TL, 1); rd_pad_axis(ABS_Z, 127); rd_pad_sync();
    close(old);
    int fresh = rd_pad_open(O_NONBLOCK);
    Event e[64]; int n = drain(fresh, e, 64);
    assert(n == 20 && !has_event(e, n, EV_KEY, BTN_A, 1));
    assert(has_event(e, n, EV_KEY, BTN_TL, 1) && has_event(e, n, EV_ABS, ABS_Z, 127));
    assert(key_state(fresh, BTN_TL));
    rd_pad_button(BTN_TL, 0); rd_pad_axis(ABS_Z, 0); rd_pad_sync(); close(fresh);
}

static void duplicate_descriptor_shares_only_its_open(void) {
    int a = open_empty(), b = dup(a);
    assert(rd_pad_is_fd(b)); close(a);
    rd_pad_button(BTN_START, 1); rd_pad_button(BTN_START, 1); rd_pad_sync();
    Event e[8]; assert(drain(b, e, 8) == 2); // native dedupe; dup remains alive after original closes
    rd_pad_button(BTN_START, 0); rd_pad_sync();
    assert(drain(b, e, 8) == 2);
    close(b);
    int c = open_empty();
    close(c);
}

static void batches_do_not_lose_sync_or_edges(void) {
    int fd = open_empty();
    for (int i = 0; i < 100; i++) rd_pad_button(BTN_Y, !(i & 1));
    rd_pad_sync();
    Event e[128]; int n = drain(fd, e, 128), keys = 0, syncs = 0;
    for (int i = 0; i < n; i++) {
        if (e[i].type == EV_KEY) { assert(e[i].code == BTN_Y && e[i].value == !(keys & 1)); keys++; }
        else if (e[i].type == EV_SYN) syncs++;
    }
    assert(keys == 100 && syncs == 4 && e[n - 1].type == EV_SYN);
    close(fd);
}

static void read_sizes_and_flags(void) {
    int a = open_empty(), b = rd_pad_open(0);
    assert(fcntl(a, F_GETFL) & O_NONBLOCK);
    assert(!(fcntl(b, F_GETFL) & O_NONBLOCK));
    assert(fcntl(a, F_GETFD) & FD_CLOEXEC);
    assert(!(fcntl(b, F_GETFD) & FD_CLOEXEC));
    Event e[4];
    assert(rd_pad_read(a, e, 23) == -1 && errno == EINVAL);
    assert(rd_pad_read(a, e, 0) == 0);
    rd_pad_button(BTN_A, 1); rd_pad_sync();
    assert(rd_pad_read(a, e, 25) == 24 && e[0].type == EV_KEY);
    assert(rd_pad_read(a, e, 24) == 24 && e[0].type == EV_SYN);
    rd_pad_button(BTN_A, 0); rd_pad_sync(); close(a); close(b);
}

static void* blocking_reader(void* data) {
    Event e[2];
    int fd = *(int*)data;
    assert(rd_pad_read(fd, e, sizeof(e)) == sizeof(e));
    assert(e[0].type == EV_KEY && e[0].code == BTN_A && e[0].value == 1);
    return NULL;
}

static void blocking_reader_does_not_block_producer(void) {
    int fd = open_empty(); fcntl(fd, F_SETFL, 0);
    pthread_t thread; assert(!pthread_create(&thread, NULL, blocking_reader, &fd));
    struct timespec delay = { 0, 10000000 }; nanosleep(&delay, NULL);
    rd_pad_button(BTN_A, 1); rd_pad_sync();
    struct timespec deadline; clock_gettime(CLOCK_REALTIME, &deadline); deadline.tv_sec += 2;
    assert(!pthread_timedjoin_np(thread, NULL, &deadline));
    rd_pad_button(BTN_A, 0); rd_pad_sync(); close(fd);
}

int main(void) {
    current_held_buttons(); independent_opens_receive_both_edges();
    small_reads_preserve_remainder_and_poll(); world_load_stall_recovers_lost_release();
    reopen_has_current_state_not_old_taps(); duplicate_descriptor_shares_only_its_open();
    batches_do_not_lose_sync_or_edges(); read_sizes_and_flags();
    blocking_reader_does_not_block_producer();
    pthread_mutex_lock(&g_mx); collect_closed(); assert(!g_clients); pthread_mutex_unlock(&g_mx);
    puts("PASS: 9 native gamepad transport regressions");
    return 0;
}
