#!/usr/bin/env bash
# Makes the Plan 1 end-to-end test media in build/test-media/ with ffmpeg's lavfi sources.
#
#   square_red_at_5s.mp4  10 s, a white square on black that turns red at exactly 5 s (no clock on screen)
#   long_60s.mp4          60 s of SMPTE colour bars (more than 16 frames at 2 fps: frames are spread out)
#   tiny_0_3s.mp4         0.3 s test pattern (a single sampled frame)
#   empty.mp4             0 bytes (must fail with a decode reason)
#   still_text.jpg        "HELLO S26" in black on white
#   still_red_blue.png    a red and a blue square on white
#   still_truncated.jpg   the first 2 KB of still_text.jpg (must fail with a decode reason)
#
# Afterwards it pulls one frame from each clip into build/test-media-check/ (outside the pushed
# folder): look at them before trusting the clips (Instructions.md).
#
# Usage: scripts/make-test-media.sh   (from the repo root; FONT=/path/to/font.ttf to override the font)
set -euo pipefail

cd "$(dirname "$0")/.."
out=build/test-media
check=build/test-media-check
rm -rf "$out" "$check"
mkdir -p "$out" "$check"

# drawtext needs a font file; ffmpeg's filter syntax wants the drive colon escaped on Windows.
if [[ -z "${FONT:-}" ]]; then
  for f in /c/Windows/Fonts/arial.ttf /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf /System/Library/Fonts/Supplemental/Arial.ttf; do
    [[ -f "$f" ]] && FONT="$f" && break
  done
fi
[[ -n "${FONT:-}" ]] || { echo "No font found; set FONT=/path/to/font.ttf" >&2; exit 1; }
case "$FONT" in
  /[a-zA-Z]/*) font_arg="${FONT:1:1}\\:${FONT:2}" ;; # /c/Windows/... -> c\:/Windows/...
  *) font_arg="$FONT" ;;
esac

ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
x264=(-c:v libx264 -pix_fmt yuv420p -movflags +faststart)

# White square until 5 s, red from 5 s on; same position and size so only the colour changes.
ff -f lavfi -i "color=c=black:s=640x480:r=30:d=10" \
  -vf "drawbox=x=220:y=140:w=200:h=200:color=white:t=fill:enable='lt(t,5)',drawbox=x=220:y=140:w=200:h=200:color=red:t=fill:enable='gte(t,5)'" \
  "${x264[@]}" "$out/square_red_at_5s.mp4"

ff -f lavfi -i "smptebars=s=1280x720:r=30:d=60" "${x264[@]}" "$out/long_60s.mp4"

ff -f lavfi -i "testsrc2=s=640x480:r=30:d=0.3" "${x264[@]}" "$out/tiny_0_3s.mp4"

: > "$out/empty.mp4"

ff -f lavfi -i "color=c=white:s=1024x768:d=1" -frames:v 1 \
  -vf "drawtext=fontfile='$font_arg':text='HELLO S26':fontsize=120:fontcolor=black:x=(w-text_w)/2:y=(h-text_h)/2" \
  -q:v 2 "$out/still_text.jpg"

ff -f lavfi -i "color=c=white:s=1024x768:d=1" -frames:v 1 \
  -vf "drawbox=x=152:y=234:w=300:h=300:color=red:t=fill,drawbox=x=572:y=234:w=300:h=300:color=blue:t=fill" \
  "$out/still_red_blue.png"

head -c 2048 "$out/still_text.jpg" > "$out/still_truncated.jpg"

# One frame per clip to eyeball: the square before and after 5 s, mid-way through the others.
ff -ss 2 -i "$out/square_red_at_5s.mp4" -frames:v 1 "$check/square_at_2s.png"
ff -ss 7 -i "$out/square_red_at_5s.mp4" -frames:v 1 "$check/square_at_7s.png"
ff -ss 30 -i "$out/long_60s.mp4" -frames:v 1 "$check/long_at_30s.png"
ff -i "$out/tiny_0_3s.mp4" -frames:v 1 "$check/tiny_first.png"

ls -l "$out"
echo "Check frames written to $check/; look at them before pushing."
