//! Marco's bridge to Vernier.
//!
//! One phone shows Vernier's coded checkerboard full-screen ([`render_board`]);
//! the other films it and turns the frames into a camera calibration
//! ([`Session`]). The JNI entry points at the bottom are what the app calls.

pub mod imu;

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JFloatArray, JLongArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jdouble, jint, jlong, jstring};
use nalgebra::{Matrix3, Vector3};
use serde::Serialize;
use vernier_camera::geometry::homography;
use vernier_camera::{
    Calibration, CalibrationError, Camera, Model, Response, Target, View, calibrate, measure_view,
    measure_view_after, solve_pnp,
};
use vernier_patterns::PatternPose;
use vernier_patterns::checkerboard::CodePacking;

use crate::imu::{CameraImu, CameraOrientation, GyroSample, ImuError};

/// LFSR order of the board's code: small, so that the few dozen squares a
/// phone screen holds are enough to read it from any part of the board.
pub const CODE_SIZE: u32 = 5;

/// A view with fewer points adds more noise than constraint. Kept low: views
/// near the frame's edges, which pin down distortion, only catch a corner of
/// a phone screen.
pub const MIN_POINTS: usize = 40;

/// The board, measured in squares: calibration doesn't need its true size.
pub fn target() -> Target {
    Target::new(1.0, CODE_SIZE).with_packing(CodePacking::TwoBits)
}

/// The board as a `width × height` 8-bit sRGB grey image with squares of
/// `square_px` pixels, its origin at the top-left corner. Screens decode sRGB,
/// so the anti-aliased edges come out at the light Vernier rendered.
pub fn render_board(width: usize, height: usize, square_px: f64) -> Vec<u8> {
    let in_pixels = Target {
        square: square_px,
        ..target()
    };
    let mut board = in_pixels.checkerboard().expect("code size 5 is supported");
    if square_px.fract() == 0.0 {
        // Squares on whole pixels have no edge to smooth: one sample per pixel, crisp and fast.
        board = board.with_supersample(1);
    }
    board
        .render(width, height, &PatternPose::IDENTITY)
        .as_slice()
        .iter()
        .map(|&light| (srgb_encode(light.clamp(0.0, 1.0)) * 255.0).round() as u8)
        .collect()
}

/// IEC 61966-2-1, the inverse of [`Response::Srgb`].
fn srgb_encode(light: f32) -> f32 {
    if light <= 0.003_130_8 {
        12.92 * light
    } else {
        1.055 * light.powf(1.0 / 2.4) - 0.055
    }
}

/// Frames of the board coming in, the views worth keeping, and the
/// calibration made from them.
pub struct Session {
    target: Target,
    response: Response,
    /// Frames are averaged over `bin × bin` blocks before measuring.
    bin: usize,
    /// Last view whose code was read, to start the next frame's search from.
    previous: Option<View>,
    /// Last view good enough to keep, until the next frame.
    candidate: Option<View>,
    views: Vec<View>,
    /// Of each kept view, to turn away near-copies.
    footprints: Vec<Footprint>,
    /// The calibrated camera, once there is one: what [`Session::track`] solves poses with.
    camera: Option<Camera>,
    /// For the camera-to-gyroscope alignment.
    orientations: Vec<CameraOrientation>,
    gyro: Vec<GyroSample>,
}

impl Session {
    /// `response` is the transfer curve the camera encoded its frames with.
    /// Frames are averaged over `bin × bin` pixel blocks before measuring:
    /// measuring costs about 3 s for a 1280×960 frame on a phone, a quarter of
    /// that binned by 2, and averaging also tames a monitor's subpixel moiré.
    /// Views, calibrations and poses are then all in binned pixels.
    pub fn new(response: Response, bin: usize) -> Self {
        Self {
            target: target(),
            response,
            bin: bin.max(1),
            previous: None,
            candidate: None,
            views: Vec::new(),
            footprints: Vec::new(),
            camera: None,
            orientations: Vec::new(),
            gyro: Vec::new(),
        }
    }

    /// Measures one frame of 8-bit luma, `stride` bytes per row. Returns how
    /// many board points it found, or `None` if the code wasn't read.
    pub fn measure(&mut self, luma: &[u8], width: usize, height: usize, stride: usize) -> Option<usize> {
        self.candidate = None;
        let view = self.read(luma, width, height, stride)?;
        let points = view.points.len();
        if points >= MIN_POINTS {
            self.candidate = Some(view);
        }
        Some(points)
    }

