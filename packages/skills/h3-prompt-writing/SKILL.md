---
name: h3-prompt-writing
description: Write MiniMax H3 video generation prompts for T2VA, I2VA, FL2VA, L2VA, and Ref2VA. Use when rewriting multimodal requests into H3 prompt structures, composing integrated_multimodal_description, overall_soundscape, and non_diegetic_music, aligning keyframes, or defining reference labels for images, videos, and audio.
---

# H3 Prompt Writing

## Toonflow `h3Local3060` 5-Model Mapping & Asset Routing

When working in Toonflow with local ComfyUI `h3Local3060`, map canvas inputs to the exact model and prompt reference:

| Toonflow Model ID | H3 Mode | Canvas Mode | Allowed Visual Inputs | Prompt Guide |
|---|---|---|---|---|
| `h3T2v` | T2VA | `text` | None (text only) | `references/base-en.txt` |
| `h3I2v` | I2VA | `singleImage` | **Single-frame storyboard shot or previous clip tail frame ONLY (0.00s anchor)** | `references/base-en.txt` |
| `h3Fl2v` | FL2VA | `startEndRequired` | **Single-frame first frame + single-frame last frame ONLY** | `references/base-en.txt` |
| `h3L2v` | L2VA | `startFrameOptional` | **Single-frame last frame ONLY (first frame empty)** | `references/base-en.txt` |
| `h3R2v` | Ref2VA | `["imageReference:9", "videoReference:3", "audioReference:3"]` | **Character turnaround sheets (三视图/四视图), scene sheets, prop sheets, reference videos/audio** | `references/ref-en.txt` |

**Hard Routing Rules (NEVER violate):**
- **Character turnaround sheets / multi-view asset boards / 2×2 prop sheets → MUST use `h3R2v` + `references/ref-en.txt`** (complete 6-section format: `subject_definitions`, `summary`, `retention_analysis`, `detailed_description`, `overall_soundscape`, `non_diegetic_music`).
- **Single-frame storyboard shot or extracted tail frame → use `h3I2v` (or `h3Fl2v` / `h3L2v`) + `references/base-en.txt`** (3-section format + frame alignment line).
- **NEVER connect a multi-view character turnaround sheet or asset reference board to `h3I2v`, `h3Fl2v`, or `h3L2v`** — ComfyUI locks that image directly to `first_frame` (0.00s) / `last_frame`, causing the turnaround sheet itself to appear in the rendered video.

## Workflow

1. Identify the input mode: T2VA, I2VA, FL2VA, L2VA, or full-reference Ref2VA.
2. For base text/keyframe modes, read `references/base-en.txt` and follow its final prompt structure.
3. For full-reference mode, read `references/ref-en.txt` and follow its six-section rewrite format.
4. Preserve the exact field names, section order, labels, and timing notation from the selected guide.

## Base Modes

- T2VA: build the full audiovisual timeline from text.
- I2VA: start from the first frame and develop forward from it.
- FL2VA: describe the continuous path between the first and last frames.
- L2VA: infer a plausible opening and converge to the supplied last frame.

Use `integrated_multimodal_description`, `overall_soundscape`, and `non_diegetic_music` in the order shown in `references/base-en.txt`.

## Full-Reference Mode

Ref2VA rewrites use `subject_definitions`, `summary`, `retention_analysis`, `detailed_description`, `overall_soundscape`, and `non_diegetic_music` in that order. Reference labels stay consistent across all sections.

Read `references/ref-en.txt` for label rules, retention analysis, and complete examples.

## Output Rules

- Write rewrite sections in English; preserve dialogue, lyrics, and visible scene text in their original language.
- Describe each shot by composition, subjects, environment, actions, camera, sound, and the exact point where referenced content appears.
- Avoid plot summaries, unresolved reference labels, and timing that does not match the requested duration.
