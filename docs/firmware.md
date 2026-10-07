# Programming the ESP32 on the 2WD chassis

How to put the Jiadroid protocol on the ESP32 that drives the first prototype.

The phone is still the brain. The ESP32 only moves the chassis, counts the wheels, and stops when asked. It speaks [protocol 0.2](protocol.md) over Wi-Fi, on TCP port 8765, the same port as the laptop simulator. The phone app connects to it the same way it connects to `python -m jiadroid.sim --robot rover`.

The project lives in `firmware/`. It is a [PlatformIO](https://platformio.org/) project for the [ESP32-S3-DevKitC-1](https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/hw-reference/esp32s3/user-guide-devkitc-1.html). Flash and serial use that board's own **USB** connector (native USB CDC / USB Serial-JTAG), not the **UART** connector. On macOS it shows up as an Espressif device, typically USB ID `303A:1001`, on a path like `/dev/cu.usbmodem1101`. A classic ESP32-WROOM-32 DevKit cannot appear that way: it has no USB of its own.

## What you need

- The ESP32-S3-DevKitC-1. Use the connector labeled USB, the one wired to the chip, not the one labeled UART.
- A data USB cable. A charge-only cable powers the board and still fails to flash it.
- The [DIYables 2WD chassis](https://www.amazon.com/dp/B0H4V8TR38): two encoder motors, an L9110S driver, a caster, and the top plate.
- Four AA cells for the motors. The ESP32 itself is powered from USB while you program it.

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

## Talk to it

The laptop client works before the phone app does. Use the address from the monitor:

```bash
python -c "from jiadroid import connect; robot = connect('tcp://192.168.1.50:8765'); print(robot.kind, robot.name); robot.move(forward=0.1); robot.stop(); robot.close()"
```

The wheels should turn, then stop. `robot.kind` is `wheeled` and the name is `2WD Chassis`.

In the Android or iOS app, enter that same address and tap Connect, the same as for the laptop simulator. The app shows which body answered and sends Follow Me as `motion.velocity`.

One phone at a time. A second connection is refused while the first is open.

## What the chip does

On connect it sends `session.hello`: kind `wheeled`, a `motion.velocity` limit of 0.40 m/s forward and 1.5 rad/s of yaw, motors and encoders named `left_wheel` and `right_wheel`, sensors `bump` and `range_front`, and one mount, `top`.

`motion.velocity` becomes two wheel speeds and PWM on the L9110S. `motor.velocity` drives one wheel in rad/s. `encoder.read` returns ticks, 960 per wheel turn until you count the slots on the real motor. `telemetry.subscribe` streams the same sample the simulator does, including a pose estimated from the encoders.

`robot.stop` coasts the motors. `safety.estop` coasts them and rejects motion until `safety.clear`. `robot.reset` zeros the pose estimate. It does not move the chassis back to a mark on the floor.

A dropped connection, or a lost Wi-Fi link, coasts the motors. An emergency stop stays latched across that drop.

The bump switch cuts the part of the command that would drive into it. The phone can still turn and back up. The reported command stays what the phone asked for.

`payload.set` is remembered and reported. The chip does not change how it drives because of the phone's weight.

## What it does not do

- Bluetooth and USB. Those are other ways to carry the same messages. This firmware is Wi-Fi only.
- The Open Duck Mini. The duck's ESP32 link, the one that would write `motion.velocity` into the walk policy, is still unwritten.
- A proven bring-up on the real plate. The project compiles here. Wheel direction, encoder direction, and the measured size of the plate still have to be checked on the hardware, then set in `config.h`.
