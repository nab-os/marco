#!/usr/bin/env python3
"""Turns a Marco recording into a Basalt dataset, to try visual-inertial tracking
on the desktop before running it on the phone.

    marco_to_basalt.py RECORDING_DIR OUT_DIR [--calibration FILE]

RECORDING_DIR is a folder the Lab's recorder wrote (adb pull
/sdcard/Android/data/dev.nabos.marco/files/recordings/<date>). OUT_DIR gets
the EuRoC layout Basalt reads, and calib.json:

    OUT_DIR/mav0/cam0/data.csv, OUT_DIR/mav0/cam0/data/<t_ns>.png
    OUT_DIR/mav0/imu0/data.csv
    OUT_DIR/calib.json

Timing: a frame is stamped at the middle of its middle row's exposure, then
moved onto the gyroscope's clock by the calibration's measured offset, so
calib.json's cam_time_offset_ns is 0. IMU rows are the gyroscope's readings
with the accelerometer interpolated to their times.

Only the standard library: frames go through a small PNG writer.
"""

import argparse
import csv
import json
import math
import struct
import sys
import zlib
from pathlib import Path


def png(path: Path, width: int, height: int, grey: bytes) -> None:
    """8-bit greyscale PNG, no filtering."""
    rows = b"".join(b"\x00" + grey[y * width:(y + 1) * width] for y in range(height))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows, 6))
        + chunk(b"IEND", b"")
    )


def frame_times(recording: Path) -> list[tuple[int, int]]:
    """(index, sensor timestamp ns) per frame in frames.y8."""
    with open(recording / "frames.csv") as f:
        return [(int(r["index"]), int(r["timestamp_ns"])) for r in csv.DictReader(f)]


def capture_info(recording: Path) -> dict[int, tuple[int, int]]:
    """Sensor timestamp → (exposure ns, rolling shutter skew ns)."""
    info = {}
    path = recording / "capture.csv"
    if path.exists():
        with open(path) as f:
            for r in csv.DictReader(f):
                info[int(r["timestamp_ns"])] = (int(r["exposure_ns"] or 0), int(r["rolling_shutter_skew_ns"] or 0))
    return info


def imu_rows(recording: Path) -> list[tuple[int, list[float], list[float]]]:
    """(t ns, gyro rad/s, accel m/s²) at each gyro reading inside the accelerometer's span."""
    gyro, accel = [], []
    with open(recording / "imu.csv") as f:
        for r in csv.DictReader(f):
            sample = (int(r["timestamp_ns"]), [float(r["x"]), float(r["y"]), float(r["z"])])
            (gyro if r["sensor"] == "g" else accel).append(sample)
    gyro.sort()
    accel.sort()
    rows, j = [], 0
    for t, w in gyro:
        while j + 1 < len(accel) and accel[j + 1][0] < t:
            j += 1
        if j + 1 >= len(accel) or accel[j][0] > t:
            continue
        (t0, a0), (t1, a1) = accel[j], accel[j + 1]
        f = (t - t0) / (t1 - t0) if t1 > t0 else 0.0
        rows.append((t, w, [a0[k] + f * (a1[k] - a0[k]) for k in range(3)]))
    return rows


