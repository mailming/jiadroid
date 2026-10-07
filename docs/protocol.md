# Jiadroid protocol 0.2

Technical contract for the phone-to-robot link. The idea and the laptop demo are in the [README](../README.md).

Version 0.2 makes the link general. A robot introduces itself with a `kind` and a list of `controls`: the robot-level commands it accepts, each with limits. The phone reads that list and drives the body with `motion.velocity` and, if there is one, `head.pose`, scaling its decisions to the announced limits. It does not need to know whether it is talking to legs or wheels. Devices (servos, motors, encoders, sensors) are still listed individually for finer control.

The Open Duck Mini's 0.1 command, `walk.velocity`, remains as an alias so older phone builds and the duck's runtime keep working. 0.2 clients accept 0.1 robots and assume the duck's controls for them.

## Transport

The protocol is a stream of messages and does not depend on how it is carried. Intended links are Wi-Fi (TCP), Bluetooth (a serial-style stream such as Bluetooth Classic SPP or a BLE UART service), and USB (USB serial). Each carries the same framed messages below.

Version 0.2 implements TCP only. The reference simulator listens on `127.0.0.1:8765`. Use `--host 0.0.0.0` when a phone on the same network should connect.

## Framing

Each message is one UTF-8 JSON object followed by a single `\n`. Carriage returns before the newline are ignored. A message, including the newline, is at most 8192 bytes. Encoders emit compact JSON. Parsers accept normal JSON whitespace inside the line.

## Envelope

```json
{"v":1,"id":"1","kind":"req","op":"motion.velocity","body":{"forward":0.1,"yaw":0.4}}
```

| Field | Meaning |
| --- | --- |
| `v` | Envelope version. Always `1`. |
| `kind` | `req`, `res`, or `evt`. |
| `op` | Operation name. |
| `id` | Correlation id. Required on `req` and `res`. Omitted on `evt`. |
| `body` | Parameters or result. Required on `req`, `evt`, and a successful `res`. |
| `error` | `{code, message}` on a failed `res`. A failed response has no `body`. |

Unknown envelope fields are rejected. Unknown fields inside `body` are ignored.

The response copies `id` and `op` from the request.

## Robot

`session.hello` describes the robot:

```json
{
  "protocol": "jiadroid",
  "version": "0.2",
  "robot": {"id": "open-duck-mini", "name": "Open Duck Mini", "kind": "biped"},
  "safety": {"state": "ready"},
  "controls": {
    "motion.velocity": {"forward": [-0.15, 0.15], "lateral": [-0.2, 0.2], "yaw": [-1, 1]},
    "head.pose": {"neck_pitch": [-0.34, 1.1], "pitch": [-0.78, 0.3], "yaw": [-0.5, 0.5], "roll": [-0.5, 0.5]},
    "walk.velocity": {}
  },
  "mounts": [
    {"id": "head", "position": [0.02, 0, 0.22], "max_mass": 0.3},
    {"id": "back", "position": [-0.055, 0, 0.09], "max_mass": 0.3}
  ],
  "devices": [{"type": "servo", "id": "left_knee", "capabilities": ["position"]}]
}
```

`kind` is one of `biped`, `quadruped`, `wheeled`, `arm`, `other`. It is descriptive; the phone acts on `controls`.

### Controls

`controls` maps an operation name to the fields it accepts and their inclusive limits, `{field: [low, high]}`. This is the robot's plug description, in the way a USB device describes itself. Rules:

- A field that is listed may be sent within its limits.
- A field that is omitted from a request is `0`.
- A field the robot did not list is refused with `out_of_range` unless it is `0`. A rover has no `lateral`; sending `lateral: 0` is fine, `lateral: 0.1` is not.
- An operation the robot did not list fails with `unsupported`.

Robot-level operations that can appear in `controls`:

| Operation | Fields | Meaning |
| --- | --- | --- |
| `motion.velocity` | `forward`, `lateral` (m/s), `yaw` (rad/s) | Body velocity. Positive `yaw` turns left (counterclockwise). |
| `head.pose` | `pitch`, `yaw`, `roll`, `neck_pitch` (radians) | Where the head points. Bodies without a head do not list it. |
| `walk.velocity` | none listed | 0.1 alias for the Open Duck Mini, see below. |

The phone's Follow Me decision is made in the duck's units and scaled to the connected body's limits: "walk forward" is 0.12 m/s on a duck that can do 0.15 and 0.4 m/s on a rover that can do 0.5. The client libraries do this (`fit_to_robot` in Python, `fit` in Kotlin and Swift).

