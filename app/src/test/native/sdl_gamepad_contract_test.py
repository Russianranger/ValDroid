"""Check real native evdev capabilities + launch mapping against SDL2 (2.0.14+).

Requires a host C compiler and libSDL2, no Android SDK, display, or physical controller.
SDL virtual joysticks model the raw slots that Linux assigns from EVIOCGBIT. This tests
SDL's controller interpretation; it does not claim to run Unity or Valheim.
"""
import ctypes as C
import ctypes.util
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
source = (ROOT / "app/src/main/java/com/valdroid/input/VirtualGamepad.java").read_text()
mapping_source = re.search(r"SDL_MAPPING\s*=\s*(.*?);", source, re.S).group(1)
mapping = "".join(re.findall(r'"([^"\n]*)"', mapping_source))
os.environ["SDL_VIDEODRIVER"] = "dummy"
os.environ["SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS"] = "1"
sdl = C.CDLL(ctypes.util.find_library("SDL2-2.0") or "libSDL2-2.0.so.0")

def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, args
    return fn

class Guid(C.Structure):
    _fields_ = [("data", C.c_ubyte * 16)]

class Event(C.Structure):
    _fields_ = [("sec", C.c_int64), ("usec", C.c_int64), ("type", C.c_uint16),
                ("code", C.c_uint16), ("value", C.c_int32)]

init = bind(sdl, "SDL_Init", C.c_int, C.c_uint32)
attach = bind(sdl, "SDL_JoystickAttachVirtual", C.c_int, C.c_int, C.c_int, C.c_int, C.c_int)
guid_for = bind(sdl, "SDL_JoystickGetDeviceGUID", Guid, C.c_int)
add_mapping = bind(sdl, "SDL_GameControllerAddMapping", C.c_int, C.c_char_p)
open_controller = bind(sdl, "SDL_GameControllerOpen", C.c_void_p, C.c_int)
joystick_for = bind(sdl, "SDL_GameControllerGetJoystick", C.c_void_p, C.c_void_p)
set_button = bind(sdl, "SDL_JoystickSetVirtualButton", C.c_int, C.c_void_p, C.c_int, C.c_ubyte)
set_axis = bind(sdl, "SDL_JoystickSetVirtualAxis", C.c_int, C.c_void_p, C.c_int, C.c_int16)
set_hat = bind(sdl, "SDL_JoystickSetVirtualHat", C.c_int, C.c_void_p, C.c_int, C.c_ubyte)
get_button = bind(sdl, "SDL_GameControllerGetButton", C.c_ubyte, C.c_void_p, C.c_int)
get_axis = bind(sdl, "SDL_GameControllerGetAxis", C.c_int16, C.c_void_p, C.c_int)
update = bind(sdl, "SDL_GameControllerUpdate", None)
delay = bind(sdl, "SDL_Delay", None, C.c_uint32)

with tempfile.TemporaryDirectory() as tmp:
    library = Path(tmp) / "pad.so"
    subprocess.run(["cc", "-std=c11", "-shared", "-fPIC", "-pthread", "-x", "c", "-", "-o", str(library)],
                   input='#define _GNU_SOURCE\n#define VD_PAD_TEST\n#include "app/src/main/cpp/valdroid_pad.c"\n',
                   text=True, cwd=ROOT, check=True)
    pad = C.CDLL(str(library), use_errno=True)
    pad_open = bind(pad, "rd_pad_open", C.c_int, C.c_int)
    ioctl = bind(pad, "rd_pad_ioctl", C.c_int, C.c_int, C.c_ulong, C.c_void_p)
    read = bind(pad, "rd_pad_read", C.c_ssize_t, C.c_int, C.c_void_p, C.c_size_t)
    button = bind(pad, "rd_pad_button", None, C.c_int, C.c_int)
    axis = bind(pad, "rd_pad_axis", None, C.c_int, C.c_int)
    sync = bind(pad, "rd_pad_sync", None)
    fd = pad_open(os.O_NONBLOCK)
    assert fd >= 0

    def query(nr, size):
        result = C.create_string_buffer(size)
        assert ioctl(fd, (2 << 30) | (size << 16) | (ord("E") << 8) | nr, result) >= 0
        return result.raw

    identity = query(2, 8)
    guid = b"".join(identity[i:i+2] + b"\0\0" for i in range(0, 8, 2)).hex()
    assert guid == mapping.split(",")[0], (guid, mapping)
    bits = query(0x21, 96)
    buttons = [i for i in range(0x120, 768) if bits[i // 8] & (1 << (i % 8))]
    assert buttons == [0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e]
    assert init(0x2000) == 0
    index = attach(1, 6, len(buttons), 1)
    assert index >= 0
    virtual_guid = bytes(guid_for(index).data).hex()
    assert add_mapping((virtual_guid + mapping[32:]).encode()) >= 0
    controller = open_controller(index)
    assert controller
    joystick = joystick_for(controller)
    events = (Event * 128)()
    hat = [0, 0]

    def pump():
        sync()
        while True:
            n = read(fd, events, C.sizeof(events))
            if n < 0:
                assert C.get_errno() == 11
                break
            for e in events[:n // C.sizeof(Event)]:
                if e.type == 1:
                    assert set_button(joystick, buttons.index(e.code), e.value) == 0
                elif e.type == 3 and e.code < 6:
                    value = e.value * 257 - 32768 if e.code in (2, 5) else e.value
                    assert set_axis(joystick, e.code, value) == 0
                elif e.type == 3 and e.code in (16, 17):
                    hat[e.code - 16] = e.value
                    value = (8 if hat[0] < 0 else 2 if hat[0] > 0 else 0) | (1 if hat[1] < 0 else 4 if hat[1] > 0 else 0)
                    assert set_hat(joystick, 0, value) == 0
        update()

    pump()
    expected_sdl = [0, 1, 2, 3, 9, 10, 4, 6, 5, 7, 8]
    for code, expected in zip(buttons, expected_sdl):
        button(code, 1); pump()
        assert [i for i in range(15) if get_button(controller, i)] == [expected], hex(code)
        button(code, 0); pump()
        if expected == 5: # SDL intentionally holds Guide for at least 250 ms.
            delay(260); update()
        assert not any(get_button(controller, i) for i in range(15)), hex(code)
    for code, value, expected in [(16, -1, 13), (16, 1, 14), (17, -1, 11), (17, 1, 12)]:
        axis(code, value); pump()
        assert [i for i in range(15) if get_button(controller, i)] == [expected]
        axis(code, 0); pump()
    for code, expected in [(0, 0), (1, 1), (3, 2), (4, 3), (2, 4), (5, 5)]:
        axis(code, 255 if code in (2, 5) else 32767); pump()
        assert get_axis(controller, expected) == 32767
        assert not any(get_button(controller, i) for i in range(15))
        axis(code, 0); pump()
        assert abs(get_axis(controller, expected)) <= 1
    os.close(fd)
    bind(sdl, "SDL_GameControllerClose", None, C.c_void_p)(controller)
    bind(sdl, "SDL_Quit", None)()
print("PASS: real SDL2 mapping of 11 buttons, 4 D-pad directions and 6 axes; native GUID/capabilities match")
