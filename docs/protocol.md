# Jiadroid protocol 0.1

Technical contract for the phone-to-robot link. The idea and the laptop demo are in the [README](../README.md).

Version 0.1 covers discovery, servo position, the Open Duck Mini walk command, telemetry, and safety. The same messages are what a later phone app should send to the duck's runtime.

## Transport

The protocol is a stream of messages. Version 0.1 carries that stream over TCP. The reference simulator listens on `127.0.0.1:8765`. A later USB serial or Wi-Fi link replaces the socket and keeps these messages.

## Framing

Each message is one UTF-8 JSON object followed by a single `\n`. Carriage returns before the newline are ignored. A message, including the newline, is at most 8192 bytes. Encoders emit compact JSON. Parsers accept normal JSON whitespace inside the line.

## Envelope

```json
{"v":1,"id":"1","kind":"req","op":"walk.velocity","body":{"forward":0.1,"yaw":0.4}}
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

## Devices

A device is `{type, id, capabilities}`. `type` and `id` together identify it.

The reference robot is an [Open Duck Mini](https://github.com/apirrone/Open_Duck_Mini). It announces 14 devices of type `servo`, each with capability `position`. Names and standing pose match `HWI` in [Open Duck Mini Runtime](https://github.com/apirrone/Open_Duck_Mini_Runtime): `left_hip_yaw`, `left_hip_roll`, `left_hip_pitch`, `left_knee`, `left_ankle`, `neck_pitch`, `head_pitch`, `head_yaw`, `head_roll`, `right_hip_yaw`, `right_hip_roll`, `right_hip_pitch`, `right_knee`, `right_ankle`.

## Safety

States: `ready`, `running`, `estop`.

- A non-zero forward, lateral, or yaw command moves `ready` to `running`.
- A head-only command stays in `ready`.
- `robot.stop` zeros the walk command and returns every servo to the standing pose. It does not clear an emergency stop.
- `safety.estop` does the same and latches `estop`.
- While latched, `walk.velocity` and `servo.position` fail with `safety_blocked`.
- `safety.clear` moves `estop` to `ready`.

## Operations

### `session.hello`

Event sent by the controller immediately after connect.

```json
{
  "protocol": "jiadroid",
  "version": "0.1",
  "robot": {"id": "open-duck-mini", "name": "Open Duck Mini"},
  "safety": {"state": "ready"},
  "devices": []
}
```

### `session.ping`

Request body `{}`. Response body `{"ok": true}`.

### `session.bye`

Request body `{}`. Response body `{"ok": true}`. The controller then closes the connection.

### `devices.list`

Request body `{}`. Response body `{"devices": [ ... ]}`.

### `walk.velocity`

This is the phone's replacement for the duck's Xbox controller. The fields are the runtime's `last_commands`, in the same units and limits (`xbox_controller.py`):

| Field | Meaning | Limit |
| --- | --- | --- |
| `forward` | `lin_vel_x`, m/s | -0.15 to 0.15 |
| `lateral` | `lin_vel_y`, m/s | -0.2 to 0.2 |
| `yaw` | angular velocity, rad/s | -1 to 1 |
| `neck_pitch` | radians | -0.34 to 1.1 |
| `head_pitch` | radians | -0.78 to 0.3 |
| `head_yaw` | radians | -0.5 to 0.5 |
| `head_roll` | radians | -0.5 to 0.5 |

Omitted fields are `0`. Positive `yaw` turns the duck counterclockwise.

```json
{"forward": 0.1, "lateral": 0, "yaw": 0.4, "head_yaw": 0.2}
```

The response echoes the command and includes `safety`.

On a real duck, these seven numbers are written into `RLWalk.last_commands`. The ONNX walk policy in Open Duck Mini Runtime still produces the joint targets. This repository does not run that policy.

### `servo.position`

Request body `{"id": "head_yaw", "position": 0.2}`. `position` is radians, from `-2.5` to `2.5`. For a head joint, this also updates that field of the walk command. Response:

```json
{"id": "head_yaw", "position": 0.2, "safety": {"state": "ready"}}
```

### `servo.read`

Request body `{"id": "left_knee"}`. Response body `{"id": "left_knee", "position": 1.368}`.

### `robot.stop`

Request body `{}`. Response body `{"safety": {"state": "ready"}}`, or `estop` if the latch is set. Servos return to the standing pose from the runtime's `init_pos`.

### `telemetry.subscribe`

Request body `{"hz": 10}`. `hz` may be omitted and then defaults to 10. It must be greater than 0 and at most 50. Response body `{"hz": 10}`.

The controller then emits `telemetry.sample` events:

```json
{
  "safety": {"state": "running"},
  "commands": {"forward": 0.1, "lateral": 0, "yaw": 0.4, "neck_pitch": 0, "head_pitch": 0, "head_yaw": 0.2, "head_roll": 0},
  "servos": {"left_knee": 1.4, "head_yaw": 0.2}
}
```

While a walk command is active, the simulator swings the leg joints around the standing pose so a step is visible. That swing is a stand-in for the real policy.

### `safety.estop`

Request body `{}`. Response body `{"safety": {"state": "estop"}}`.

### `safety.clear`

Request body `{}`. Response body `{"safety": {"state": "ready"}}` when a latch was cleared.

## Errors

Failed responses use `error.code`:

| Code | When |
| --- | --- |
| `unknown_device` | No device has that id. |
| `unsupported` | The device cannot do that, or the operation is unknown. |
| `out_of_range` | A walk command or servo position is outside its limits. |
| `safety_blocked` | A walk or servo command arrived while emergency stop is latched. |

## Reference simulator

`python -m jiadroid.sim` listens on `tcp://127.0.0.1:8765` and announces the 14 Open Duck Mini servos. The Follow Me window decides a walk command, sends it with this protocol, and draws the duck from the command the simulator accepted.

## Client shape

```python
from jiadroid import connect

robot = connect("tcp://127.0.0.1:8765")
robot.devices()
robot.walk(forward=0.1, yaw=0.4, head_yaw=0.2)
robot.servo("left_knee").read()
robot.stop()
robot.close()
```

`servo(id)` fails in the client when that servo was not announced. `walk` waits for the controller's response.
