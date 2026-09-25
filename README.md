# Your phone, the robot's brain

You already carry a camera, a fast processor, and an AI assistant in your pocket. Jiadroid is a test of one idea: that phone can be the brain, and the robot can be the body.

The body for this experiment is [Open Duck Mini](https://github.com/apirrone/Open_Duck_Mini), a small walking duck with 14 servos. The phone watches you and sends the same kind of command the duck's gamepad already uses: walk forward, step sideways, turn, and look with the head. The duck's own walking policy still owns the individual joints.

**Put your phone on the duck, tap Follow Me, and it follows you.**

That is the whole first product. It is small on purpose. If a phone can steer this duck, the same phone can later steer a different machine.

## What you would see

The phone's camera looks for you. It only needs a few judgments:

- you are left, centered, or right
- you are too close or too far
- you are gone

Those become a walk command: turn left, go forward, turn right, slow down, or stop. The head turns toward you. If you disappear, or you get too close, it stops and stands.

```text
your phone
camera, decisions, follow logic
        |
        |  forward, sideways, turn, head
        v
Open Duck Mini
14 servos and its walking policy
        |
      steps
```

The interesting bet is the plug in the middle. The phone does not learn a custom driver for one chassis. The duck introduces its servos. Another machine could introduce different hardware through the same kind of conversation. The phone stays the brain. The machine changes.

## Try it on a laptop

Today you can try that split on a laptop, and see if it is worth building onto a real duck.

You are the person. Your mouse is where you stand. A pale cone is the phone's camera. The words on screen are the phone's decision. Forward and turn are the command the duck accepted, in the same units as its gamepad. The knees move because that command started a step.

```bash
python -m pip install -e .
python -m jiadroid.demo
```

Three things to try:

1. Stay in the cone, ahead of the duck. It walks toward you.
2. Step to either side. It turns to face you, and the head looks your way.
3. Come too close, or walk outside the cone. It stops.

Escape closes the window.

## What is working

The phone side and the duck side are separate programs. They talk with a small protocol: the duck announces its 14 servos, the phone sends a walk command, the duck reports joint positions, and an emergency stop latches until it is cleared. The follow logic sits entirely on the phone side.

This window does not run Open Duck Mini's walking neural net. It accepts the command that net already expects. On the real robot, that command would replace the Xbox controller inside [Open Duck Mini Runtime](https://github.com/apirrone/Open_Duck_Mini_Runtime).

## The next build

A phone app, a link to the duck's Raspberry Pi, and the real walk policy come after this feels worth building. A real camera would replace the mouse.

The question for this version is simpler: is "my phone can follow me" a convincing first step on this duck?

The technical contract is in [docs/protocol.md](docs/protocol.md).