### Devices

A device is `{type, id, capabilities}`. `type` and `id` together identify it. Types in this version:

| Type | Capabilities | Operations |
| --- | --- | --- |
| `servo` | `position` | `servo.position`, `servo.read` |
| `motor` | `velocity` | `motor.velocity` |
| `encoder` | `ticks` | `encoder.read` |
| `sensor` | its unit: `boolean`, `meters`, ... | `sensor.read` |

The Open Duck Mini announces 14 `servo` devices. Names and standing pose match `HWI` in [Open Duck Mini Runtime](https://github.com/apirrone/Open_Duck_Mini_Runtime): `left_hip_yaw`, `left_hip_roll`, `left_hip_pitch`, `left_knee`, `left_ankle`, `neck_pitch`, `head_pitch`, `head_yaw`, `head_roll`, `right_hip_yaw`, `right_hip_roll`, `right_hip_pitch`, `right_knee`, `right_ankle`. The physics duck adds sensors `left_foot`, `right_foot`, `height`, `upright`.

The rover announces motors `left_wheel`, `right_wheel`, encoders with the same names, and sensors `bump` and `range_front`.

### Phone mounts

A phone riding on the robot is a payload. Its weight and where it sits change the center of mass, so the robot announces the docks it has and the phone answers with what it weighs and how big it is.

`mounts` in `session.hello` lists the docks. Each is `{id, position, max_mass}`:

- `position` is `[x, y, z]` in meters in the body frame: x forward, y left, z up. It is where the center of a centered phone sits. On the duck the origin is the trunk, at the hip, and the position is the one at the standing pose. On the rover the origin is the center of the axle.
- `max_mass` is kilograms. A heavier phone is refused.

The Open Duck Mini announces two mounts:

| id | position (m) | max mass | Where |
| --- | --- | --- | --- |
| `head` | `[0.02, 0, 0.22]` | 0.30 kg | The face. In the physics simulator this mass is fixed to the head, so it pitches with the neck. |
| `back` | `[-0.055, 0, 0.09]` | 0.30 kg | The rear shell, fixed to the trunk. |

The first prototype announces one mount, `top`. The base is a DIYables 2WD chassis ([ASIN B0H4V8TR38](https://www.amazon.com/dp/B0H4V8TR38)): two encoder motors, an L9110S driver, a caster, listed at 0.32 kg. The phone stands on the top plate, screen facing forward, so the front camera looks along +x. The mount point is `[0.06, 0, 0.0975]`, the center of a 155 mm phone, and it holds up to 0.30 kg. A phone lying flat would point that camera at the ceiling, so this mount is vertical: `size` is `[thickness, width, height]`. The listing has no mechanical drawing; the track, plate height, and encoder slot count in `jiadroid.sim.rover` are the usual ones for this chassis and should be measured on the real plate.

The phone then sends `payload.set`:

```json
{"mount": "head", "mass": 0.170, "size": [0.0078, 0.0716, 0.1476], "offset": [0, 0, 0], "name": "iPhone 16"}
```

| Field | Meaning |
| --- | --- |
| `mount` | Which dock. An unknown id is `unknown_device`. |
| `mass` | Kilograms, above 0 and at most the mount's `max_mass`. This is the phone plus its case. |
| `size` | Full extents `[x, y, z]` in meters, each above 0 and at most 0.25. On a vertical dock the phone stands: thickness, width, height. On a flat dock it lies down: length, width, thickness. |
| `offset` | Optional `[x, y, z]` in meters, at most 0.05 from the mount point. A dock that clamps the bottom of the phone, or a thick case, shifts the center of mass. Omitted means `[0, 0, 0]`. |
| `name` | Optional model name, up to 64 characters. |

The response echoes those fields and adds `position`, the phone's center in the body frame (`mount.position` plus `offset`). `payload.clear` removes it. Neither changes the safety state, and both are allowed during an emergency stop: the phone is still sitting there. While a phone is mounted, `telemetry.sample` includes the same `payload` object.

A model the app does not recognise is reported as a generic phone: 0.190 kg, 155 × 73 × 8 mm. These are bare phones, with no case, from the maker's spec sheet. The same numbers live in `jiadroid.protocol.phones`.

| Phone | Mass | Width | Height | Thickness |
| --- | --- | --- | --- | --- |
| iPhone 16 | 170 g | 71.6 mm | 147.6 mm | 7.8 mm |
| iPhone 16 Plus | 199 g | 77.8 mm | 160.9 mm | 7.8 mm |
| iPhone 16 Pro | 199 g | 71.5 mm | 149.6 mm | 8.3 mm |
| iPhone 16 Pro Max | 227 g | 77.6 mm | 163.0 mm | 8.3 mm |
| iPhone 15 | 171 g | 71.6 mm | 147.6 mm | 7.8 mm |
| iPhone 15 Plus | 201 g | 77.8 mm | 160.9 mm | 7.8 mm |
| iPhone 15 Pro | 187 g | 70.6 mm | 146.6 mm | 8.3 mm |
| iPhone 15 Pro Max | 221 g | 76.7 mm | 159.9 mm | 8.3 mm |
| Pixel 9 | 198 g | 72.0 mm | 152.8 mm | 8.5 mm |
| Pixel 9 Pro | 199 g | 72.0 mm | 152.8 mm | 8.5 mm |
| Pixel 9 Pro XL | 221 g | 76.5 mm | 162.8 mm | 8.5 mm |
| Pixel 8 | 187 g | 70.8 mm | 150.5 mm | 8.9 mm |
| Pixel 8 Pro | 213 g | 76.5 mm | 162.6 mm | 8.8 mm |

## Safety

States: `ready`, `running`, `estop`.

- A non-zero `motion.velocity` or `motor.velocity` moves `ready` to `running`.
- `head.pose` and `servo.position` do not change the state.
- `robot.stop` zeros motion and head and returns servos to the neutral pose. It does not clear an emergency stop.
- `robot.reset` does the same and, in a simulator, puts the robot back on its feet at the origin.
- `safety.estop` stops and latches `estop`.
- While latched, `motion.velocity`, `head.pose`, `motor.velocity`, `servo.position`, and `walk.velocity` fail with `safety_blocked`.
- `safety.clear` moves `estop` to `ready`.

## Operations

### `session.hello`

Event sent by the robot immediately after connect. Body above.

### `session.ping`

Request body `{}`. Response body `{"ok": true}`.

### `session.bye`

Request body `{}`. Response body `{"ok": true}`. The robot then closes the connection.

### `devices.list`

Request body `{}`. Response body `{"devices": [ ... ]}`.

### `motion.velocity`

Request body: the announced fields, e.g. `{"forward": 0.1, "yaw": 0.4}`. Response echoes every announced field and adds `safety`:

```json
{"forward": 0.1, "lateral": 0, "yaw": 0.4, "safety": {"state": "running"}}
```

On the Open Duck Mini these are `lin_vel_x`, `lin_vel_y`, and the angular velocity that the walk policy reads. On the rover they become two wheel speeds.

### `head.pose`

Request body: the announced fields in radians, e.g. `{"yaw": 0.2}`. Response echoes them and adds `safety`. On the duck, `pitch`, `yaw`, `roll` are the `head_*` servos and `neck_pitch` is its own servo.

### `walk.velocity`

The Open Duck Mini's 0.1 command, one message for motion and head, with the runtime's field names (`xbox_controller.py`):

| Field | Limit |
| --- | --- |
| `forward` | -0.15 to 0.15 m/s |
| `lateral` | -0.2 to 0.2 m/s |
| `yaw` | -1 to 1 rad/s |
| `neck_pitch` | -0.34 to 1.1 rad |
| `head_pitch` | -0.78 to 0.3 rad |
| `head_yaw` | -0.5 to 0.5 rad |
| `head_roll` | -0.5 to 0.5 rad |

It is exactly `motion.velocity` followed by `head.pose`. The response echoes all seven fields and `safety`. New clients should send the two 0.2 operations.

On a real duck, these seven numbers are `RLWalk.last_commands`. The walk policy produces the joint targets. The physics simulator in this repository runs such a policy too.

### `servo.position`

Request body `{"id": "head_yaw", "position": 0.2}`. `position` is radians, from `-2.5` to `2.5`. For a head joint, this also updates the matching field of `head.pose`. Response:

```json
{"id": "head_yaw", "position": 0.2, "safety": {"state": "ready"}}
```

### `servo.read`

Request body `{"id": "left_knee"}`. Response body `{"id": "left_knee", "position": 1.368}`.

### `motor.velocity`

Request body `{"id": "left_wheel", "velocity": 5.0}`, in rad/s within the motor's own limit. Response `{"id", "velocity", "safety"}`. On the rover, driving motors directly updates the reported `motion`.

### `encoder.read`

Request body `{"id": "left_wheel"}`. Response body `{"id": "left_wheel", "ticks": 360}`.

### `sensor.read`

Request body `{"id": "range_front"}`. Response body `{"id": "range_front", "value": 1.25, "unit": "meters"}`. Booleans are `0` or `1`.

### `payload.set`

Request body as above. Response body is the applied payload, including `position`. Refused with `unknown_device` for an unknown mount and `out_of_range` for a mass, size, or offset the dock does not accept.

### `payload.clear`

Request body `{}`. Response body `{"cleared": true}`.

### `robot.stop`

Request body `{}`. Response body `{"safety": {"state": "ready"}}`, or `estop` if the latch is set.

### `robot.reset`

Request body `{}`. Response body `{"safety": {"state": ...}}`. Neutral pose; a simulator also restores its starting position.

### `telemetry.subscribe`

Request body `{"hz": 10}`. `hz` may be omitted and then defaults to 10. It must be greater than 0 and at most 50. Response body `{"hz": 10}`.

The robot then emits `telemetry.sample` events:

```json
{
  "safety": {"state": "running"},
  "motion": {"forward": 0.1, "lateral": 0, "yaw": 0.4},
  "head": {"pitch": 0, "yaw": 0.2, "roll": 0, "neck_pitch": 0},
  "servos": {"left_knee": 1.4, "head_yaw": 0.2},
  "sensors": {"left_foot": 1, "right_foot": 0, "upright": 1},
  "base": {"x": 0.31, "y": -0.02, "z": 0.16, "yaw": 0.4}
}
```

`safety`, `motion`, and `servos` are always present. `head`, `motors`, `sensors`, `base`, and `payload` appear when the body has them. `base` is the robot's estimate of its own pose in meters and radians; simulators report the true one. `payload` is the phone from `payload.set`.

### `safety.estop`

Request body `{}`. Response body `{"safety": {"state": "estop"}}`.

### `safety.clear`

Request body `{}`. Response body `{"safety": {"state": "ready"}}` when a latch was cleared.

## Errors

Failed responses use `error.code`:

| Code | When |
| --- | --- |
| `unknown_device` | No device has that id. |
| `unsupported` | The device cannot do that, the robot does not accept that operation, or the operation is unknown. |
| `out_of_range` | A value is outside its limits, or a field the robot did not announce is non-zero. |
| `safety_blocked` | A motion or position command arrived while emergency stop is latched. |

## Reference simulators

`python -m jiadroid.sim` hosts one body on `tcp://127.0.0.1:8765`. Add `--host 0.0.0.0` so a phone on the same Wi-Fi can connect.

| `--robot` | Body | Needs |
| --- | --- | --- |
| `duck` (default) | Open Duck Mini, kinematic. Legs swing on a sine wave so a step is visible. | nothing |
| `rover` | The 2WD chassis prototype: differential drive, encoders, a bump switch, a range sensor, and a phone standing on the top plate. | nothing |
| `duck-physics` | Open Duck Mini in MuJoCo. A walking policy turns `motion.velocity` into joint targets; without one the duck stands and moves its head. `--policy file.onnx|file.zip`, `--view`. | `pip install -e ".[sim]"` |

Any of them accepts the same Follow Me command from the Android and iOS apps and from `python -m jiadroid.demo`.

The ESP32 program in [`firmware/`](../firmware/) is this same chassis on real hardware. It listens on TCP port 8765 and announces the same body. How to flash it is in [firmware.md](firmware.md).

## Client shape

```python
from jiadroid import connect

robot = connect("tcp://127.0.0.1:8765")
robot.kind                       # "biped"
robot.controls                   # {"motion.velocity": {"forward": (-0.15, 0.15), ...}, ...}
robot.mounts                    # (Mount("head", (0.02, 0, 0.22), 0.3), ...)
robot.set_payload("head", mass=0.170, size=(0.0078, 0.0716, 0.1476), name="iPhone 16")
robot.move(forward=0.1, yaw=0.4)
if robot.supports("head.pose"):
    robot.look(yaw=0.2)
robot.servo("left_knee").read()
robot.sensor("upright").read()
robot.stop()
robot.close()
```

`move` and `look` fail in the client with `unsupported` when the robot did not announce the operation, and `servo(id)` fails when that servo was not announced. `walk(...)` still exists for the duck and falls back to `move` plus `look` on other bodies.
