# Idea bank

Loose product and platform ideas for Jiadroid. Not a roadmap commitment — a place to park sparks so they do not get lost. Add new entries at the top of the right section. Mark status as `spark`, `sketching`, `building`, `shipped`, or `parked`.

The shared bet: **phone = brain, ESP32 body = muscles**, same protocol for many machines.

---

## Robot bodies / apps

### Front / back camera switch

- **Status:** spark  
- **Why:** Follow Me defaults to the front camera (eyes toward people, screen-forward dock). The **back** camera is often sharper, can **auto/manual focus**, and is better for distant people, markers, table edges, or ball-finding. Front cameras usually have fixed focus.  
- **Idea:** Debug (and maybe a long-press on the eyes) toggles front ↔ back. Remap look/follow mirroring when the facing flips; remember last choice. On back camera, allow tap-to-focus / continuous AF where the platform supports it. Mount note: back-camera mode may want the phone flipped or a rear-facing dock, or the phone off-robot aiming at the scene.  
- **Hard parts:** preview mirror and overlay math differ by facing; FOV / focal length change Follow gains; don’t drop the session mid-bind.

### Local audience memory (who is talking)

- **Status:** spark  
- **Why:** One short talk feels clever; remembering “you’re Jia, you like jokes, there’s a kid in the room” makes Lulu feel like *their* robot.  
- **Idea:** On-phone audience KB (name, notes, last topics, soft `likelyKid` hint). Inject a tiny summary into local `reply()` / `Brain` system prompt. Clearable in Debug.  
- **Voice tone:** Vosk / Apple Speech give text only — not man/woman/kid. Optional pitch band is a weak hint; better “who” = enroll voiceprints or fuse camera face size. Prefer self-intro (“I’m Sam”) over guessing gender.  
- **Hard parts:** privacy (stay on device); don’t invent gender; keep prompt short so local models stay fast.

### Demo-complete Follow Me (close the first show)

- **Status:** spark  
- **Why:** Software is ahead of a floor demo that doesn’t embarrass itself.  
- **Idea (MVP checklist, not new products):**  
  1. Real 2WD bring-up: motors/encoders oriented, Follow over USB then Wi‑Fi once.  
  2. Vertical phone dock so the front camera faces people (or flip for back-camera mode).  
  3. Front / back camera switch + focus on the back lens when useful.  
  4. Wire bump (+ optional HC-SR04); phone soft-stops on bump / near range (chassis already announces sensors).  
  5. Port Android spoken motion (`stop` / `follow` / turn) to iOS — today “Lulu, stop” only moves motors on Android.  
  6. One rehearsed script + keep README “what’s built / not yet” in sync.  
- **Hard parts:** hardware verification; iPhone still Wi‑Fi-only for the link.

### Lost-person search spin

- **Status:** spark  
- **Why:** When follow loses the person, STOP feels dead; a slow in-place search looks alive and often reacquires.  
- **Idea:** After N frames lost → gentle yaw sweep (or alternate left/right), eyes “curious/confused”; stop on reacquire or timeout. Honor bump/range.  
- **Hard parts:** don’t spin off a table; cancel immediately on wake “stop.”

### Eyes show link / safety state

- **Status:** spark  
- **Why:** Demo audience can’t see Debug; when USB drops or bump hits, the face should change.  
- **Idea:** Map link-lost → sad/confused blink; bump/estop → wide startled; reconnect → happy flash then listening. Reuse emotion enum; no new art.  
- **Hard parts:** don’t thrash emotions on flaky CDC; debounce.

### Speak softer when close

- **Status:** spark  
- **Why:** At 30 cm, TTS is shouty; distance is already in Follow.  
- **Idea:** Scale TTS volume (and maybe speech rate) from person/marker distance; excited emotion still allowed, just quieter.  
- **Hard parts:** Android/iOS TTS volume APIs differ; don’t fight system volume every frame.

### Short-term talk memory without a cloud Brain

- **Status:** spark  
- **Why:** Local `reply()` is single-turn; “what did I just say?” fails unless Brain is configured.  
- **Idea:** Keep last ~6 turns on phone; local replies can echo/refer; optional tiny on-device model later. Audience KB (above) is long-term; this is the open chat window.  
- **Hard parts:** keep it tiny; wake name still gates new turns.

### Table-edge awareness (cliff stop without only IR)

- **Status:** spark  
- **Why:** A tabletop rover should not drive off the edge. Humans do this with vision plus motion sense; the Follow Me camera today looks at people, not the near ground.
- **Idea:** Treat it as geometry + filtering, not magic ML.
  1. Detect the table boundary in the lower camera frame (line / floor-plane break).
  2. With mount pose and camera intrinsics, intersect rays with the table plane → distance from bumper to edge in meters.
  3. While moving, integrate commanded or measured velocity (and yaw) so  
     \(d_{k+1} \approx d_k - v\,\Delta t\cos\theta\), and correct \(d\) whenever vision sees the edge again.
  4. If \(d < d_{\text{safe}}\) (stopping distance + margin) → `robot.stop` / soft clamp on `motion.velocity`.
