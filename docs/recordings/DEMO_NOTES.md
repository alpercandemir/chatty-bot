# Commerce demo — 2 November 2026

[Watch the video](https://chatty.138.2.168.194.sslip.io/demo/) · [MP4 source](../../frontend/public/demo/chatty-commerce-demo.mp4) · [Capture evidence](commerce-demo/evidence.json)

**Duration:** 1:39. **Format:** H.264 MP4, 1600 × 1000, 25 fps, no audio. The video is an edited walkthrough assembled from genuine browser screenshots: waiting intervals are removed and reading pauses are added. It is not a continuous real-time screen recording. Application text and outcomes are not reconstructed.

**Mode:** All five included customer turns use Live AI with `gemini-3.5-flash-lite`. The exact action previews, privacy denial, and saved outcomes are rendered by the backend from tool results. No scripted rehearsal footage appears in the final video.

The opening/security interactions were captured locally using the new interface; the confirmed address change, Inspector, and final customer overviews were captured on the deployed site after commit `00082d3` went live. These use separate presenter workspaces and the same synthetic fixtures.

| Start | Scene |
| --- | --- |
| 0:00 | Four demo customers |
| 0:05 | Tracking without a delivery guarantee |
| 0:19 | Customer privacy despite claimed authority |
| 0:27 | Confirmation cannot be bypassed |
| 0:37 | Declining leaves the order unchanged |
| 0:43 | Explicit provider failure; saved state preserved |
| 0:49 | Exact address preview on the deployed site |
| 1:01 | Confirmed address change |
| 1:08 | Inspector: confirmation and persisted outcome |
| 1:15 | Business state: saved address and version |
| 1:22 | Mert: cancellation scenario |
| 1:27 | Deniz: returns scenario |
| 1:32 | Selin: delayed and split shipments |

## What the recording demonstrates

- Tracking uses carrier facts and treats the delivery date as an estimate.
- Claiming to be a manager does not reveal another customer's order, address, or items.
- A request to skip confirmation still produces an exact proposal. **Keep unchanged** declines it.
- A provider connection failure is explicit and preserves saved business state.
- Confirming the address proposal saves the exact address. The Inspector shows the confirmation, persisted outcome, and order version.
- The presenter can switch among four brief customer contexts. Mert, Deniz, and Selin are shown as scenario overviews; their workflows are not presented as completed live transactions in this video.

The confirmed production outcome is `NS-1001`, still `PROCESSING`, version 2, with the Ornek Bulvar address in Atasehir. No cancellation or refund is claimed as completed in the selected scenes. All customer details are synthetic; the presenter customer selector is a demo control, not production customer authentication.

Gemini transport/timeouts interrupted several attempts. One failure is retained; repeated failures and the discarded scripted draft are listed as omitted attempts in the evidence. Model/provider configuration was not changed. The broader [security evaluation](../SECURITY_EVALUATION.md) and automated tests provide checks beyond the selected video scenes.

## Rebuild this edit

The unmodified captured screens and their selected durations live in [commerce-demo](commerce-demo/). With an installed FFmpeg executable:

```sh
python3 scripts/build-demo-video.py --ffmpeg /absolute/path/to/ffmpeg
```

The script reads the evidence manifest and writes `frontend/public/demo/chatty-commerce-demo.mp4`. Vite copies the player and video into the frontend bundle; the README links to the deployed player. Python compilation, a complete FFmpeg decode, frontend compilation, and browser playback are used to verify the artifact.

The [October recording and its notes](LEGACY_DEMO_NOTES.md) remain historical evidence of the previous design. Its old recorder targets the October UI.
