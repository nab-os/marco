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
- **Calibrate this camera** on the other. It streams the widest back lens without stabilisation (the ultrawide on a Pixel, from inside the logical camera) with OIS, digital stabilisation, distortion correction and sharpening off and the focus fixed. Hold it 10–20 cm from the board and move slowly through every tilt and every part of the frame. A view is only kept if the board sits differently from every view kept so far. **Calibrate** compares the result with Android's factory calibration and saves it to `Android/data/dev.nabos.marco/files/vision/`.
- **Record camera + motion** writes raw frames and gyro/accelerometer readings to `files/recordings/`. The **● Rec** button on the Find screen records the Bluetooth signal along with them. The file formats are described in [`Recorder.kt`](../app/src/main/java/dev/nabos/marco/vision/Recorder.kt).

`adb shell am start -n dev.nabos.marco/.lab.LabActivity --es mode board` (or `calibrate`, `record`) opens a mode directly, and `adb pull /sdcard/Android/data/dev.nabos.marco/files/` fetches the results.

## Tests

`cargo test --release` renders the board, reads it back, turns away a repeated view, and calibrates a synthetic phone-screen board seen through a fisheye ultrawide.
