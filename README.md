# Your phone, the robot's brain

Every robot needs a brain: a camera to see, a processor to think, sensors to know where it is, a way to talk to people, and a connection to the internet. Robot makers rebuild that brain again and again, in every machine.

You already carry one in your pocket.

A modern phone has a good camera, a fast processor with an AI chip, motion sensors, a microphone and speaker, a touchscreen, GPS, Wi-Fi, a battery, and apps that already know you. It gets better every year, and you replace it anyway.

**Jiadroid lets a phone become the brain of a robot.** The robot keeps the body: motors, servos, wheels, legs, grippers, pumps. The phone does the seeing, deciding, and talking.

## The idea: a common plug between brain and body

USB made it possible to plug almost any keyboard, camera, or printer into almost any computer. The computer does not need to know how each device is wired inside. The device says what it is, and the computer knows how to use it.

Jiadroid tries to do the same for robots.

```text
                 your phone
      camera, AI, sensors, voice, apps
                     |
              Jiadroid protocol
                     |
     +---------------+---------------+
     |               |               |
 walking robot   wheeled robot    robot arm
 servos, legs    motors, wheels   joints, gripper
```

When a phone connects, the robot introduces itself: "I have these servos," or "I have two wheel motors and a distance sensor." The phone does not need a custom driver for each machine. It reads that list and knows what it can control.

The robot stays simple. It needs a small controller that moves its hardware, reports back what happened, and stops safely when asked.

## Why this could matter

**Robots get cheaper.** If the phone supplies the camera and computer, the robot does not need its own. The expensive part is something you already own.

**Robots get smarter over time.** A better phone, or a software update, upgrades the robot's brain without touching the body.

**One brain, many bodies.** The same phone and the same apps could drive a walking toy, a garden cart, a desk arm, or a classroom robot.

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

Nothing about that decision belongs to one robot. A wheeled robot turns with wheels. A walking robot turns with steps. The phone gives the same kind of instruction either way.

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

## What exists today

- A shared language between phone and robot. The robot announces its hardware. The phone sends commands. The robot reports what it is doing.
- Safety built into the language. A stop command halts motion, and an emergency stop blocks all movement until someone clears it.
- A simulated Open Duck Mini that announces its 14 servos and accepts walk commands.
- Follow Me logic that decides where the robot should go.
- The laptop demo above, where the decision side and the robot side are separate programs talking through that language.

## What is not built yet

- A phone app. The laptop plays the phone's role for now.
- Real person detection with a camera. The mouse stands in for the person.
- A connection to a real Open Duck Mini. The next step is to feed the phone's walk command into the duck's existing walking software, in place of its game controller.
- A second robot body, such as a wheeled robot or an arm, to prove that one phone can drive very different machines.

## Where this goes

The first milestone is modest: "My phone can follow me."

The larger goal is: "My phone can give intelligence to any compatible machine."

After Follow Me, the same phone could add voice commands, obstacle avoidance, and navigation. The same connection could then reach grippers, arms, garden tools, or other devices, so hardware makers and app makers can build for one shared platform.

The technical contract between phone and robot is in [docs/protocol.md](docs/protocol.md).
