# Android phone IMU

The Android Activity Recognition API classifies human movement and transport
(`STILL`, `WALKING`, `RUNNING`, `ON_BICYCLE`, and `IN_VEHICLE`). It is not a
robot pose or odometry API: its updates are slow, robot motion may be
misclassified, and it does not report position, heading, or velocity.

For a phone mounted on a robot, read the motion sensors directly with
`SensorManager`.

## Pixel 8 Pro used by this project

The current Android test phone is a **Google Pixel 8 Pro**. Jiadroid already
recognizes this model as a bare 0.213 kg payload measuring 76.5 x 162.6 x
8.8 mm. Add the case and clamp mass when reporting the real payload.

For a vertical `head`, `back`, or rover `top` mount, its protocol size is:

```json
{"mount":"top","mass":0.213,"size":[0.0088,0.0765,0.1626],"offset":[0,0,0],"name":"Pixel 8 Pro"}
```

Do not hard-code the phone's available sensors. Query them at runtime because
Android can expose different fused sensors after an OS or sensor-hub update.

## Sensors

Register these sensors when available:

| Sensor | Android constant | Robot use |
| --- | --- | --- |
| Game rotation vector | `TYPE_GAME_ROTATION_VECTOR` | Preferred orientation near motors because it ignores the magnetometer. Roll and pitch remain stable, but yaw drifts. |
| Rotation vector | `TYPE_ROTATION_VECTOR` | Earth-referenced orientation and heading. Its magnetometer-based yaw can be disturbed by motors, steel, speakers, and high current wiring. |
| Gyroscope | `TYPE_GYROSCOPE` | Angular velocity in rad/s for turn rate and state estimation. |
| Linear acceleration | `TYPE_LINEAR_ACCELERATION` | Estimated acceleration in m/s² after gravity removal; useful for braking, impacts, and short-term state estimation. |

Use one rotation-vector source at a time. Prefer the game rotation vector on a
robot, and use the normal rotation vector only after checking magnetic
interference on the assembled body.

`SENSOR_DELAY_GAME` is a suitable initial rate (nominally about 50 Hz).
Registration rates are requests rather than guarantees, so use each
`SensorEvent.timestamp` and measure the actual interval. Only add
`android.permission.HIGH_SAMPLING_RATE_SENSORS` if Jiadroid later requests more
than 200 Hz.

Use `SensorManager.getQuaternionFromVector()` to decode rotation-vector events
instead of assuming every event contains the same number of values.

## Frames and mounting

Android device coordinates are:

- +X toward the phone's right edge
- +Y toward the phone's top edge
- +Z out through the display

Jiadroid's robot body frame is +X forward, +Y left, +Z up. The app runs in
landscape, but display rotation does not automatically turn sensor readings
into this body frame. Apply a fixed transform for the selected mount and the
phone's physical orientation. Record whether the USB port is on the robot's
left or right; the two landscape orientations differ by 180 degrees.

Calibrate the transform while the robot is level and stationary. Store it with
the mount configuration rather than inferring it from screen orientation.

## Limits and mechanical setup

- Do not integrate phone acceleration alone for long-term position or
  velocity. Small bias errors grow rapidly. Fuse it with wheel encoders,
  visual odometry, GPS, UWB, or another external position source.
- Put a thin silicone or foam layer between the Pixel and a vibrating chassis,
  while keeping the mount rigid enough that the phone cannot move relative to
  the robot.
- Keep the phone away from motors, magnets, speakers, and high-current cables
  when using `TYPE_ROTATION_VECTOR`.
- A partial wake lock does not by itself guarantee continuous background
  sensing. A future always-on IMU implementation should use a foreground
  service, manage its lifecycle explicitly, and release the wake lock when
  sensing stops.
- Android Emulator motion data is synthetic. Validate noise, latency, frame
  mapping, magnetic interference, and vibration on the physical Pixel 8 Pro.

The current Jiadroid Android app does not yet stream phone IMU samples into the
robot protocol. This document defines the intended hardware and integration
approach for that work.
