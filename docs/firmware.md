# Programming the ESP32 on the 2WD chassis

How to put the Jiadroid protocol on the ESP32 that drives the first prototype.

The phone is still the brain. The ESP32 only moves the chassis, counts the wheels, and stops when asked. On this branch it speaks [protocol 0.2](protocol.md) primarily over **USB‑C serial** (phone OTG powers the board and carries the same newline‑JSON messages). Optional Wi‑Fi TCP on port 8765 remains as a fallback when `WIFI_SSID` is set.

The project lives in `firmware/`. It is a [PlatformIO](https://platformio.org/) project for the [ESP32-S3-DevKitC-1](https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/hw-reference/esp32s3/user-guide-devkitc-1.html). The connector labeled **USB** is native USB CDC (protocol + phone power). The connector labeled **UART/COM** is for laptop debug logs only. On macOS the USB port shows up as Espressif `303A:1001`, path like `/dev/cu.usbmodem1101`.

## What you need

- The ESP32-S3-DevKitC-1. Use the connector labeled USB, the one wired to the chip, not the one labeled UART.
- A data USB cable. A charge-only cable powers the board and still fails to flash it.
- The [DIYables 2WD chassis](https://www.amazon.com/dp/B0H4V8TR38): two encoder motors, an L9110S driver, a caster, and the top plate.
- Four AA cells for the motors (L9110S). They are separate from the ESP32 supply.
- For the USB MVP: a USB‑C OTG cable from an **Android** phone into the board **USB** port (phone powers the ESP32 and talks to it). A laptop USB cable is only for flashing.
- Optional: a 5 V powerbank on USB/COM if you are not using the phone for power.

PlatformIO compiles the firmware and flashes it. Install the PlatformIO IDE extension in Cursor, or install the command-line tool:

```bash
python3.11 -m pip install --user platformio
export PATH="$HOME/Library/Python/3.11/bin:$PATH"
```

That install puts `pio` in `~/Library/Python/3.11/bin`. Add the `export` line to your shell profile if a new terminal does not find `pio`.

## Wi-Fi and pins

Edit `firmware/include/config.h` before the first flash.

| Setting | What to put there |
| --- | --- |
| `WIFI_SSID`, `WIFI_PASSWORD` | The network the phone will use. The chip joins it and prints its address on the serial port. |
| Motor pins | The four L9110S inputs. Defaults are GPIO 4, 5, 6, and 7. |
| `MOTOR_INVERT_LEFT`, `MOTOR_INVERT_RIGHT` | Set a side to `-1` if that wheel turns the wrong way. |
| Encoder pins | Channel A is the counted edge, channel B sets the direction. Defaults are GPIO 15, 16, 17, and 18. |
| `ENC_SIGN_LEFT`, `ENC_SIGN_RIGHT` | Set a side to `-1` if that encoder counts backward. |
| `PIN_BUMP` | GPIO 21, a switch to ground. An open pin reads as not pressed. |
| `PIN_RANGE_TRIG`, `PIN_RANGE_ECHO` | An HC-SR04, if you add one. Both are `-1` until then, and `range_front` stays at 2 m. |

Wheel radius, track, and encoder slots are the same numbers as `jiadroid.sim.rover`. The kit listing has no drawing, so measure them on the real plate and change both places together.

These GPIO numbers are pins that are actually on the DevKitC-1 headers. GPIO 19 and 20 are that USB connector, and GPIO 26–32 are wired to the module's flash, so the firmware does not use them.

## Wire it

Share one ground between the ESP32, the L9110S, and the encoder plugs. Power the motors from the AA pack through the L9110S. Do not power the motors from the ESP32's 3.3 V pin.

The L9110S inputs take a 3.3 V GPIO. Power the encoder boards from the ESP32's 3.3 V pin. A 5 V encoder output into a GPIO can damage the chip.

| ESP32-S3 | Connects to |
| --- | --- |
| GPIO 4 | L9110S A-1A, left motor |
| GPIO 5 | L9110S A-1B, left motor |
| GPIO 6 | L9110S B-1A, right motor |
| GPIO 7 | L9110S B-1B, right motor |
| GPIO 15 | Left encoder A |
| GPIO 16 | Left encoder B |
| GPIO 17 | Right encoder A |
| GPIO 18 | Right encoder B |
| GPIO 21 | Bump switch, the other side of the switch to GND |
| GND | L9110S GND and both encoder GNDs |

Left and right follow the body frame in the protocol: x forward, y left, origin at the middle of the axle. Positive yaw turns left.

## Flash it

Use the connector labeled **USB**, not **UART** (sometimes labeled COM). Stay on USB for both flashing and the serial log. UART is a different chip and a different `/dev/cu.*` path; this project is not set up for it.

Plug the board in with a data cable. From the repository root:

```bash
cd firmware
pio device list
```

Pick the Espressif entry. It usually looks like:

```text
/dev/cu.usbmodem1101
--------------------
Hardware ID: USB VID:PID=303A:1001 ...
Description: USB JTAG/serial debug unit
```

The exact `usbmodemNNNN` number can change when you unplug, change hubs, or hold BOOT and tap RESET. Always take the path from `pio device list` for this session. Do not reuse an old path such as `/dev/cu.usbmodem1234561` — that fails with `Could not open …, the port doesn't exist`.

Flash, then open the log (two separate commands; the monitor is not the uploader):

```bash
pio run -t upload --upload-port /dev/cu.usbmodem1101
pio device monitor --port /dev/cu.usbmodem1101
```

Replace the port with whatever `pio device list` showed.

The monitor uses 115200 baud. `platformio.ini` keeps DTR and RTS inactive so opening the monitor does not shove the chip into the bootloader. When the chip joins Wi-Fi it prints a line like `Listening on 192.168.1.50:8765`. That address is what the phone connects to.

### If the log says `waiting for download`

That means the ROM bootloader is sitting in download mode. You are watching the bootloader, not a hung upload. Quit the monitor (`Ctrl+C`), then either:

- run `pio run -t upload --upload-port …` on the current port, or
- press **RESET** (not BOOT) so the firmware runs again, then reopen the monitor.

If upload cannot sync on a blank or stubborn board, hold **BOOT**, tap **RESET**, release **BOOT**, run `pio device list` again, and upload to the port that still says `USB JTAG/serial debug unit`. After this firmware is on the chip, later uploads can reset over the same USB port without holding BOOT.

From Cursor with the PlatformIO extension, open `firmware/`, then use Upload and Monitor on the `esp32-s3-devkitc-1` environment. That runs the same two commands; still point them at the port from `pio device list`.

## Status RGB LED

The DevKitC‑1 onboard WS2812 (GPIO **38** on v1.1, set `PIN_STATUS_RGB` in `config.h`; use **48** on v1.0) shows link and command state:

| LED | Meaning |
| --- | --- |
| Slow blue blink | Joining Wi‑Fi |
| Dim blue | Wi‑Fi up, waiting for a phone |
| Dim green | Phone linked, idle |
| Green / cyan / magenta pulse | `motion.velocity` (forward / turn left / mix) |
| Reddish pulse | Reverse motion |
| White flash | `robot.stop` / reset |
| Fast red blink | `safety.estop` |
| Green flash | `safety.clear` |
| Purple flash | `session.bye` |
| Slow red blink | Wi‑Fi failed / skipped |

## Talk to it (USB‑C MVP)

1. Flash from a laptop on the **USB** port, then unplug the laptop.
2. Plug that same **USB** port into an Android phone with a USB‑C OTG cable (phone powers the ESP32).
3. Put AA cells in for the motors; share GND between ESP32 and L9110S; wire GPIO 4–7 as above.
4. Build/install the Android app from this branch. In Debug, tap **USB**. Allow USB permission. You should see `2WD Chassis` over USB‑C.

Debug logs (including `cmd motion.velocity …`) print on the **UART/COM** port at 115200, not on the USB protocol link. Leave COM unplugged during a phone demo unless you want a laptop log.

### Optional Wi‑Fi fallback

Set `WIFI_SSID` / `WIFI_PASSWORD` in `config.h`, flash, and watch COM for `Listening on …:8765`. In the app, enter that address and tap **Connect** (not USB). iPhone can use Wi‑Fi only; USB serial is Android on this branch.

```bash
python -c "from jiadroid import connect; robot = connect('tcp://192.168.1.50:8765'); print(robot.kind, robot.name); robot.move(forward=0.1); robot.stop(); robot.close()"
```

One phone at a time. A second connection is refused while the first is open.

## What the chip does

On connect it sends `session.hello`: kind `wheeled`, a `motion.velocity` limit of 0.40 m/s forward and 1.5 rad/s of yaw, motors and encoders named `left_wheel` and `right_wheel`, sensors `bump` and `range_front`, and one mount, `top`.

`motion.velocity` becomes two wheel speeds and PWM on the L9110S. `motor.velocity` drives one wheel in rad/s. `encoder.read` returns ticks, 960 per wheel turn until you count the slots on the real motor. `telemetry.subscribe` streams the same sample the simulator does, including a pose estimated from the encoders.

`robot.stop` coasts the motors. `safety.estop` coasts them and rejects motion until `safety.clear`. `robot.reset` zeros the pose estimate. It does not move the chassis back to a mark on the floor.

A dropped connection, or a lost Wi-Fi link, coasts the motors. An emergency stop stays latched across that drop.

The bump switch cuts the part of the command that would drive into it. The phone can still turn and back up. The reported command stays what the phone asked for.

`payload.set` is remembered and reported. The chip does not change how it drives because of the phone's weight.

## What it does not do

- iPhone over USB. Apple does not allow casual USB‑serial to this DevKit; use Wi‑Fi for iPhone.
- Bluetooth. Not implemented on this branch.
- The Open Duck Mini. The duck's ESP32 link, the one that would write `motion.velocity` into the walk policy, is still unwritten.
- A proven bring-up on the real plate. The project compiles here. Wheel direction, encoder direction, and the measured size of the plate still have to be checked on the hardware, then set in `config.h`.
