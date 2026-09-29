"""Real SDL2 discovery regression: three gamepads before isolation, one mapped pad after.

Host-only; requires Linux, libSDL2 2.24+, a C compiler. No hardware or elevated permissions.
"""
import ctypes.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[4]
library = ctypes.util.find_library("SDL2-2.0") or "libSDL2-2.0.so.0"
probe = r'''
import ctypes as C, sys
sdl = C.CDLL(sys.argv[1])
sdl.SDL_Init.argtypes = [C.c_uint32]
sdl.SDL_JoystickNameForIndex.argtypes = [C.c_int]
sdl.SDL_JoystickNameForIndex.restype = C.c_char_p
sdl.SDL_JoystickPathForIndex.argtypes = [C.c_int]
sdl.SDL_JoystickPathForIndex.restype = C.c_char_p
sdl.SDL_GetError.restype = C.c_char_p
assert sdl.SDL_Init(0x2000) == 0, sdl.SDL_GetError()
names = [sdl.SDL_JoystickNameForIndex(i).decode() for i in range(sdl.SDL_NumJoysticks())]
paths = [sdl.SDL_JoystickPathForIndex(i).decode() for i in range(len(names))]
print(list(zip(names, paths)))
# SDL may normalize product names using VID/PID. Assert the actual retained device paths.
expected = ['/dev/input/event-valdroid'] + ['/dev/input/event-vdphysical' + str(i + 1) for i in range(int(sys.argv[2]))]
assert paths == expected, (paths, expected)
sdl.SDL_Quit()
'''

with tempfile.TemporaryDirectory() as tmp:
    shim = Path(tmp) / "discovery.so"
    subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-shared", "-fPIC", "-pthread",
                    "app/src/test/native/sdl_device_filter_shim.c", "-ldl", "-o", str(shim)], cwd=ROOT, check=True)
    env = dict(os.environ, LD_PRELOAD=str(shim), SDL_VIDEODRIVER="dummy", SDL_JOYSTICK_HIDAPI="0",
               SDL_JOYSTICK_DEVICE="/dev/input/event-valdroid:/dev/input/event-vdphysical1:/dev/input/event-vdphysical2")
    for enabled, extra_count in [("0", "2"), ("1", "0")]:
        env["VALDROID_VIRTUAL_GAMEPAD_ONLY"] = enabled
        subprocess.run([sys.executable, "-c", probe, library, extra_count], env=env, check=True, timeout=15)
print("PASS: real SDL2 discovers three controllers without isolation and only the mapped pad with isolation")
