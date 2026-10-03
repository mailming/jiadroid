# Your phone, the robot's brain

**Give any robot a cheaper brain, eyes, ears, and internet, using the phone people already own, and let it act on its own.**

A robot duck, a robot vacuum like an iRobot Roomba, a robot dog: each one needs to see, hear, think, and connect. Today each maker has to build all of that into every robot, which makes robots expensive, or leaves it out, which keeps robots simple and remote-controlled.

Most people already carry a better version of that hardware in their pocket:

| A robot needs | The phone already has |
| --- | --- |
| A brain | A fast processor with an AI chip |
| Eyes | High-quality cameras |
| Ears and a voice | Microphones and a speaker |
| A sense of motion and place | Motion sensors and GPS |
| Internet | Wi-Fi and mobile data |
| A way to talk to its owner | A touchscreen, apps, and a personal assistant |

**Jiadroid lets a phone become that part of a robot.** Put the phone on the robot, or connect it nearby. The robot keeps its body: motors, wheels, legs, servos, brushes, grippers. The phone does the seeing, listening, deciding, and connecting.

That turns a robot that only follows remote control into one that acts on its own. It can follow you, respond when you talk to it, and choose where to go. The robot does not need a costly computer and camera of its own.

## Any robot, one kind of brain

```text
                          your phone
          brain, eyes, ears, internet, your apps
                              |
                       Jiadroid protocol
                              |
     +------------+-----------+-----------+------------+
     |            |                       |            |
 robot duck   robot vacuum            robot dog    robot arm
 legs, head   wheels, brushes         four legs    joints, gripper
```

The same phone, and the same apps, could make any of these bodies smarter.

## The idea: a common plug between brain and body

USB made it possible to plug almost any keyboard, camera, or printer into almost any computer. The computer does not need to know how each device is wired inside. The device says what it is, and the computer knows how to use it.

Jiadroid tries to do the same for robots.

When a phone connects, the robot introduces itself. It says what kind of body it is and which commands it takes: "I can move at up to 0.15 m/s and turn at 1 rad/s, and I have a head that pitches and yaws," or "I can move at 0.5 m/s and turn at 2 rad/s, and that is all." It lists its parts: "I have these 14 leg and head servos," or "I have two drive wheels, two encoders, and a bump switch." And it says where a phone can sit. The phone answers with its own weight and size, because a 170 g phone and a 230 g phone are not the same load, and a phone on the head is not the same load as a phone on the back. The phone does not need a custom driver for each machine. It reads those lists, scales its decisions to the limits, and knows what it can control.

The robot stays simple. It needs a small controller that moves its hardware, reports back what happened, and stops safely when asked. That controller is an ESP32. It already has Wi-Fi, Bluetooth, and USB serial, so the phone can use whichever of those the robot has. The ESP32 does not see, hear, or decide. The phone does that.

## Connect by Wi-Fi, Bluetooth, or USB

Robots are built differently, so the phone should connect however the robot can. The conversation between phone and robot stays the same. Only the connection changes.

| Connection | Good for |
| --- | --- |
| Wi-Fi | An ESP32 on the robot, a robot that already has a small computer such as Open Duck Mini, or a phone that sits nearby instead of riding on the robot |
| Bluetooth | An ESP32 talking to a phone with no network, including simple low-power robots and toys |
| USB cable | A phone mounted on the robot and plugged into the ESP32, with the most reliable connection and the option to charge the phone |

A robot vacuum could talk over Bluetooth. A robot dog could use Wi-Fi. A homemade rover could plug the phone straight into its ESP32 with USB. The same Follow Me app would work on all three.

## Why this could matter

**Robots get cheaper.** If the phone supplies the camera and computer, the robot does not need its own. The expensive part is something you already own.

**Robots get smarter over time.** A better phone, or a software update, upgrades the robot's brain without touching the body.

**Robots act on their own.** Many affordable robots only follow a remote or a fixed routine. With a phone's camera, microphone, and AI, they can react to people and places.

**One brain, many bodies.** The same phone and the same apps could drive a robot duck, a robot vacuum, a robot dog, a garden cart, or a classroom robot.

**Your robot is personal.** Your phone already has your voice assistant, your contacts, your preferences, and your photos. A robot using that phone can know who you are.

**Builders can focus.** Hardware makers build good bodies. App makers build good behaviors. Neither has to build the other half.

## The first test: "My phone can follow me"

The first product is intentionally simple.

**Put your phone on a robot, tap Follow Me, and the robot follows you.**

The phone's camera looks for you. It makes a few judgments:

- you are left, centered, or right
- you are too close or too far
- you are gone

