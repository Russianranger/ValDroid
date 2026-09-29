/* Linux-host fixture for SDL discovery. Model two physical evdev interfaces plus the real
 * ValDroid virtual pad. Only the fixture's host ioctls are synthetic; the production native
 * filter and transport run unchanged. Loaded only by sdl_device_filter_test.py via LD_PRELOAD.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdarg.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>

static unsigned char physical[4096];
static int host_ioctl(int fd, unsigned long req, void* arg);
#define VD_PAD_TEST
#define ioctl host_ioctl
#include "../../main/cpp/valdroid_pad.c"
#undef ioctl

static int is_physical(int fd) {
    return fd >= 0 && fd < (int)sizeof(physical) && physical[fd];
}
static int path_kind(const char* path) {
    if (!strcmp(path, VD_PAD_PATH)) return 1;
    if (!strcmp(path, "/dev/input/event-vdphysical1")) return 2;
    if (!strcmp(path, "/dev/input/event-vdphysical2")) return 3;
    return 0;
}
static int host_ioctl(int fd, unsigned long req, void* arg) {
    if (is_physical(fd)) {
        if (((req >> 8) & 0xff) == 'E' && (req & 0xff) == 6) {
            size_t size = (req >> 16) & 0x3fff;
            snprintf(arg, size, "Xbox Wireless Controller");
            return (int)strlen(arg) + 1;
        }
        // Same VID/PID as the virtual pad: a vendor allowlist would fail to isolate it.
        return rd_pad_ioctl(-1, req, arg);
    }
    int (*real_ioctl)(int, unsigned long, ...) = dlsym(RTLD_NEXT, "ioctl");
    return real_ioctl(fd, req, arg);
}

int ioctl(int fd, unsigned long req, ...) {
    va_list ap; va_start(ap, req); void* arg = va_arg(ap, void*); va_end(ap);
    // Same order as box64's my_ioctl (also used for direct ioctl syscalls).
    if (rd_pad_is_fd(fd)) return rd_pad_ioctl(fd, req, arg);
    if (rd_pad_block_guest_controller_ioctl(fd, req)) return -1;
    return host_ioctl(fd, req, arg);
}

int open(const char* path, int flags, ...) {
    int mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    int (*real_open)(const char*, int, ...) = dlsym(RTLD_NEXT, "open");
    int kind = path_kind(path);
    if (kind == 1) return rd_pad_open(flags);
    if (kind > 1) {
        int fd = real_open("/dev/null", flags, mode); // actual character fd, as checked by the filter
        if (fd >= 0 && fd < (int)sizeof(physical)) physical[fd] = 1;
        return fd;
    }
    return real_open(path, flags, mode);
}
int open64(const char* path, int flags, ...) {
    int mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return open(path, flags, mode);
}
int close(int fd) {
    int (*real_close)(int) = dlsym(RTLD_NEXT, "close");
    if (fd >= 0 && fd < (int)sizeof(physical)) physical[fd] = 0;
    return real_close(fd);
}
int stat(const char* path, struct stat* st) {
    int kind = path_kind(path);
    if (kind) {
        memset(st, 0, sizeof(*st)); st->st_mode = S_IFCHR | 0660; st->st_nlink = 1;
        st->st_rdev = makedev(13, 98 + kind); st->st_ino = 98 + kind;
        return 0;
    }
    int (*real_stat)(const char*, struct stat*) = dlsym(RTLD_NEXT, "stat");
    return real_stat(path, st);
}
ssize_t read(int fd, void* buf, size_t size) {
    if (rd_pad_is_fd(fd)) return rd_pad_read(fd, buf, size);
    ssize_t (*real_read)(int, void*, size_t) = dlsym(RTLD_NEXT, "read");
    return real_read(fd, buf, size);
}