def basalt_calibration(calibration: dict, width: int, height: int) -> dict:
    camera = calibration["camera"]
    if (camera["width"], camera["height"]) != (width, height):
        sys.exit(f"calibration is for {camera['width']}x{camera['height']}, frames are {width}x{height}")
    base = {k: camera[k] for k in ("fx", "fy", "cx", "cy")}
    d = camera["distortion"]
    if camera["model"] == "fisheye":
        intrinsics = {"camera_type": "kb4", "intrinsics": {**base, "k1": d[0], "k2": d[1], "k3": d[2], "k4": d[3]}}
    else:
        # Vernier's pinhole is OpenCV's k1 k2 p1 p2 k3; radtan8 adds k4..k6 and the
        # largest normalised radius it is valid to: the frame's corners, with room.
        corners = [((u - camera["cx"]) / camera["fx"], (v - camera["cy"]) / camera["fy"])
                   for u in (0, width) for v in (0, height)]
        rpmax = 1.2 * max(math.hypot(x, y) for x, y in corners)
        intrinsics = {"camera_type": "pinhole-radtan8", "intrinsics": {
            **base, "k1": d[0], "k2": d[1], "p1": d[2], "p2": d[3], "k3": d[4],
            "k4": 0.0, "k5": 0.0, "k6": 0.0, "rpmax": rpmax}}

    imu = calibration.get("imu")
    if imu is None:
        sys.exit("the calibration has no gyroscope alignment (imu): calibrate again with a gyroscope")
    # Marco gives the rotation taking gyro axes to camera axes; Basalt wants the
    # camera's pose in the IMU frame (camera points to IMU points): its inverse.
    x, y, z, w = imu["imu_to_camera_xyzw"]
    identity = {"px": 0.0, "py": 0.0, "pz": 0.0, "qx": 0.0, "qy": 0.0, "qz": 0.0, "qw": 1.0}
    return {"value0": {
        # The lever arm between gyroscope and camera (a cm or two) isn't measured.
        "T_imu_cam": [{"px": 0.0, "py": 0.0, "pz": 0.0, "qx": -x, "qy": -y, "qz": -z, "qw": w}],
        "intrinsics": [intrinsics],
        "resolution": [[width, height]],
        "calib_accel_bias": [0.0] * 9,
        "calib_gyro_bias": [0.0] * 12,
        "imu_update_rate": 200.0,
        # A phone's MEMS IMU, noisier than EuRoC's: starting values, to tune.
        "accel_noise_std": [0.02, 0.02, 0.02],
        "gyro_noise_std": [0.002, 0.002, 0.002],
        "accel_bias_std": [0.001, 0.001, 0.001],
        "gyro_bias_std": [0.0001, 0.0001, 0.0001],
        "T_mocap_imu": identity,
        "T_imu_marker": identity,
        "mocap_time_offset_ns": 0,
        "mocap_to_imu_offset_ns": 0,
        # Applied to the frames' timestamps already.
        "cam_time_offset_ns": 0,
        "vignette": [],
    }}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("recording", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--calibration", type=Path, help="default: RECORDING/calibration.json")
    args = parser.parse_args()

    meta = json.loads((args.recording / "meta.json").read_text())
    width, height = meta["camera"]["width"], meta["camera"]["height"]
    calibration_path = args.calibration or args.recording / "calibration.json"
    if not calibration_path.exists():
        sys.exit(f"no calibration at {calibration_path}: pass --calibration")
    calibration = json.loads(calibration_path.read_text())
    offset_ns = round(calibration.get("imu", {}).get("time_offset_ms", 0.0) * 1e6)

    images = args.out / "mav0" / "cam0" / "data"
    images.mkdir(parents=True, exist_ok=True)
    (args.out / "mav0" / "imu0").mkdir(parents=True, exist_ok=True)
    (args.out / "calib.json").write_text(json.dumps(basalt_calibration(calibration, width, height), indent=2))

    info = capture_info(args.recording)
    size = width * height
    written = 0
    with open(args.recording / "frames.y8", "rb") as raw, open(args.out / "mav0" / "cam0" / "data.csv", "w") as out:
        out.write("#timestamp [ns],filename\n")
        last = None
        for index, sensor_ns in frame_times(args.recording):
            raw.seek(index * size)
            grey = raw.read(size)
            if len(grey) < size:
                break
            exposure, skew = info.get(sensor_ns, (0, 0))
            t = sensor_ns + exposure // 2 + skew // 2 + offset_ns
            if last is not None and t <= last:
                continue
            last = t
            png(images / f"{t}.png", width, height, grey)
            out.write(f"{t},{t}.png\n")
            written += 1

    rows = imu_rows(args.recording)
    with open(args.out / "mav0" / "imu0" / "data.csv", "w") as out:
        out.write("#timestamp [ns],w_RS_S_x [rad s^-1],w_RS_S_y [rad s^-1],w_RS_S_z [rad s^-1],"
                  "a_RS_S_x [m s^-2],a_RS_S_y [m s^-2],a_RS_S_z [m s^-2]\n")
        for t, w, a in rows:
            out.write(f"{t},{w[0]:.9g},{w[1]:.9g},{w[2]:.9g},{a[0]:.9g},{a[1]:.9g},{a[2]:.9g}\n")

    missing = sum(1 for _, t in frame_times(args.recording) if t not in info)
    print(f"{written} frames {width}x{height}, {len(rows)} IMU rows, camera moved {offset_ns / 1e6:+.2f} ms onto the IMU clock"
          + (f"; {missing} frames had no exposure info" if missing else ""))


if __name__ == "__main__":
    main()
