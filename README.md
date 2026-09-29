# ValDroid

**ValDroid** is an unofficial, community-developed launcher that runs the native Linux x86_64 build
of [Valheim](https://www.valheimgame.com) on ARM64 Android devices, with GPU rendering, touch
controls and gamepad support.

> [!NOTE]
> ValDroid does not include the game. It runs **your own copy** of Valheim's Linux build. You can
> download it inside the app with your Steam account\*, or add it as a `.zip` you made yourself.
>
> \* Downloading from Steam needs the **Steam mobile app with Steam Guard turned on**. You enter your
> Steam login in ValDroid, it goes straight to Steam's servers, and you **approve the sign-in in the
> Steam mobile app** — the authorisation itself happens there. ValDroid only receives Steam's
> permission to download the game and to use Steam Cloud; the permission is kept in memory for the
> session.

> [!TIP]
> ValDroid was tested with **Valheim 1.0.15**, and that is where it works best — especially on Mali
> GPUs. Older builds may show problems that are already fixed on the current version.

> [!WARNING]
> **ValDroid is an early alpha.** It launches and plays on the devices we've tested, but behaviour
> still varies from phone to phone. Back up your saves before you play.

## Features

- ✔️ Runs **Valheim 1.0** (the native Linux x86_64 build, Unity 6) on ARM64 phones and tablets
- ✔️ **Native ARM64 Mono**: the game's own code runs directly on your phone's CPU and only the Unity
  engine is emulated. That roughly doubles FPS wherever the CPU is the limit.
- ✔️ **Two renderers**: MobileGlues (OpenGL → OpenGL ES, the default) and Vulkan through Turnip
- ✔️ **On-screen controls with a full editor**: buttons (with styles and your own pictures), sticks,
  d-pad, touchpad, scroll bar and a radial menu. Two ready-made layouts — gamepad (the default) and
  keyboard with touchpad — and mouse look with the touchpad or a physical mouse. Physical gamepads and
  keyboards work too.
- ✔️ **Steam inside the app**\*: download the game, and move saves between your phone and PC through
  Steam Cloud
- ✔️ **Mods (experimental)**: BepInEx is built in; install mods from a `.zip` and switch them on and off
- ✔️ **Graphics presets** (Low and Very low), tuned for emulation rather than for a PC and written into
  the game before it starts
- ✔️ **Frame-rate modes** (Economy ~30, Balanced ~40, Smooth ~60, or no limit), plus an on-screen
  performance bar with FPS, CPU, RAM, power and temperature
- ✔️ **Texture compression** with a disk cache, so later launches load faster
- ✔️ **Save, settings and control-layout import & export**
- ✔️ Haptics, night mode, and Russian, Spanish and Portuguese translations

## Project status & what to expect

ValDroid is young: a few weeks old, built by **one person**, and in active development. It grew out
of [RimDroid](https://github.com/udarmolota/RimDroid) and [Zomdroid](https://github.com/udarmolota/zomdroid),
and shares their approach.

A few honest notes so expectations land right:

- **Single-player only for now.** Multiplayer, dedicated servers and crossplay are not supported yet.
- **Mods are experimental.** BepInEx is built in and many mods work, but not all of them: very old
  mods may load and do nothing.
- **Valheim is demanding.** Most of the engine runs under emulation, so the CPU is often the limit.
  What slows you down depends on where you are: open meadows are light, dense forest is heavy on the
  CPU, and a base full of fires and torches is heavy on the GPU.
- **Phones get hot.** With no frame limit a phone runs flat out and throttles after a while. The
  Balanced mode (~40 FPS) is much kinder to your hands and your battery.
- **The first launch is slow.** Textures and shaders are prepared once and cached, and every launch
  after that is noticeably faster.

## Device compatibility

- **Adreno (Snapdragon)** has the most mature path. Recent flagships play smoothly on the Low preset.
- **Mali:** support is in development.
- **Other GPUs** (for example Xclipse in recent Exynos chips) are untested.

## System requirements

- **Minimum:** Android 11+, ARM64, 8 GB RAM, about 10 GB of free storage (the game is ~4 GB, plus
  space to unpack it), and a copy of Valheim you own.
- **Recommended:** a recent flagship-class chip and 12 GB+ RAM. The closer to a current flagship,
  the better the experience.

## Getting the best performance

- **Turn on your phone's game booster at its highest-performance profile**: Samsung *Game Booster*,
  Xiaomi/POCO *Game Turbo*, Realme/OPPO/OnePlus *Game Space*, vivo/iQOO *Ultra Game Mode*.
  ValDroid registers itself as a game, so these tools should pick it up automatically.
- **Keep Native ARM64 Mono on** (Settings). Turning it off roughly halves your FPS.
- **Use the Very low preset** on weaker phones, and lower the resolution if the GPU struggles.
- **Pick a frame-rate mode** instead of no limit for long sessions. You'll get steadier FPS and less heat.
- **Close background apps** so the game gets the RAM and CPU to itself.

## How it works

Valheim officially ships for x86_64 only. ValDroid runs the **native Linux build** on ARM64:
[box64](https://github.com/ptitSeb/box64) emulates the x86_64 Unity engine in-process, the game's
C# code runs on a native ARM64 build of Unity's Mono, graphics go to your phone's real GPU, and
Android touch and gamepad input is injected straight into the game.

## Physical controller mapping (this fork)

Open **Settings → Gamepad mapping** before launching the game. Select **Gamepad** or
**Mouse / keyboard** as a starting layout, then edit individual outputs and save. Mappings apply
app-wide and are loaded when the game starts or resumes.

- Buttons, triggers, stick clicks and each D-pad direction can send a gamepad action, keyboard
  key, mouse click, wheel scroll, mouse movement, or no input.
- Each stick can drive either virtual gamepad stick, move the mouse cursor (mouse look when the
  game captures the pointer), use four custom direction bindings, or be disabled.
- Changing one binding replaces its previous action. There is no additional physical-controller
  passthrough behind a keyboard/mouse binding. Mixing gamepad and keyboard/mouse outputs is
  available by explicitly choosing both kinds in the same layout.
- Existing physical button calibration is retained. Use the calibration section only to correct
  swapped or unusual controller buttons; select actual game outputs in the rows above it.
- Touchscreen layouts are still edited separately. While a physical controller is connected it
  exclusively owns the virtual gamepad; touch-gamepad writes are blocked, including hidden or
  edited controls. Keyboard/mouse touch helpers remain available. Disconnecting the controller
  restores touch-gamepad input and clears held virtual buttons and axes.
- **Right stick axes** defaults to Automatic, which selects a complete centered Z/RZ or RX/RY
  pair. If a controller advertises both but only one works, choose its pair explicitly and save.
  Trigger aliases are read from one source per trigger; generic vendor axes are not guessed.

For an on-device check, map **A → E**, **B → Disabled**, **D-pad up → 1**, left stick to custom
**W/A/S/D**, and right stick to **Mouse cursor / look**. Save and relaunch. Check that A sends only E,
B does nothing, D-pad up sends only 1, and the sticks move and look as selected. Then test the
gamepad starting layout, trigger travel, and diagonal D-pad input. While holding an input,
background the app or disconnect the controller: its held output should release. If two buttons
share an output, releasing one should keep it held until the other is released.

The controller builds also route controller motion before Android's focused-view
handlers and suppresses stick jitter inside the device deadzone (at least 15%). For a double-input
report, launch the game, test the affected controls immediately, then export logs. `ValDroid/Input`
in `logcat.txt` records the saved profile, device ranges, selected stick/trigger axes, up to 512
button edges and 64 motion samples per resume, and output counts on pause. Motion samples are limited to one every
300 ms. These diagnostics distinguish controller mappings from a separate mouse/keyboard stream;
ordinary physical mouse/keyboard support remains enabled.

`0.1.3-button-test` fixes the native virtual-gamepad transport used after Java mapping. Each
guest open gets its own event queue; a short read cannot discard other button edges; held-button
queries return the actual state; and an overflowing queue resynchronizes to current buttons and
axes after a loading stall. Reopening the device starts from current state rather than old taps.
The `pad:` lines in `rimdroid.log` identify reader opens, delivered button edges, state queries and
overflow recovery. Stick mappings are unchanged in this build.

For the in-world button check, use the Gamepad layout and press/release each button separately
after the character finishes spawning. Then hold/release each shoulder button and trigger and
repeat the face-button checks. Test Start and Select, pause/resume, and another world load. Export
logs if a held modifier remains active or an unrelated action occurs. Use Automatic right-stick
axes for controllers that report Z/RZ; forcing RX/RY on such a device disables its right stick.

`0.1.4-input-trace` investigates wrong in-world actions that remain after the transport fixes.
The latest device trace shows correct evdev presses/releases, one active reader and no queue
overflow; it does not prove that Unity or Valheim interprets each button correctly. The launcher
now supplies the exact SDL mapping for our USB Xbox GUID, including all 11 buttons, the hat and
six axes. It retains the compact USB layout; adding unused Bluetooth button slots would shift it.

Debug APKs with native Mono also observe Unity's existing `NativeInputSystem` callbacks. Bounded
`[VD-INPUT]` lines in `box64.log` record gamepad descriptors, event times, and Unity `GPAD` button
states. Other formats are labeled raw, not guessed. The observer does not rewrite events, install
game callbacks, inject inputs, or change Valheim bindings. Keyboard/text events are excluded.
It logs up to 512 button/trigger changes per device; stick-only motion does not spend that budget.
The instance environment override `VALDROID_INPUT_TRACE=0` disables this diagnostic.

For this test, keep the same controller profile. After the character finishes spawning, tap
**A, B, X, Y, LB, RB, Start, Select** individually, leaving a second between buttons, then
export logs. Include one expected/actual action example if possible. This is a diagnostic build,
not confirmation that the remaining in-world fault is fixed.

Host checks: `app/src/test/native/valdroid_pad_test.c` covers evdev transport,
`unity_input_trace_test.c` covers Unity buffer parsing and read-only behavior, and
`python3 app/src/test/native/sdl_gamepad_contract_test.py` checks the advertised native capabilities
and launcher mapping against real SDL2 virtual joysticks (requires a C compiler and libSDL2 2.0.14+).
These checks do not substitute for testing Valheim on the device.

`0.1.5-single-controller` addresses the duplicate devices demonstrated by the 06:54 device log.
Unity registered the mapped ValDroid pad as device 3 and two physical Xbox interfaces as devices
4 and 5. Device 4 duplicated button presses with a different layout: X also reported Y, Y reported
LB, LB reported Select, RB reported Start, and LT reported RT. The virtual pad reported the intended
buttons. `SDL_JOYSTICK_DEVICE` adds a device; it does not stop SDL from discovering others.

The guest ioctl boundary now rejects direct physical evdev/legacy-joystick controller probes by
capabilities while preserving the virtual pad. This also covers controllers opened with openat or
through a symlink, and both libc and direct ioctl syscalls. Keyboard, mouse and touch capabilities
are not filtered. The launcher disables SDL's alternative HIDAPI controller backend. Android still
reads physical controls and applies the saved profile; the game sees only the resulting virtual pad.
The advanced environment override `VALDROID_VIRTUAL_GAMEPAD_ONLY=0` disables the evdev/js guard for
comparison. `pad: blocked direct guest controller` lines identify filtered devices.

The host regression `python3 app/src/test/native/sdl_device_filter_test.py` uses actual SDL2
discovery with a virtual pad and two simulated physical interfaces, all sharing a VID/PID. It
reproduces three visible gamepads without the fix and exactly the virtual path with it. This test
and the SDL mapping test now run in CI alongside native isolation and transport checks.

Device verification: keep the saved Gamepad profile, enter a world, test X/Y and LB/RB/Start/Select,
then check a custom keyboard binding and a Disabled button. Export logs to confirm Unity reports
only the mapped pad and that no direct-controller input escapes a remap or Disabled binding.

## Build

- Android Studio (its bundled JBR), Android SDK and NDK
- `box64/` is our own fork of box64, built into the app from this repository
- `libmobileglues.so` ships unmodified from [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues)
  in the bundled libraries

## Supporting development

This is an independent project. To help keep it going, contributions are welcome via
[Ko-Fi](https://ko-fi.com/udarmolota).

## Feedback

Please report issues or request features via
[GitHub Issues](https://github.com/udarmolota/ValDroid/issues), or join us on
[r/ValDr0id](https://www.reddit.com/r/ValDr0id/). The quickest way to report a problem is
**Report a bug** in the in-app menu: pick your email app, add a short description of what happened,
and send. The logs are attached for you.

## Credits & Third-Party Sources

- [box64](https://github.com/ptitSeb/box64): x86_64 → ARM64 emulation
- [Mono (Unity fork)](https://github.com/Unity-Technologies/mono): the native ARM64 runtime for the
  game's code
- [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) by [MobileGL-Dev](https://github.com/MobileGL-Dev):
  OpenGL → OpenGL ES renderer
- [Mesa / Zink / Turnip](https://gitlab.freedesktop.org/mesa/mesa): Vulkan rendering and the Adreno
  Vulkan driver
- [gbe_fork](https://github.com/Detanup01/gbe_fork) (LGPL-3.0): offline Steam API for the emulated
  runtime
- [JavaSteam](https://github.com/Longi94/JavaSteam) and [Bouncy Castle](https://www.bouncycastle.org/):
  Steam sign-in, downloads and Steam Cloud
- [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass): Android linker namespace access
- [RimDroid](https://github.com/udarmolota/RimDroid) and [Zomdroid](https://github.com/udarmolota/zomdroid):
  the launchers ValDroid grew out of

Valheim is a trademark of Iron Gate AB. ValDroid is not affiliated with or endorsed by Iron Gate or
Coffee Stain.
