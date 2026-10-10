"""Generate owned, deterministic test media; never read or edit personal files.
Requires requirements-safety.txt and an FFmpeg executable with x264/x265/AV1 encoders.
Encoder versions may change the output bytes: the generated manifest travels with the fixtures.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from PIL import Image, ImageCms, ImageDraw, TiffImagePlugin
from pillow_heif import register_heif_opener

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
args = parser.parse_args()
if not args.ffmpeg:
    raise SystemExit("FFmpeg not found; pass --ffmpeg with its executable path")
target = root / "app/src/androidTest/assets/safety-corpus"
target.mkdir(parents=True, exist_ok=True)
register_heif_opener()
image = Image.new("RGB", (128, 96))
draw = ImageDraw.Draw(image)
for box, color in [((0,0,63,47), "red"), ((64,0,127,47), "lime"), ((0,48,63,95), "blue"), ((64,48,127,95), "yellow")]:
    draw.rectangle(box, fill=color)
icc = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
exif = Image.Exif()
exif[274] = 1
exif[306] = "2006:01:02 03:04:05"
exif[34665] = {36867: "2006:01:02 03:04:05", 36868: "2006:01:02 03:04:05"}
R = TiffImagePlugin.IFDRational
exif[34853] = {1:"N", 2:(R(48),R(12),R(30)), 3:"E", 4:(R(16),R(22),R(15))}
rows = []
def add(name, mime, kind="image", **expected):
    data = (target/name).read_bytes()
    rows.append(dict(name=name, mime=mime, kind=kind, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), **expected))
for orientation in [1,6,8]:
    exif[274] = orientation
    name = f"orientation-{orientation}.jpg"
    image.save(target/name, quality=96, exif=exif, icc_profile=icc)
    add(name, "image/jpeg", width=128 if orientation==1 else 96, height=96 if orientation==1 else 128, orientation=orientation)
alpha = image.convert("RGBA"); alpha.putpixel((0,0),(0,0,0,0))
alpha.save(target/"alpha.png", icc_profile=icc); add("alpha.png", "image/png", width=128, height=96, alpha=True)
for name, kwargs in [("lossless.webp", dict(lossless=True)), ("lossy.webp", dict(quality=70))]:
    alpha.save(target/name, **kwargs); add(name,"image/webp", width=128,height=96)
second = image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
for name, kwargs in [("animated.gif", {}), ("animated.webp", dict(lossless=True))]:
    image.save(target/name, save_all=True, append_images=[second], duration=[100,100], loop=0, **kwargs)
    add(name, "image/gif" if name.endswith("gif") else "image/webp", width=128,height=96, animated=True)
for name, mime in [("modern.avif","image/avif"), ("modern.heic","image/heic")]:
    image.save(target/name, quality=95, icc_profile=icc)
    add(name,mime,width=128,height=96)
# TIFF is intentionally an archive-only unsupported-view fixture, not a claim of Android TIFF decoding.
image.save(target/"archive-only.tiff", compression="tiff_lzw", icc_profile=icc)
add("archive-only.tiff","image/tiff",kind="archive-only")
for name, codec, extra in [("h264.mp4","libx264",["-preset","ultrafast"]), ("hevc.mp4","libx265",["-preset","ultrafast","-x265-params","pools=none:frame-threads=1:log-level=error","-tag:v","hvc1"]), ("av1.mp4","libaom-av1",["-cpu-used","8","-row-mt","0"])]:
    command = [args.ffmpeg,"-hide_banner","-loglevel","error","-y","-f","lavfi","-i","testsrc2=size=160x96:rate=12","-t","1","-an","-c:v",codec,"-threads","1","-pix_fmt","yuv420p",*extra,"-movflags","+faststart",str(target/name)]
    subprocess.run(command,check=True,timeout=90)
    add(name,"video/mp4",kind="video",width=160,height=96)
manifest = dict(generator="Ciel Vitrine owned synthetic corpus", fixtures=rows)
(target/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
(root/"docs/fixtures/safety-corpus-manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(f"Prepared {len(rows)} owned fixtures, {sum(r['bytes'] for r in rows)} bytes")
