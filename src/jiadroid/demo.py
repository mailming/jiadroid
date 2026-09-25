"""On-screen Follow Me demo for an Open Duck Mini.

The mouse is the person. Decisions become the duck's walk command, the same
forward / turn values its gamepad sends, and the picture moves from the
command the duck accepted.
"""

from __future__ import annotations

import math
import time
import tkinter as tk
from tkinter import font

from jiadroid import connect
from jiadroid.errors import RobotError
from jiadroid.follow import (
    FOV_RAD,
    VIEW_RANGE_PX,
    FollowDecision,
    Pose,
    Scene,
    decide,
    observe,
    step_pose,
)
from jiadroid.sim.controller import SimulatorServer

CANVAS_W = 900
CANVAS_H = 540

BG = "#f4f0e6"
INK = "#1c1915"
MUTED = "#6e675e"
CONE = "#e4d9c6"
GRID = "#e7e0d4"
PERSON = "#c4512c"
GO = "#1d6b45"
STOP_COLOR = "#8d3b2c"
TRAIL = "#b7ad9f"
DUCK = "#f0b429"
BEAK = "#e07a2f"


class FollowApp(tk.Tk):
    def __init__(self, robot) -> None:
        super().__init__()
        self._robot = robot
        self._pose = Pose(CANVAS_W / 2, 150, math.pi / 2)
        self._person: tuple[float, float] | None = None
        self._applied = (0.0, 0.0, 0.0, 0.0)
        self._holding_stop = False
        self._trail: list[tuple[float, float]] = []
        self._gait = 0.0
        self._knee = 0.0
        self._joint_timer = 0.0
        self._last = time.monotonic()
        self._alive = True

        self.title("Jiadroid — Follow Me")
        self.configure(bg=BG)
        self.resizable(False, False)

        title_font = font.Font(family="Segoe UI", size=22, weight="bold")
        body_font = font.Font(family="Segoe UI", size=12)
        situation_font = font.Font(family="Segoe UI", size=16)
        command_font = font.Font(family="Segoe UI", size=28, weight="bold")
        detail_font = font.Font(family="Consolas", size=12)

        header = tk.Frame(self, bg=BG)
        header.pack(fill="x", padx=28, pady=(22, 0))
        tk.Label(header, text="Follow Me", font=title_font, fg=INK, bg=BG).pack(anchor="w")
        tk.Label(
            header,
            text="Move the mouse. You are the person. The pale cone is the phone's camera.",
            font=body_font,
            fg=MUTED,
            bg=BG,
        ).pack(anchor="w", pady=(4, 0))
        servos = [device for device in robot.devices() if device.type == "servo"]
        tk.Label(
            header,
            text=f"{robot.name} · {len(servos)} servos",
            font=body_font,
            fg=MUTED,
            bg=BG,
            wraplength=840,
            justify="left",
        ).pack(anchor="w", pady=(8, 0))

        self.canvas = tk.Canvas(
            self,
            width=CANVAS_W,
            height=CANVAS_H,
            bg=BG,
            highlightthickness=0,
        )
        self.canvas.pack(padx=28, pady=(16, 8))
        self.canvas.bind("<Motion>", self._on_motion)
        self.canvas.bind("<Leave>", self._on_leave)

        self.situation = tk.Label(self, text="Person is lost", font=situation_font, fg=INK, bg=BG)
        self.situation.pack(anchor="w", padx=28)
        self.command = tk.Label(self, text="STOP", font=command_font, fg=STOP_COLOR, bg=BG)
        self.command.pack(anchor="w", padx=28, pady=(2, 0))
        self.detail = tk.Label(self, text="", font=detail_font, fg=MUTED, bg=BG)
        self.detail.pack(anchor="w", padx=28, pady=(4, 6))

        bars = tk.Frame(self, bg=BG)
        bars.pack(fill="x", padx=28, pady=(0, 22))
        self.forward_bar = tk.Canvas(bars, width=280, height=18, bg=BG, highlightthickness=0)
        self.turn_bar = tk.Canvas(bars, width=280, height=18, bg=BG, highlightthickness=0)
        tk.Label(bars, text="Forward", font=body_font, fg=MUTED, bg=BG).pack(side="left")
        self.forward_bar.pack(side="left", padx=(8, 24))
        tk.Label(bars, text="Turn", font=body_font, fg=MUTED, bg=BG).pack(side="left")
        self.turn_bar.pack(side="left", padx=(8, 0))

        self.bind("<Escape>", lambda _event: self._on_close())
        self.protocol("WM_DELETE_WINDOW", self._on_close)
        self.after(30, self._tick)

    def _on_motion(self, event: tk.Event) -> None:
        self._person = (float(event.x), CANVAS_H - float(event.y))

    def _on_leave(self, _event: tk.Event) -> None:
        self._person = None

    def _on_close(self) -> None:
        self._alive = False
        self.destroy()

    def _tick(self) -> None:
        if not self._alive:
            return
        now = time.monotonic()
        dt = min(0.1, now - self._last)
        self._last = now
        try:
            self._step(dt)
        except (RobotError, OSError, TimeoutError, ConnectionError) as exc:
            self.situation.configure(text="The robot stopped responding")
            self.command.configure(text="STOP", fg=STOP_COLOR)
            self.detail.configure(text=str(exc))
            self._alive = False
            return
        self._draw()
        self.after(30, self._tick)

    def _step(self, dt: float) -> None:
        scene = self._scene()
        decision = decide(scene)
        self._apply(decision)
        step_pose(self._pose, self._applied[0], self._applied[1], self._applied[2], dt)
        if decision.command == "STOP":
            self._gait = 0.0
        else:
            self._gait += dt * 7.0
        self._clamp()
        self._trail.append((self._pose.x, self._pose.y))
        if len(self._trail) > 60:
            del self._trail[:-60]
        self._joint_timer += dt
        if self._joint_timer >= 0.15:
            self._joint_timer = 0.0
            self._knee = self._robot.servo("left_knee").read()
        self._show(decision)

    def _scene(self) -> Scene:
        if self._person is None:
            return Scene(False, 0.0, 0.0)
        return observe(self._pose.x, self._pose.y, self._pose.heading, self._person[0], self._person[1])

    def _apply(self, decision: FollowDecision) -> None:
        if decision.command == "STOP":
            if not self._holding_stop:
                self._robot.stop()
                self._holding_stop = True
            self._applied = (0.0, 0.0, 0.0, 0.0)
            return
        command = (decision.forward, decision.lateral, decision.yaw, decision.head_yaw)
        if command != self._applied:
            self._robot.walk(
                forward=decision.forward,
                lateral=decision.lateral,
                yaw=decision.yaw,
                head_yaw=decision.head_yaw,
            )
            self._applied = command
        self._holding_stop = False

    def _clamp(self) -> None:
        pad = 36.0
        self._pose.x = min(max(self._pose.x, pad), CANVAS_W - pad)
        self._pose.y = min(max(self._pose.y, pad), CANVAS_H - pad)

    def _show(self, decision: FollowDecision) -> None:
        moving = decision.command != "STOP"
        self.situation.configure(text=decision.situation)
        self.command.configure(text=decision.command, fg=GO if moving else STOP_COLOR)
        self.detail.configure(
            text=(
                f"forward {self._applied[0]:.2f} m/s   turn {self._applied[2]:.2f} rad/s   "
                f"head {self._applied[3]:.2f}   knee {self._knee:.2f}   "
                f"{self._robot.safety}"
            )
        )
        self._draw_bar(self.forward_bar, self._applied[0] / 0.15)
        self._draw_bar(self.turn_bar, self._applied[2] / 1.0)

    def _draw_bar(self, canvas: tk.Canvas, speed: float) -> None:
        canvas.delete("all")
        width = 280
        mid = width / 2
        canvas.create_rectangle(0, 6, width, 12, fill="#ddd4c4", outline="")
        canvas.create_line(mid, 2, mid, 16, fill=MUTED)
        end = mid + max(-1.0, min(1.0, speed)) * (mid - 4)
        color = GO if speed >= 0 else STOP_COLOR
        canvas.create_rectangle(min(mid, end), 4, max(mid, end), 14, fill=color, outline="")

    def _draw(self) -> None:
        self.canvas.delete("all")
        self._draw_grid()
        self._draw_cone()
        self._draw_trail()
        if self._person is not None:
            self._draw_person()
        self._draw_robot()

    def _draw_grid(self) -> None:
        step = 40
        for x in range(0, CANVAS_W + 1, step):
            self.canvas.create_line(x, 0, x, CANVAS_H, fill=GRID)
        for y in range(0, CANVAS_H + 1, step):
            screen_y = CANVAS_H - y
            self.canvas.create_line(0, screen_y, CANVAS_W, screen_y, fill=GRID)

    def _draw_cone(self) -> None:
        reach = VIEW_RANGE_PX
        span = FOV_RAD / 2
        origin = self._screen(self._pose.x, self._pose.y)
        left = self._screen(
            self._pose.x + math.cos(self._pose.heading + span) * reach,
            self._pose.y + math.sin(self._pose.heading + span) * reach,
        )
        right = self._screen(
            self._pose.x + math.cos(self._pose.heading - span) * reach,
            self._pose.y + math.sin(self._pose.heading - span) * reach,
        )
        self.canvas.create_polygon(*origin, *left, *right, fill=CONE, outline="")

    def _draw_trail(self) -> None:
        if len(self._trail) < 2:
            return
        points: list[float] = []
        for x, y in self._trail:
            sx, sy = self._screen(x, y)
            points.extend((sx, sy))
        self.canvas.create_line(*points, fill=TRAIL, width=2, smooth=True)

    def _draw_person(self) -> None:
        assert self._person is not None
        x, y = self._screen(*self._person)
        self.canvas.create_oval(x - 12, y - 12, x + 12, y + 12, fill=PERSON, outline="")
        self.canvas.create_text(x, y - 22, text="you", fill=PERSON, font=("Segoe UI", 11, "bold"))

    def _draw_robot(self) -> None:
        step = 7.0 * math.sin(self._gait)
        body = [(-16, -12), (4, -14), (8, 0), (4, 14), (-16, 12)]
        self.canvas.create_polygon(
            *[coord for point in body for coord in self._local(*point)],
            fill=DUCK,
            outline="",
        )
        self._foot(-4 + step, 12)
        self._foot(-4 - step, -12)
        head = self._local(16, 0)
        self.canvas.create_oval(head[0] - 9, head[1] - 9, head[0] + 9, head[1] + 9, fill=DUCK, outline="")
        beak = [self._local(22, -3), self._local(30, 0), self._local(22, 3)]
        self.canvas.create_polygon(*[coord for point in beak for coord in point], fill=BEAK, outline="")
        eye = self._local(18, 4)
        self.canvas.create_oval(eye[0] - 1.5, eye[1] - 1.5, eye[0] + 1.5, eye[1] + 1.5, fill=INK, outline="")

    def _foot(self, forward: float, side: float) -> None:
        center = self._local(forward, side)
        self.canvas.create_oval(
            center[0] - 5,
            center[1] - 3,
            center[0] + 5,
            center[1] + 3,
            fill=BEAK,
            outline="",
        )

    def _local(self, forward: float, left: float) -> tuple[float, float]:
        heading = self._pose.heading
        origin_x, origin_y = self._screen(self._pose.x, self._pose.y)
        world_x = forward * math.cos(heading) - left * math.sin(heading)
        world_y = forward * math.sin(heading) + left * math.cos(heading)
        return origin_x + world_x, origin_y - world_y

    def _screen(self, x: float, y: float) -> tuple[float, float]:
        return x, CANVAS_H - y


def main() -> None:
    server = SimulatorServer("127.0.0.1", 0)
    server.start()
    robot = connect(f"tcp://{server.host}:{server.port}")
    try:
        FollowApp(robot).mainloop()
    finally:
        robot.close()
        server.close()


if __name__ == "__main__":
    main()