    /// The frame's view, if its code was read.
    fn read(&mut self, luma: &[u8], width: usize, height: usize, stride: usize) -> Option<View> {
        if width == 0 || height == 0 || stride < width || luma.len() < stride * (height - 1) + width {
            return None;
        }
        let bin = self.bin;
        let (width, height) = (width / bin, height / bin);
        if width == 0 || height == 0 {
            return None;
        }
        // Averaged in light, not in encoded values.
        let light: Vec<f32> = (0..=255u8).map(|value| self.response.light(value as f32 / 255.0)).collect();
        let weight = 1.0 / (bin * bin) as f32;
        let mut frame = vec![0f32; width * height];
        for y in 0..height * bin {
            let row = &luma[y * stride..y * stride + width * bin];
            let out = &mut frame[(y / bin) * width..(y / bin + 1) * width];
            for (x, &value) in row.iter().enumerate() {
                out[x / bin] += light[value as usize] * weight;
            }
        }
        let measured = match &self.previous {
            Some(previous) => measure_view_after(&frame, width, height, &self.target, previous),
            None => measure_view(&frame, width, height, &self.target),
        };
        match measured {
            Ok(view) if view.is_absolute() => {
                self.previous = Some(view.clone());
                Some(view)
            }
            _ => {
                self.previous = None;
                None
            }
        }
    }

    /// Keeps the last measured frame for the calibration, if it was good
    /// enough and shows the board differently from every view kept so far.
    /// Returns whether it did.
    ///
    /// Near-copies would let a phone held still, or two phones lying on a
    /// desk, fill the calibration with one pose and make it look certain.
    pub fn keep(&mut self) -> bool {
        let Some(view) = self.candidate.take() else {
            return false;
        };
        let Some(footprint) = Footprint::of(&view) else {
            return false;
        };
        let width = view.width as f64;
        if self.footprints.iter().any(|kept| kept.close_to(&footprint, width)) {
            return false;
        }
        self.views.push(view);
        self.footprints.push(footprint);
        true
    }

    pub fn kept(&self) -> usize {
        self.views.len()
    }

    pub fn calibrate(&self, model: Model) -> Result<Calibration, CalibrationError> {
        calibrate(&self.views, model)
    }

    /// Calibrates with each lens model and keeps the one that fits best, as
    /// the camera [`Session::track`] uses. Returns it, with every model's rms.
    ///
    /// A wide lens can be anything from nearly rectilinear (a phone's
    /// ultrawide, corrected optically) to a true fisheye: the data decides.
    pub fn calibrate_best(&mut self) -> Result<(Calibration, Vec<(Model, f64)>), CalibrationError> {
        let mut fitted = Vec::new();
        let mut failure = CalibrationError::TooFewViews(self.views.len());
        for model in [Model::Pinhole, Model::Fisheye] {
            match calibrate(&self.views, model) {
                Ok(calibration) => fitted.push((model, calibration)),
                Err(error) => failure = error,
            }
        }
        let rms = fitted.iter().map(|(model, calibration)| (*model, calibration.rms)).collect();
        let (_, best) = fitted
            .into_iter()
            .min_by(|a, b| a.1.rms.total_cmp(&b.1.rms))
            .ok_or(failure)?;
        self.camera = Some(best.camera.clone());
        Ok((best, rms))
    }

    /// Uses a camera calibrated earlier for [`Session::track`].
    pub fn set_camera(&mut self, camera: Camera) {
        self.camera = Some(camera);
    }

