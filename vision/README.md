# marco-vision

Marco's bridge to [Vernier](https://github.com/nab-os/vernier-rs): one phone shows Vernier's coded checkerboard (code size 5, two bits per cell) full-screen, pixel for pixel, and the other calibrates its camera from it. It is the first step towards tracking the finder phone with its camera for the signal map.

It is optional. vernier-rs is private until its relicensing, so it builds only when `local.properties` has

```properties
marco.vision=true
```

and then needs Rust with the `aarch64-linux-android` target, an NDK under the SDK, and SSH access to vernier-rs. Without it the app builds as before, and the camera tools say Vernier is missing.

## Trying it

Debug builds add a **Marco Lab** launcher entry:

- **Show calibration board** on one phone, at half brightness, with the system bars hidden.
- **Calibrate this camera** on the other: one pass of 20–40 s calibrates the lens and aligns it with the gyroscope. Keep the board in view and keep moving, tilting and turning the phone every way and bringing the board into the shaded cells of the grid over the preview. It finishes on its own once it has 12 distinct views, 80% of the grid and 30 poses (**Finish now** is there from 6 views), in about 5 s on a Pixel 7a.
  - The camera is the widest back lens without stabilisation (the ultrawide on a Pixel, from inside the logical camera), with OIS, digital stabilisation, distortion correction and sharpening off and the focus fixed. Frames are captured at 1280×960 and averaged over 2×2 blocks before measuring (about 0.3 s a frame on a Pixel 7a instead of 1 s; it also smooths a monitor's subpixel moiré), so calibrations are for 640×480 (`binning: 2` in the file).
  - Every frame measured is stamped at the middle of the exposure of the board's average row, for the rolling shutter, and becomes a pose. A frame is also kept for the lens if the board sits differently from every view kept so far, thinned to 1500 points. The grid spans the frame less an eighth on each side, because Vernier's measuring window needs a few board periods of room and no point lands nearer the edge.
  - At the end the lens is fitted with both models, pinhole and fisheye, keeping the better; every pose is solved with it; and pairs of poses up to 6 s apart are matched with the gyroscope's turn over the same interval. That gives the rotation from gyroscope to camera axes, the delay between their clocks and the gyro's bias, each with a bootstrap spread ([`src/imu.rs`](src/imu.rs)). The result is compared with Android's factory calibration and pose, and saved to `Android/data/dev.nabos.marco/files/vision/` with the alignment under `imu`.
- **Record camera + motion** writes raw frames and gyro/accelerometer readings to `files/recordings/`. The **● Rec** button on the Find screen records the Bluetooth signal along with them. The file formats are described in [`Recorder.kt`](../app/src/main/java/dev/nabos/marco/vision/Recorder.kt).

### On a computer screen

A monitor makes a bigger board than a phone: it can fill a wide lens's whole frame, corners included, from 30–50 cm, closer to where a fixed-focus lens is sharp. The board's scale doesn't matter, but it must be shown one board pixel per screen pixel, which `marco-board` does at any window size:

```bash
cargo run --release -p marco-board -- --square 32   # fullscreen; --windowed for a window
```

It renders the board again whenever the window changes size, in the background, showing the previous one cropped (never scaled) meanwhile. `+` / `-` change the square size, `F` or F11 toggles fullscreen, `Q` or Esc quits. Each render prints the board's size in squares and the display's scale factor: with fractional scaling, check that the compositor isn't resampling the window. Pick squares that land around 10 pixels or more in the camera's image: about 32 px on a ~110 dpi monitor for a phone ultrawide at 40 cm.

### From adb

`adb shell am start -n dev.nabos.marco/.lab.LabActivity --es mode board` (or `calibrate`, `record`) opens a mode directly, and `adb pull /sdcard/Android/data/dev.nabos.marco/files/` fetches the results.

## Tests

`cargo test --release` renders the board, reads it back, turns away a repeated view, and calibrates a synthetic phone-screen board seen through a fisheye ultrawide. It also aligns a simulated gyroscope, first from known poses and then end to end from rendered frames of a wobbling camera, recovering the rotation, clock offset and bias.

Calibration fits both lens models, pinhole (`k1 k2 p1 p2 k3`) and fisheye (Kannala–Brandt `k1..k4`), and keeps the one with the lower rms: a phone's ultrawide is often closer to rectilinear than its field of view suggests.
