#!/usr/bin/env python3
"""Assemble the documented browser screenshots into the edited README walkthrough."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", default="docs/recordings/commerce-demo/evidence.json")
    parser.add_argument("--output", default="frontend/public/demo/chatty-commerce-demo.mp4")
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    args = parser.parse_args()
    if not args.ffmpeg:
        parser.error("Supply --ffmpeg with the path to an installed FFmpeg executable.")
    evidence_path = (ROOT / args.evidence).resolve()
    evidence = json.loads(evidence_path.read_text())
    frames = [frame for frame in evidence["frames"] if not frame.get("excluded")]
    if not frames:
        parser.error("The evidence must contain captured browser frames.")
    output = (ROOT / args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="chatty-video-") as directory:
        work = Path(directory)
        lines = ["ffconcat version 1.0"]
        for index, frame in enumerate(frames):
            # Safe relative names in the concat file, independent of checkout path quoting.
            target = work / f"frame-{index:03}.jpg"
            shutil.copyfile(evidence_path.parent / frame["file"], target)
            lines.extend([f"file '{target.name}'", f"duration {float(frame['duration'])}"])
        lines.append(f"file 'frame-{len(frames) - 1:03}.jpg'")
        manifest = work / "frames.txt"
        manifest.write_text("\n".join(lines) + "\n")
        subprocess.run(
            [
                args.ffmpeg,
                "-y",
                "-hide_banner",
                "-loglevel",
                "warning",
                "-f",
                "concat",
                "-safe",
                "1",
                "-i",
                str(manifest),
                "-vf",
                "scale=1600:1000:force_original_aspect_ratio=decrease,"
                "pad=1600:1000:(ow-iw)/2:(oh-ih)/2:color=white,setsar=1,fps=25",
                "-c:v",
                "libx264",
                "-preset",
                "medium",
                "-crf",
                "18",
                "-pix_fmt",
                "yuv420p",
                "-movflags",
                "+faststart",
                "-an",
                str(output),
            ],
            check=True,
        )
    print(f"Saved {output} ({sum(f['duration'] for f in frames):.0f} seconds)")


if __name__ == "__main__":
    main()