    /// Measures a frame and, given a calibrated camera of the frame's size,
    /// records which way the camera faced the board, for the alignment.
    /// Returns how many board points it found, or `None` if no pose came out.
    ///
    /// `t_ns` is the frame's sensor timestamp, when its first row started
    /// exposing. Rows expose one after another, `rolling_shutter_skew_ns`
    /// from first to last, so the pose is stamped at the middle of the
    /// exposure of the board's average row.
    pub fn track(
        &mut self,
        luma: &[u8],
        width: usize,
        height: usize,
        stride: usize,
        t_ns: i64,
        exposure_ns: i64,
        rolling_shutter_skew_ns: i64,
    ) -> Option<usize> {
        let (width, height) = (width / self.bin, height / self.bin);
        let camera = self.camera.as_ref().and_then(|camera| scaled_to(camera, width, height))?;
        let view = self.read(luma, width * self.bin, height * self.bin, stride)?;
        if view.points.len() < MIN_POINTS {
            return None;
        }
        let fit = solve_pnp(&camera, &view).ok()?;
        if fit.rms > MAX_POSE_RMS {
            return None;
        }
        let mean_row = view.points.iter().map(|point| point.pixel[1]).sum::<f64>() / view.points.len() as f64;
        let row_delay = rolling_shutter_skew_ns as f64 * mean_row / height as f64;
        self.orientations.push(CameraOrientation {
            t_ns: t_ns + exposure_ns / 2 + row_delay as i64,
            camera_to_board: fit.pose.rotation.inverse(),
        });
        Some(view.points.len())
    }

    pub fn add_gyro(&mut self, samples: impl IntoIterator<Item = GyroSample>) {
        self.gyro.extend(samples);
    }

    /// Poses recorded by [`Session::track`] so far.
    pub fn poses(&self) -> usize {
        self.orientations.len()
    }

    /// Starts the alignment over: forgets poses and gyro readings.
    pub fn reset_alignment(&mut self) {
        self.orientations.clear();
        self.gyro.clear();
    }

    pub fn align(&self) -> Result<CameraImu, ImuError> {
        imu::align(&self.orientations, &self.gyro)
    }
}

/// `camera` for images a whole number of times smaller than it was calibrated
/// at, as a calibration made at full resolution seen through binning. Pixel
/// centres map as `x / f = (X + 0.5) / f - 0.5`; distortion works on
/// normalised coordinates and doesn't change.
pub fn scaled_to(camera: &Camera, width: usize, height: usize) -> Option<Camera> {
    if camera.width == width && camera.height == height {
        return Some(camera.clone());
    }
    if width == 0 || camera.width % width != 0 || camera.height != height * (camera.width / width) {
        return None;
    }
    let factor = (camera.width / width) as f64;
    Some(Camera {
        width,
        height,
        fx: camera.fx / factor,
        fy: camera.fy / factor,
        cx: (camera.cx + 0.5) / factor - 0.5,
        cy: (camera.cy + 0.5) / factor - 0.5,
        ..camera.clone()
    })
}

/// A pose fitted worse than this, pixels, is a misread rather than a measurement.
const MAX_POSE_RMS: f64 = 1.0;

/// How the board sits in a view: the image point at the middle of its points,
/// and where [`Footprint::STEP`] squares along each board axis land from
/// there. Together they say where the board is, how big, how turned and how
/// tilted.
#[derive(Clone, Copy, Debug)]
struct Footprint {
    centre: [f64; 2],
    along_x: [f64; 2],
    along_y: [f64; 2],
}

impl Footprint {
    const STEP: f64 = 3.0;
    /// Centre moved by less than this fraction of the image width...
    const SAME_PLACE: f64 = 0.05;
    /// ...and both steps changed by less than this fraction of their length.
    const SAME_SHAPE: f64 = 0.1;

    fn of(view: &View) -> Option<Self> {
        let board: Vec<[f64; 2]> = view.points.iter().map(|point| point.board).collect();
        let pixels: Vec<[f64; 2]> = view.points.iter().map(|point| point.pixel).collect();
        let to_pixel = homography(&board, &pixels)?;
        let to_board = to_pixel.try_inverse()?;
        let count = pixels.len() as f64;
        let centre = [
            pixels.iter().map(|p| p[0]).sum::<f64>() / count,
            pixels.iter().map(|p| p[1]).sum::<f64>() / count,
        ];
        let middle = apply(&to_board, centre)?;
        let step_x = apply(&to_pixel, [middle[0] + Self::STEP, middle[1]])?;
        let step_y = apply(&to_pixel, [middle[0], middle[1] + Self::STEP])?;
        Some(Self {
            centre,
            along_x: [step_x[0] - centre[0], step_x[1] - centre[1]],
            along_y: [step_y[0] - centre[0], step_y[1] - centre[1]],
        })
    }