- **Fuse with:** chassis encoder odometry already on the 2WD firmware.
- **Hardware backup:** downward IR cliff sensors on the ESP32 for a hard, low-latency stop when vision is wrong or laggy.
- **Fits protocol:** phone decides; body can also latch safety locally when IR trips.

### Golf-ball / ping-pong-ball picker

- **Status:** spark  
- **Why:** A clear, demo-friendly job: roam a lawn or play area, find small bright spheres, collect them, dump in a bin.
- **Phone brain:** detect balls (color / circle / small object model), choose nearest reachable target, aim the chassis, decide “approach / scoop / reverse / search.”
- **Body:** wheeled base + simple collector (roller, rake, or open hopper) + optional dump servo. Announce mounts and actuators in `session.hello`.
- **Variants:** indoor ping-pong under a table; outdoor golf on short grass; “how many balls in two minutes” challenge mode on the phone.
- **Hard parts:** grass clutter, partial occlusion, ball under the bumper, not crushing the ball.

### Follow Me (person / marker)

- **Status:** building (core app exists)  
- Current Android/iOS Follow Me with eyes UI, voice, USB-C/Wi‑Fi link to chassis.
- Wake name (default **Lulu**, user-assignable) so it only answers when addressed.

### Wake name / attention

- **Status:** shipped  
- Ignore ambient talk until someone says the robot’s name (`hey Lulu, …`). That opens ~30s of conversation without repeating the name; each answered turn refreshes the window. Typed Debug lines skip the gate.

### Expressive eyes (medium) driven by conversation emotion

- **Status:** shipped  
- **Why:** The eyes are the face; blink/idle alone helps, but matching mood to talk makes Lulu feel alive.
- **Idea:** Keep a tiny emotion enum for the face (`neutral`, `happy`, `curious`, `listening`, `thinking`, `confused`, `sad`, `excited`). Conversation returns **text + emotion**:
  - Local `reply()` / wake “Yes?” → fixed mapping (greeting → happy, name-only → curious, lost person → confused).
  - Cloud `Brain` → ask for a short JSON or trailing tag, e.g. `{"say":"…","emotion":"happy"}`, or `… <<happy>>` stripped before TTS.
  - Voice lifecycle: hearing → `listening`, model wait → `thinking`, speaking → hold reply emotion, then ease back to follow/idle.
- **Eyes play:** blink, brow/lid shape, pupil scale, cheek tint — still Canvas/SwiftUI, driven by `lookX`/`lookY` + emotion state.
- **Hard parts:** model format must be strict (fallback `neutral` on parse fail); don’t let emotion tags leak into speech; keep one shared emotion vocabulary on Android and iOS.

### Voice-command robot butler

- **Status:** spark  
- “Come here,” “stop,” “go to the kitchen doorway,” “find my keys” — reuse conversation stack; add spatial goals when navigation exists.

### Obstacle-aware wander / patrol

- **Status:** spark  
- Front ranging (`range_front` already in chassis hello) + phone vision for larger obstacles; slow or turn before contact.

### Garden helper (spot water / weed mark)

- **Status:** parked  
- Phone sees plant or marked pot; body drives a pump or drops a flag. Same plug, different payload.

### Dock-and-charge / return-to-base

- **Status:** spark  
- Visual or IR dock target; phone steers in; body handles contact switch and charge sense later.

---

## Platform / link

### Phone-provisioned Wi‑Fi + OTA firmware

- **Status:** building (on `feature/usb-c-link`)  
- USB (Android) or Wi‑Fi: `wifi.set` into NVS; stream `firmware.begin` + `.bin` for OTA. First flash still from a laptop.
- **MVP polish:** after Save Wi‑Fi, surface the robot’s IP / “ready for iPhone” in the eyes/status line so the second phone can connect without a laptop serial log.

### Bluetooth body link

- **Status:** spark  
- Same newline-JSON protocol over BLE UART / Classic SPP for robots that should not depend on Wi‑Fi.

### On-phone IMU as robot sense

- **Status:** sketching — see [android-imu.md](android-imu.md)  
- Game rotation / gyro / linear accel fused with wheel odometry for better pose while following or tracing a table edge.

### Multi-person identity (“follow *me*, not them”)

- **Status:** spark  
- Re-ID or “lock on tap”; README already calls out the gap.

### Real Open Duck Mini dock

- **Status:** spark  
- Phone mount on the duck; ESP32 writes `motion.velocity` into the walk policy instead of a gamepad.

### App-side firmware store

- **Status:** spark  
- Phone downloads signed chassis builds and flashes over OTA; no laptop after the first brick recovery path.

---

## How to add an idea

Copy this stub:

```markdown
### Short title

- **Status:** spark
- **Why:** one sentence of user value
- **Idea:** how phone brain + body would split the work
- **Hard parts:** what will hurt
```

Keep ideas concrete enough that a future you (or teammate) can turn one into a branch without reconstructing the conversation.
