# Eyes design brief (for Gemini / ChatGPT / design tools)

The Follow Me face is a **procedural** pair of eyes (Android `EyesView`, iOS `EyesFace` / `EyeMood`), not sprites or Lottie. A design tool should return **numbers** that fit the existing `Mood` contract so both apps stay in sync.

## Runtime contract

| Input | Range | Meaning |
| --- | --- | --- |
| `lookX`, `lookY` | −1 … 1 | Gaze toward the person / turn direction |
| `blink` | 0 … 1 | Lid close amount |
| `emotion` | enum below | Conversation + link / bump / search |

**Emotions (exact names, do not invent new ones):**  
`neutral`, `happy`, `curious`, `listening`, `thinking`, `confused`, `sad`, `excited`

**Per-emotion mood fields (paste into both apps):**

| Field | Typical range | Notes |
| --- | --- | --- |
| `face` | `#RRGGBB` | Full-screen fill + lid color |
| `iris` | `#RRGGBB` | Soft green today (`#6AAE70`) |
| `lidOpen` | 0.6 … 1.0 | Lower = squint / smile lids |
| `irisScale` | 0.8 … 1.3 | |
| `pupilScale` | 0.8 … 1.3 | |
| `heightScale` | 0.85 … 1.15 | Eye oval height |
| `browLift` | −0.15 … 0.20 | |
| `browTilt` | −1.0 … 1.0 | |
| `cheekAlpha` | 0 … 255 | Flat blush only |

**Hard limits for any redesign**

- Two eyes, brows, optional cheeks only — no mouth, nose, text, particles, or heavy shadows.
- Same geometry family for every emotion (parameter shifts, not new shapes).
- Readable from ~2 m; cute but consumer-hardware clean (not uncanny, not mega-anime).
- Must stay Canvas / SwiftUI–drawable; no video or image sequences required for MVP.

**Where to apply a returned table**

- Android: `EyesView.mood()`
- iOS: `EyeMood.init(_:)` in `ContentView.swift`

Current neutral-ish defaults: face `#FFF4D2`, iris `#6AAE70`; happy `lidOpen` ≈ 0.78; excited `cheekAlpha` ≈ 70; sad `lidOpen` ≈ 0.7.

---

## Footer (append to every prompt)

```text
RULES
1) Output valid JSON only. No markdown fences. No commentary.
2) If unsure, pick conservative cute values; do not expand the schema.
3) Max response: 80 lines.
```

---

## Prompt A — mood table (primary)

Feed this into Gemini or ChatGPT when you want a full palette to implement.

```text
You are a product designer for a phone-mounted robot face.

CONTEXT
- Full-screen pair of cartoon eyes on a cream face.
- Drawn procedurally (ellipses + brows + cheeks), NOT sprite sheets / Lottie / video.
- Inputs at runtime: lookX, lookY in [-1,1], blink in [0,1], emotion enum.
- Emotions (exact names): neutral, happy, curious, listening, thinking, confused, sad, excited.
- Goal: cuter, more polished, still readable from 2 meters. Soft, friendly, slightly Japanese kawaii + clean consumer hardware (not anime mega-eyes, not uncanny).

HARD LIMITS
- Keep the same geometry model: two eyes, brows, optional cheeks, one iris color, one face fill.
- No mouth, no nose, no text, no particles, no shadows beyond flat cheek tint.
- No new emotion names.
- All scales are multipliers around 1.0 unless noted.
- Colors as #RRGGBB only.

OUTPUT FORMAT — reply with ONLY this JSON, no prose:

{
  "style_notes": "max 40 words",
  "idle": {
    "blink_interval_ms": [min, max],
    "blink_close_ms": number,
    "blink_open_ms": number,
    "pupil_travel": { "x": 0.0-0.35, "y": 0.0-0.30 }
  },
  "moods": {
    "neutral": {
      "face": "#RRGGBB",
      "iris": "#RRGGBB",
      "lidOpen": 0.6-1.0,
      "irisScale": 0.8-1.3,
      "pupilScale": 0.8-1.3,
      "heightScale": 0.85-1.15,
      "browLift": -0.15-0.20,
      "browTilt": -1.0-1.0,
      "cheekAlpha": 0-255
    }
  },
  "micro_motion": {
    "thinking_wobble": "one short sentence + suggested Hz + amplitude",
    "listening_pulse": "one short sentence or null",
    "search_look": "how SEARCH should move eyes, one sentence"
  }
}

Include the same mood object keys for every emotion:
happy, curious, listening, thinking, confused, sad, excited (and neutral).
If a value is unchanged from defaults, still include it. Do not invent fields.

RULES
1) Output valid JSON only. No markdown fences. No commentary.
2) If unsure, pick conservative cute values; do not expand the schema.
3) Max response: 80 lines.
```

---

## Prompt B — animation timing only

```text
Design blink + emotion transition timing for procedural robot eyes.

Constraints:
- 30 fps Canvas/SwiftUI; no keyframe files.
- Emotions: neutral, happy, curious, listening, thinking, confused, sad, excited.
- Transitions must be lerpable in 150–350 ms.
- Cute, not twitchy; demo audience watches from across a table.

OUTPUT ONLY this JSON:

{
  "blink": { "interval_ms": [min,max], "close_ms": n, "open_ms": n, "double_blink_chance": 0-1 },
  "emotion_transition_ms": n,
  "per_emotion": {
    "happy": { "extra": "max 12 words" },
    "curious": { "extra": "max 12 words" },
    "listening": { "extra": "max 12 words" },
    "thinking": { "extra": "max 12 words" },
    "confused": { "extra": "max 12 words" },
    "sad": { "extra": "max 12 words" },
    "excited": { "extra": "max 12 words" },
    "neutral": { "extra": "max 12 words" }
  }
}

RULES
1) Output valid JSON only. No markdown fences. No commentary.
2) If unsure, pick conservative cute values; do not expand the schema.
3) Max response: 80 lines.
```

---

## Prompt C — visual reference sheet (image models)

Keep image generation separate from the JSON mood table.

```text
Generate ONE reference sheet, flat vector style, cream background.
Layout: 2x4 grid, labeled exactly:
neutral | happy | curious | listening
thinking | confused | sad | excited
Same eye shape family in every cell. Soft green iris. No mouth. No hands. No logo.
Phone-screen aspect. Clean consumer robot face, cute but professional.
No text except the 8 labels.
```

Then run **Prompt A** with: *Match the attached sheet; output only the JSON mood table.*

---

## Suggested workflow

1. Run Prompt C (optional) for a shared look.
2. Run Prompt A → save JSON (e.g. `docs/eyes-mood.json` when you settle on one).
3. Run Prompt B if blink / transition feel wrong.
4. Port `moods` into Android + iOS; keep emotion names identical.
5. Sanity-check on device: greet (happy), “what do you see” while lost (confused), unplug USB (sad), bump if wired (excited), lost-person search (curious).