    fn close_to(&self, other: &Self, width: f64) -> bool {
        let distance = |a: [f64; 2], b: [f64; 2]| (a[0] - b[0]).hypot(a[1] - b[1]);
        let reshaped = |a: [f64; 2], b: [f64; 2]| distance(a, b) / a[0].hypot(a[1]).max(1e-9);
        distance(self.centre, other.centre) / width < Self::SAME_PLACE
            && reshaped(self.along_x, other.along_x) < Self::SAME_SHAPE
            && reshaped(self.along_y, other.along_y) < Self::SAME_SHAPE
    }
}

fn apply(homography: &Matrix3<f64>, point: [f64; 2]) -> Option<[f64; 2]> {
    let mapped = homography * Vector3::new(point[0], point[1], 1.0);
    (mapped.z.abs() > 1e-12).then(|| [mapped.x / mapped.z, mapped.y / mapped.z])
}

/// What the app gets back from a calibration, as JSON.
#[derive(Serialize)]
pub struct Report {
    pub camera: Camera,
    /// Reprojection error over every point kept, pixels.
    pub rms: f64,
    pub views: usize,
    pub points: usize,
}

impl From<&Calibration> for Report {
    fn from(calibration: &Calibration) -> Self {
        Self {
            camera: calibration.camera.clone(),
            rms: calibration.rms,
            views: calibration.views.len(),
            points: calibration.views.iter().map(|fit| fit.used).sum(),
        }
    }
}

// --- JNI: dev.nabos.marco.vision.Vernier ------------------------------------------
//
// A session is handed to Kotlin as an opaque handle (a leaked Box) and must only
// be used from one thread at a time. Panics are caught: they mustn't unwind into
// the JVM.

fn guarded<T>(fallback: T, body: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(fallback)
}

/// # Safety
/// `handle` must come from `newSession` and not have been freed.
unsafe fn session<'a>(handle: jlong) -> &'a mut Session {
    unsafe { &mut *(handle as *mut Session) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_renderBoard(
    env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    square_px: jdouble,
) -> jbyteArray {
    let pixels = guarded(None, || {
        (width > 0 && height > 0 && square_px > 0.0)
            .then(|| render_board(width as usize, height as usize, square_px))
    });
    pixels
        .and_then(|pixels| env.byte_array_from_slice(&pixels).ok())
        .map_or(std::ptr::null_mut(), |array| array.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_newSession(
    _env: JNIEnv,
    _class: JClass,
    srgb: jboolean,
    bin: jint,
) -> jlong {
    let response = if srgb != 0 { Response::Srgb } else { Response::Linear };
    Box::into_raw(Box::new(Session::new(response, bin.max(1) as usize))) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_freeSession(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Session) });
    }
}

/// Points found, or -1 if the code wasn't read.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_measure(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    luma: JByteArray,
    width: jint,
    height: jint,
    stride: jint,
) -> jint {
    let Ok(bytes) = env.convert_byte_array(&luma) else {
        return -1;
    };
    guarded(-1, || {
        let session = unsafe { session(handle) };
        session
            .measure(&bytes, width.max(0) as usize, height.max(0) as usize, stride.max(0) as usize)
            .map_or(-1, |points| points as jint)
    })
}

/// Views kept so far, after keeping the last frame if it was good enough.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_keep(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let session = unsafe { session(handle) };
    session.keep();
    session.kept() as jint
}

/// The best-fitting calibration as a [`Report`] in JSON, plus `rms_by_model`;
/// or `{"error": "..."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_calibrate(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let json = guarded(error_json("calibration crashed"), || {
        let session = unsafe { session(handle) };
        match session.calibrate_best() {
            Ok((calibration, rms)) => {
                let mut report = serde_json::to_value(Report::from(&calibration)).unwrap_or_default();
                let by_model: serde_json::Map<String, serde_json::Value> = rms
                    .into_iter()
                    .map(|(model, rms)| (format!("{model:?}").to_lowercase(), rms.into()))
                    .collect();
                report["rms_by_model"] = by_model.into();
                report.to_string()
            }
            Err(e) => error_json(&e.to_string()),
        }
    });
    string(&env, json)
}