Then it tells the body to turn, walk forward, slow down, or stop. If you disappear, or you get too close, it stops.

Nothing about that decision belongs to one robot. A robot vacuum turns with its wheels. A robot duck or dog turns with steps. The phone gives the same kind of instruction either way.

## First body: Open Duck Mini

The first demo body is [Open Duck Mini](https://github.com/apirrone/Open_Duck_Mini), an open-source walking duck with 14 servos. It is a good test because walking is harder than rolling, and because the duck already understands one simple instruction: walk forward, step sideways, or turn. Today that instruction comes from a game controller. In this project, it comes from the phone, through an ESP32 on the robot.

The duck's own walking software still handles balance and each leg joint. The phone only decides where it should go and where it should look.

That split is the point: the phone is the brain, and the robot keeps the reflexes it needs to move safely.

## First prototype: a 2WD chassis, phone on top

The first base is a [DIYables 2WD robot car chassis](https://www.amazon.com/dp/B0H4V8TR38): two DC motors with encoders, an L9110S motor driver, a caster, and a top plate. The kit is listed at 0.32 kg. The phone stands on that plate, screen facing forward, because Follow Me uses the front camera. A phone lying flat would look at the ceiling.

The chassis announces one mount, `top`, and only a move command. The same Follow Me app drives it, scaled to about 0.4 m/s. In the simulator this is `--robot rover`. The wheel track, the plate height, and the encoder slot count are the usual ones for this kind of chassis; the listing does not print them, so they get measured on the real plate.

## Try it on a laptop

You can see the idea working now, without a phone or a robot.

```bash
python -m pip install -e .
python -m jiadroid.demo                  # the duck
python -m jiadroid.demo --robot rover    # the rover, same brain
```

In the window, your mouse is you. The robot is drawn as a duck either way. The pale cone is what the phone camera sees. The large word is the phone's decision, and the numbers are the motion command the robot accepted, in its own units.

Try this:

1. Stay in the cone, ahead of the robot. It moves toward you.
2. Move to either side. It turns toward you, and a duck's head looks your way.
3. Come too close, or leave the cone. It stops.

Escape closes the window.

## Try it with physics, and teach the duck to walk

The kinematic duck above waves its legs on a timer. The physics duck is the real thing: the Open Duck Mini's own model (14 servos, its mass and inertia, its feet) in [MuJoCo](https://mujoco.org), standing on a floor, falling if it is pushed too hard. A walking policy decides the joint targets from what the duck senses, fifty times a second, exactly the way the duck's on-board runtime does.

```bash
python -m pip install -e ".[rl]"
python -m jiadroid.sim --robot duck-physics          # stands, moves its head, waits for a walker
python -m jiadroid.demo --robot duck-physics         # the Follow Me window on top of it
python -m jiadroid.rl                                # random actions in the training environment
```

Nobody has to hand-write the walk. The duck learns it by reinforcement learning in the Gymnasium environment `Jiadroid/OpenDuckMini-v0`: it is told a velocity, rewarded for matching it and staying up, and penalised for wasting torque or twitching. Sensor noise, delays, shoves, and floor friction are randomised so what it learns survives the move to hardware.

```bash
python -m pip install -e ".[train]"
python examples/train_duck.py --steps 20000000 --envs 8 --out runs/duck_ppo --export-onnx
python examples/train_duck.py --eval runs/duck_ppo.zip
mjpython -m jiadroid.rl --policy runs/duck_ppo.zip --view --clean            # watch it (macOS needs mjpython)
python -m jiadroid.sim --robot duck-physics --policy runs/duck_ppo.onnx --host 0.0.0.0
```

That last line puts the learned walker behind the protocol. Point the phone app at it and the phone's decision moves a physically simulated duck.

The environment keeps the same observation, action, and command layout as [Open Duck Playground](https://github.com/apirrone/Open_Duck_Playground) and [Open Duck Mini Runtime](https://github.com/apirrone/Open_Duck_Mini_Runtime). A policy trained here runs on the real duck's runtime as an `.onnx` file, and a Playground `.onnx` runs here with `--policy`. See [docs/training.md](docs/training.md) for the details and what to expect from training.

## Try it on a phone

The Android and iOS apps follow a real person, a printed photo of a person, or a printed mini-person QR code. The QR code wins when both are in view. The phone's front camera finds its target and decides the walk command. The apps run in landscape. By default the screen shows a pair of eyes that look toward the person. Both apps also listen on the microphone and speak a reply. Tap Debug to see the camera, the conversation, the walk command, and a simulated duck. The same command can be sent to the laptop simulator.

1. Pick a target. A real person needs nothing printed; set Person height to their height in millimeters (1700 by default). For a desk test, print `android/marker/printed-person.svg` at actual size (the ruler on the sheet reads 100 mm) and set Person height to 230. For the QR marker, print `android/marker/mini-person.svg` (60 mm code) or `mini-person-large.svg` (120 mm code) and set QR code to match.
2. Open the `android` folder in Android Studio, or `ios/Jiadroid.xcodeproj` in Xcode, and run it on a phone. The iPhone build needs a signing team selected in Xcode. A simulator has no useful camera for the printed marker.
3. Point the camera at the target. Center it and the duck walks forward. Move it to either side and the duck turns. Come too close, or leave the view, and the duck stops.
4. To drive a laptop simulator as well, start one where the phone can reach it, then enter that address in the app and tap Connect.

```bash
python -m pip install -e .
python -m jiadroid.sim --host 0.0.0.0                          # kinematic duck
python -m jiadroid.sim --host 0.0.0.0 --robot rover            # rover
python -m jiadroid.sim --host 0.0.0.0 --robot duck-physics --policy runs/duck_ppo.onnx
```

The phone and the laptop need to be on the same Wi-Fi. The simulator prints the address to enter. The app shows which body it connected to and drives whichever one answers.

### Pixel 8 Pro and robot motion sensors

The current Android test phone is a Google Pixel 8 Pro. Its bare-phone payload
profile (213 g, 76.5 x 162.6 x 8.8 mm) is already included in the Android app,
iOS app, Python library, and protocol documentation. Cases and clamps add to
the reported payload mass.

Android Activity Recognition is intended for human walking, running, cycling,
and vehicle travel, so it is not useful as robot odometry. A future Jiadroid
IMU integration should read the game rotation vector, gyroscope, and linear
acceleration directly with `SensorManager`, transform readings from the
landscape-mounted phone into the robot body frame, and fuse them with encoders
or another position source. See [Android phone IMU](docs/android-imu.md) for the
Pixel 8 Pro setup, coordinate frames, sampling guidance, and limitations.

## What exists today

- A shared language between phone and robot, version 0.2. The robot announces what kind of body it is, which motion and head commands it accepts with their limits, its parts, and where a phone can be mounted. The phone sends commands scaled to those limits, and reports its own weight, size, and which mount it is on. The robot reports what it is doing.
- Safety built into the language. A stop command halts motion, an emergency stop blocks all movement until someone clears it, and a reset puts a simulated robot back on its feet.
- Three simulated bodies behind that one language: a kinematic Open Duck Mini, a two-wheel rover, and an Open Duck Mini in MuJoCo physics.
- A Gymnasium environment for the physics duck, matched to the Open Duck project's observation and action layout, with a PPO training script and ONNX export. Trained policies run in the simulator and, as ONNX, on the duck's own runtime.
- Follow Me logic that decides where the robot should go, and rescales that decision to any body.
- The laptop demo above, where the decision side and the robot side are separate programs talking through that language over a local network connection, the same kind Wi-Fi would carry.
- Android and iOS apps that follow a real person, a printed photo of one, or a printed mini-person marker. Both show a pair of eyes by default, listen and answer out loud, keep the conversation on a Debug screen with the camera and simulated duck, and send the motion command to any of the laptop simulators.

## What is not built yet

- A trained walker that walks well. The training environment and script are here, and a few million steps produce a duck that stands and shuffles. Walking as well as the Open Duck project's policies needs longer training and its imitation reward, which relies on reference motions this repository does not ship. See [docs/training.md](docs/training.md).
- Telling people apart. With several people in view, the phone follows whichever one the pose detector picks.
- ESP32 firmware that speaks this language and drives a real body. A dock that mounts the phone on the duck, and a connection from that ESP32 to a real Open Duck Mini, so the phone's `motion.velocity` is written into `RLWalk.last_commands` in place of the game controller.
- Bluetooth and USB connections. The phone reaches the simulators over the network.
- Firmware for the 2WD chassis. The simulator already speaks for it. What is missing is ESP32 code that drives the L9110S from `motion.velocity` and counts the motor encoders, with the phone standing on the top plate.

## Where this goes

The first milestone is modest: "My phone can follow me."

The larger goal is: "My phone can give intelligence to any compatible machine."

After Follow Me, the same phone could add voice commands, obstacle avoidance, and navigation. The same connection could then reach grippers, arms, garden tools, or other devices, so hardware makers and app makers can build for one shared platform.

The technical contract between phone and robot is in [docs/protocol.md](docs/protocol.md). Training the duck's walk with reinforcement learning is in [docs/training.md](docs/training.md).
