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

When a phone connects, the robot introduces itself: "I have these leg servos," or "I have two drive wheels and a bump sensor." The phone does not need a custom driver for each machine. It reads that list and knows what it can control.

The robot stays simple. It needs a small controller that moves its hardware, reports back what happened, and stops safely when asked.

## Connect by Wi-Fi, Bluetooth, or USB

Robots are built differently, so the phone should connect however the robot can. The conversation between phone and robot stays the same. Only the connection changes.

| Connection | Good for |
| --- | --- |
| Wi-Fi | Robots that already have a small computer on board, like Open Duck Mini, or phones that sit nearby instead of riding on the robot |
| Bluetooth | Simple, low-power robots and toys that need a wireless link without a network |
| USB cable | A phone mounted directly on the robot, with the most reliable connection and the option to charge the phone |

A robot vacuum could talk over Bluetooth. A robot dog could use Wi-Fi. A homemade rover could plug the phone straight into its controller with USB. The same Follow Me app would work on all three.

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

The first demo body is [Open Duck Mini](https://github.com/apirrone/Open_Duck_Mini), an open-source walking duck with 14 servos. It is a good test because walking is harder than rolling, and because the duck already understands one simple instruction: walk forward, step sideways, or turn. Today that instruction comes from a game controller. In this project, it comes from the phone.

The duck's own walking software still handles balance and each leg joint. The phone only decides where it should go and where it should look.

That split is the point: the phone is the brain, and the robot keeps the reflexes it needs to move safely.

## Try it on a laptop

You can see the idea working now, without a phone or a robot.

```bash
python -m pip install -e .
python -m jiadroid.demo
```

In the window, your mouse is you. The duck is the robot. The pale cone is what the phone camera sees. The large word is the phone's decision, and the numbers are the walk command the duck accepted.

Try this:

1. Stay in the cone, ahead of the duck. It walks toward you.
2. Move to either side. It turns toward you, and its head looks your way.
3. Come too close, or leave the cone. It stops.

Escape closes the window.

## Try it on a phone

The Android app follows a real person, a printed photo of a person, or a printed mini-person QR code. The QR code wins when both are in view. The phone's front camera finds its target and decides the walk command. The app runs in landscape. By default the screen shows a pair of eyes that look toward the person. The Android app also listens on the microphone and speaks a reply. Tap Debug to see the camera, the conversation, the walk command, and a simulated duck. The same command can be sent to the laptop simulator. The Android and iOS apps work the same way.

1. Pick a target. A real person needs nothing printed; set Person height to their height in millimeters (1700 by default). For a desk test, print `android/marker/printed-person.svg` at actual size (the ruler on the sheet reads 100 mm) and set Person height to 230. For the QR marker, print `android/marker/mini-person.svg` (60 mm code) or `mini-person-large.svg` (120 mm code) and set QR code to match.
2. Open the `android` folder in Android Studio, or `ios/Jiadroid.xcodeproj` in Xcode, and run it on a phone. The iPhone build needs a signing team selected in Xcode. A simulator has no useful camera for the printed marker.
3. Point the camera at the target. Center it and the duck walks forward. Move it to either side and the duck turns. Come too close, or leave the view, and the duck stops.
4. To drive the laptop simulator as well, start it where the phone can reach it, then enter that address in the app and tap Connect.

```bash
python -m pip install -e .
python -m jiadroid.sim --host 0.0.0.0
```

The phone and the laptop need to be on the same Wi-Fi. The simulator prints the address to enter.

## What exists today

- A shared language between phone and robot. The robot announces its hardware. The phone sends commands. The robot reports what it is doing.
- Safety built into the language. A stop command halts motion, and an emergency stop blocks all movement until someone clears it.
- A simulated Open Duck Mini that announces its 14 servos and accepts walk commands.
- Follow Me logic that decides where the robot should go.
- The laptop demo above, where the decision side and the robot side are separate programs talking through that language over a local network connection, the same kind Wi-Fi would carry.
- Android and iOS apps that follow a real person, a printed photo of one, or a printed mini-person marker. Both show a pair of eyes by default, with the camera and simulated duck on a Debug screen, and both can send the walk command to the laptop simulator.

## What is not built yet

- Telling people apart. With several people in view, the phone follows whichever one the pose detector picks.
- A dock that mounts the phone on the duck, and a connection to a real Open Duck Mini. The next step is to feed the phone's walk command into the duck's existing walking software, in place of its game controller.
- Bluetooth and USB connections. The phone reaches the simulator over the network.
- A second robot body, such as a robot vacuum or a robot dog, to prove that one phone can drive very different machines.

## Where this goes

The first milestone is modest: "My phone can follow me."

The larger goal is: "My phone can give intelligence to any compatible machine."

After Follow Me, the same phone could add voice commands, obstacle avoidance, and navigation. The same connection could then reach grippers, arms, garden tools, or other devices, so hardware makers and app makers can build for one shared platform.

The technical contract between phone and robot is in [docs/protocol.md](docs/protocol.md).