/// Takes a `camera` object as `calibrate` reports it; false if it doesn't parse.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_setCamera(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    camera_json: JString,
) -> jboolean {
    let Ok(json) = env.get_string(&camera_json).map(String::from) else {
        return JNI_FALSE;
    };
    guarded(JNI_FALSE, || match serde_json::from_str::<Camera>(&json) {
        Ok(camera) => {
            unsafe { session(handle) }.set_camera(camera);
            JNI_TRUE
        }
        Err(_) => JNI_FALSE,
    })
}

/// Board points behind a recorded pose, or -1 if the frame gave none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_track(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    luma: JByteArray,
    width: jint,
    height: jint,
    stride: jint,
    t_ns: jlong,
    exposure_ns: jlong,
    rolling_shutter_skew_ns: jlong,
) -> jint {
    let Ok(bytes) = env.convert_byte_array(&luma) else {
        return -1;
    };
    guarded(-1, || {
        let session = unsafe { session(handle) };
        session
            .track(
                &bytes,
                width.max(0) as usize,
                height.max(0) as usize,
                stride.max(0) as usize,
                t_ns,
                exposure_ns,
                rolling_shutter_skew_ns,
            )
            .map_or(-1, |points| points as jint)
    })
}

/// `count` gyro readings: times in ns, rates as `x, y, z` triples in rad/s.
/// Returns the poses recorded so far.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_addGyro(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    times: JLongArray,
    rates: JFloatArray,
    count: jint,
) -> jint {
    let count = count.max(0) as usize;
    let mut t = vec![0i64; count];
    let mut r = vec![0f32; count * 3];
    if env.get_long_array_region(&times, 0, &mut t).is_err() || env.get_float_array_region(&rates, 0, &mut r).is_err() {
        return -1;
    }
    guarded(-1, || {
        let session = unsafe { session(handle) };
        session.add_gyro(t.iter().zip(r.chunks(3)).map(|(&t_ns, rate)| GyroSample {
            t_ns,
            rate: Vector3::new(rate[0] as f64, rate[1] as f64, rate[2] as f64),
        }));
        session.poses() as jint
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_resetAlignment(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    unsafe { session(handle) }.reset_alignment();
}

/// A [`CameraImu`] as JSON, or `{"error": "..."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_align(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let json = guarded(error_json("alignment crashed"), || {
        match unsafe { session(handle) }.align() {
            Ok(alignment) => serde_json::to_string(&alignment).unwrap_or_else(|e| error_json(&e.to_string())),
            Err(e) => error_json(&e.to_string()),
        }
    });
    string(&env, json)
}

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}

fn string(env: &JNIEnv, value: String) -> jstring {
    env.new_string(value).map_or(std::ptr::null_mut(), |string| string.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    use nalgebra::{Rotation3, UnitQuaternion, Vector3};
    use vernier_camera::{RigidPose, Scene};

    #[test]
    fn rendered_board_reads_straight_on() {
        let (width, height) = (480, 360);
        let pixels = render_board(width, height, 12.0);
        assert_eq!(pixels.len(), width * height);
        let mut session = Session::new(Response::Srgb, 1);
        let points = session.measure(&pixels, width, height, width).expect("code read");
        assert!(points >= MIN_POINTS, "{points} points");
        assert!(session.keep());
    }

    #[test]
    fn turns_away_the_same_view_twice() {
        let (width, height) = (480, 360);
        let pixels = render_board(width, height, 12.0);
        let mut session = Session::new(Response::Srgb, 1);
        session.measure(&pixels, width, height, width);
        assert!(session.keep());
        session.measure(&pixels, width, height, width);
        assert!(!session.keep(), "a phone held still adds nothing");
        assert_eq!(session.kept(), 1);
    }

    #[test]
    fn rejects_short_buffers() {
        let mut session = Session::new(Response::Linear, 1);
        assert_eq!(session.measure(&[0; 10], 480, 360, 480), None);
        assert!(!session.keep());
    }

    /// A phone screen (about 28 × 60 squares) filmed by an ultrawide from a
    /// handful of hand-held poses, spread over the frame.
    #[test]
    fn calibrates_an_ultrawide_from_a_phone_screen() {
        let (width, height) = (640, 480);
        let mut truth = Camera::ideal(Model::Fisheye, width, height, 330.0, 331.0, 318.0, 243.0);
        truth.distortion = vec![0.02, -0.01, 0.003, -0.0008];
        let board = target();
        let scene = Scene {
            camera: &truth,
            target: &board,
            half_size: [14.0, 30.0],
            background: 0.05, // the phone's black bezel
            supersample: 2,
        };
        let poses = [
            ([0.0, 0.0, 0.2], [0.0, 0.0, 30.0]),
            ([0.4, 0.1, -0.3], [-12.0, -6.0, 28.0]),
            ([-0.35, 0.3, 1.2], [12.0, 5.0, 31.0]),
            ([0.15, -0.45, 1.9], [-10.0, 8.0, 27.0]),
            ([-0.3, -0.3, -1.1], [9.0, -9.0, 29.0]),
            ([0.3, 0.35, 2.7], [14.0, -4.0, 30.0]),
            ([0.05, 0.4, 0.6], [-15.0, 2.0, 28.0]),
            ([-0.4, -0.05, -0.7], [3.0, 10.0, 30.0]),
        ];
        let mut session = Session::new(Response::Linear, 1);
        for (rotation, translation) in poses {
            let pose = RigidPose::from_vectors(Vector3::from(rotation), Vector3::from(translation));
            let luma: Vec<u8> = scene.render(&pose).iter().map(|v| (v * 255.0).round() as u8).collect();
            session.measure(&luma, width, height, width);
            session.keep();
        }
        assert!(session.kept() >= 6, "kept {}", session.kept());
        let calibration = session.calibrate(Model::Fisheye).expect("calibrates");
        let camera = &calibration.camera;
        assert!((camera.fx / truth.fx - 1.0).abs() < 0.01, "fx {}", camera.fx);
        assert!((camera.fy / truth.fy - 1.0).abs() < 0.01, "fy {}", camera.fy);
        assert!((camera.cx - truth.cx).abs() < 2.0, "cx {}", camera.cx);
        assert!((camera.cy - truth.cy).abs() < 2.0, "cy {}", camera.cy);
        assert!(calibration.rms < 0.5, "rms {}", calibration.rms);
    }

    /// The whole path a phone takes: frames of the board from a camera that
    /// wobbles in front of it, gyro readings of the same wobble stamped 8 ms
    /// late, through `track` and `align`.
    #[test]
    fn aligns_camera_and_gyro_from_board_frames() {
        let (width, height) = (640, 480);
        let mut camera = Camera::ideal(Model::Fisheye, width, height, 330.0, 331.0, 318.0, 243.0);
        camera.distortion = vec![0.02, -0.01, 0.003, -0.0008];
        let board = target();
        let scene = Scene { camera: &camera, target: &board, half_size: [14.0, 30.0], background: 0.05, supersample: 1 };

        // Gyro axes to camera axes: a back camera's usual quarter turns, 2° off.
        let square = Rotation3::from_matrix_unchecked(Matrix3::new(0.0, -1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, -1.0));
        let imu_to_camera = Rotation3::from_scaled_axis(Vector3::new(0.2, 0.9, -0.4).normalize() * 2f64.to_radians()) * square;
        let offset_s = 0.008;
        // A wobble of about ±12° about every axis, in gyro axes.
        let rate = |t: f64| {
            let tau = std::f64::consts::TAU;
            Vector3::new(0.35 * (tau * 0.29 * t).sin(), 0.3 * (tau * 0.21 * t + 1.0).sin(), 0.3 * (tau * 0.37 * t + 2.0).sin())
        };
        let (seconds, dt) = (30.0, 1e-4);
        let mut wobble = vec![UnitQuaternion::identity()];
        for step in 0..(seconds / dt) as usize {
            let last = *wobble.last().unwrap();
            wobble.push(last * UnitQuaternion::from_scaled_axis(rate((step as f64 + 0.5) * dt) * dt));
        }

        let mut session = Session::new(Response::Linear, 1);
        session.set_camera(camera.clone());
        let mut t = 0.3;
        while t < seconds - 0.3 {
            // Board → camera: the camera faces the board from 30 squares away,
            // turned by the wobble seen in camera axes.
            let turn = wobble[(t / dt) as usize].to_rotation_matrix();
            let rotation = imu_to_camera * turn.inverse() * imu_to_camera.inverse();
            let pose = RigidPose { rotation, translation: rotation * Vector3::new(0.0, 0.0, 30.0) };
            let luma: Vec<u8> = scene.render(&pose).iter().map(|v| (v * 255.0).round() as u8).collect();
            session.track(&luma, width, height, width, (t * 1e9) as i64, 0, 0);
            t += 0.6;
        }
        let gyro = (0..(seconds / 0.005) as usize).map(|k| {
            let t = k as f64 * 0.005;
            GyroSample { t_ns: ((t + offset_s) * 1e9) as i64, rate: rate(t) }
        });
        session.add_gyro(gyro);
        assert!(session.poses() >= 40, "{} poses", session.poses());

        let found = session.align().expect("aligns");
        let q = found.imu_to_camera_xyzw;
        let rotation = UnitQuaternion::from_quaternion(nalgebra::Quaternion::new(q[3], q[0], q[1], q[2]));
        let error = (rotation.to_rotation_matrix().inverse() * imu_to_camera).angle().to_degrees();
        assert!(error < 0.3, "rotation off by {error}°");
        assert!((found.time_offset_ms - 8.0).abs() < 2.0, "offset {} ms", found.time_offset_ms);
        assert!((found.off_axis_deg - 2.0).abs() < 0.3, "off axis {}°", found.off_axis_deg);
    }

    /// Where a frame's time goes with a monitor-sized board filling much of a
    /// 1280×960 ultrawide frame. `cargo test --release timing -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn timing_on_a_monitor_board() {
        let (width, height) = (1280, 960);
        let camera = Camera::ideal(Model::Pinhole, width, height, 498.0, 498.0, 642.0, 477.0);
        let board = target();
        let scene = Scene { camera: &camera, target: &board, half_size: [40.0, 22.5], background: 0.05, supersample: 1 };
        let pose = RigidPose::from_vectors(Vector3::new(0.2, -0.15, 0.1), Vector3::new(0.0, 0.0, 53.0));
        let luma: Vec<u8> = scene.render(&pose).iter().map(|v| (v * 255.0).round() as u8).collect();
        let mut session = Session::new(Response::Linear, 1);
        let mut binned = Session::new(Response::Linear, 2);
        for round in 0..3 {
            let started = std::time::Instant::now();
            let view = binned.read(&luma, width, height, width).expect("reads binned");
            eprintln!("round {round} binned: {} points, measure {:?}", view.points.len(), started.elapsed());
            let started = std::time::Instant::now();
            let view = session.read(&luma, width, height, width).expect("reads");
            let measured = started.elapsed();
            let started = std::time::Instant::now();
            let fit = solve_pnp(&camera, &view).expect("pose");
            let solved = started.elapsed();
            eprintln!("round {round}: {} points, measure {measured:?}, pnp {solved:?} (rms {:.3})", view.points.len(), fit.rms);
        }
    }

    /// A calibration made at full resolution keeps working through binning.
    #[test]
    fn tracks_binned_frames_with_a_full_size_calibration() {
        let (width, height) = (1280, 960);
        let mut camera = Camera::ideal(Model::Pinhole, width, height, 996.0, 997.0, 641.0, 478.0);
        camera.distortion = vec![0.02, -0.02, 0.0, 0.0, 0.004];
        let half = scaled_to(&camera, 640, 480).expect("scales");
        assert_eq!((half.fx, half.cx), (498.0, 320.25));
        assert!(scaled_to(&camera, 600, 480).is_none());

        let board = target();
        let scene = Scene { camera: &camera, target: &board, half_size: [40.0, 22.5], background: 0.05, supersample: 1 };
        let rotation = Vector3::new(0.2, -0.15, 0.1);
        let pose = RigidPose::from_vectors(rotation, Vector3::new(0.0, 0.0, 70.0));
        let luma: Vec<u8> = scene.render(&pose).iter().map(|v| (v * 255.0).round() as u8).collect();
        let mut session = Session::new(Response::Linear, 2);
        session.set_camera(camera);
        assert!(session.track(&luma, width, height, width, 0, 0, 0).is_some(), "binned frame gives a pose");
        let measured = session.orientations[0].camera_to_board.inverse();
        let error = (measured.inverse() * pose.rotation).angle().to_degrees();
        assert!(error < 0.1, "pose off by {error}°");
    }
}
