//! Marco's bridge to Vernier.
//!
//! One phone shows Vernier's coded checkerboard full-screen ([`render_board`]);
//! the other films it while turning and moving, and one [`Session`] turns that
//! single pass into a lens calibration and a camera-to-gyroscope alignment. The
//! JNI entry points at the bottom are what the app calls.

pub mod imu;

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JFloatArray, JLongArray};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jintArray, jlong, jstring};
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

/// Points kept per view, evenly spread. A monitor board gives ~9000 a view;
/// a fit with all of them took 10–15 s on a phone, and past a thousand or so
/// more points hardly change the result.
pub const MAX_POINTS_PER_VIEW: usize = 1500;

/// A grid over the frame tells which parts the board has visited. It spans
/// the frame less [`COVER_INSET`] of its size on each side: Vernier's
/// measuring window needs room, so no point ever lands within a few board
/// periods of the frame's edge. Points out there count in the nearest cell.
pub const COVER_COLUMNS: usize = 6;
pub const COVER_ROWS: usize = 4;
pub const COVER_INSET: f64 = 0.125;

/// When a pass has enough to finish on its own.
pub const READY_VIEWS: usize = 12;
pub const READY_COVERAGE: f64 = 0.8;
pub const READY_POSES: usize = 30;
/// The least a calibration can be made from at all.
pub const MIN_VIEWS: usize = 6;

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

/// One pass over the board: every frame measured, the views kept for the
/// lens calibration, and the gyroscope's readings over the same time. At the
/// end, [`Session::finish`] calibrates the lens from the views, solves every
/// frame's pose with it, and aligns those poses with the gyroscope.
pub struct Session {
    target: Target,
    response: Response,
    /// Frames are averaged over `bin × bin` blocks before measuring.
    bin: usize,
    /// Last view whose code was read, to start the next frame's search from.
    previous: Option<View>,
    /// Last view good enough to keep, until the next frame.
    candidate: Option<View>,
    /// Kept for the calibration.
    views: Vec<View>,
    /// Of each kept view, to turn away near-copies.
    footprints: Vec<Footprint>,
    /// Every frame measured, for the alignment's poses.
    frames: Vec<TimedView>,
    gyro: Vec<GyroSample>,
    /// Which cells of the frame the kept views' points have visited.
    covered: [bool; COVER_COLUMNS * COVER_ROWS],
}

/// A measured frame, stamped at the middle of the exposure of the board's
/// average row (rows expose one after another: a rolling shutter).
struct TimedView {
    t_ns: i64,
    view: View,
}

/// Where a pass stands, for the app to show and to know when to finish.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Status {
    pub views: usize,
    pub poses: usize,
    /// Fraction of the frame's cells the board has visited.
    pub coverage: f64,
    pub ready: bool,
}

impl Session {
    /// `response` is the transfer curve the camera encoded its frames with.
    /// Frames are averaged over `bin × bin` pixel blocks before measuring:
    /// measuring costs about 1 s for a 1280×960 frame on a phone, a third of
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
            frames: Vec::new(),
            gyro: Vec::new(),
            covered: [false; COVER_COLUMNS * COVER_ROWS],
        }
    }

    /// Measures one frame of 8-bit luma, `stride` bytes per row. Returns how
    /// many board points it found, or `None` if the code wasn't read.
    ///
    /// `t_ns` is the frame's sensor timestamp, when its first row started
    /// exposing; with `exposure_ns` and `rolling_shutter_skew_ns` it places
    /// the frame at the moment the board's rows were exposed.
    #[allow(clippy::too_many_arguments)]
    pub fn measure(
        &mut self,
        luma: &[u8],
        width: usize,
        height: usize,
        stride: usize,
        t_ns: i64,
        exposure_ns: i64,
        rolling_shutter_skew_ns: i64,
    ) -> Option<usize> {
        self.candidate = None;
        let view = self.read(luma, width, height, stride)?;
        let points = view.points.len();
        if points < MIN_POINTS {
            return Some(points);
        }
        let view = thinned(&view, MAX_POINTS_PER_VIEW);
        let mean_row = view.points.iter().map(|point| point.pixel[1]).sum::<f64>() / view.points.len() as f64;
        let row_delay = rolling_shutter_skew_ns as f64 * mean_row / view.height as f64;
        self.frames.push(TimedView {
            t_ns: t_ns + exposure_ns / 2 + row_delay as i64,
            view: view.clone(),
        });
        self.candidate = Some(view);
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
        let cell = |position: f64, size: usize, cells: usize| {
            let inside = (position / size as f64 - COVER_INSET) / (1.0 - 2.0 * COVER_INSET);
            ((inside.clamp(0.0, 0.9999)) * cells as f64) as usize
        };
        for point in &view.points {
            let column = cell(point.pixel[0], view.width, COVER_COLUMNS);
            let row = cell(point.pixel[1], view.height, COVER_ROWS);
            self.covered[row * COVER_COLUMNS + column] = true;
        }
        self.views.push(view);
        self.footprints.push(footprint);
        true
    }

    pub fn kept(&self) -> usize {
        self.views.len()
    }

    /// Row-major, [`COVER_COLUMNS`] × [`COVER_ROWS`].
    pub fn covered(&self) -> &[bool] {
        &self.covered
    }

    pub fn status(&self) -> Status {
        let coverage = self.covered.iter().filter(|&&cell| cell).count() as f64 / self.covered.len() as f64;
        Status {
            views: self.views.len(),
            poses: self.frames.len(),
            coverage,
            ready: self.views.len() >= READY_VIEWS && coverage >= READY_COVERAGE && self.frames.len() >= READY_POSES,
        }
    }

    pub fn add_gyro(&mut self, samples: impl IntoIterator<Item = GyroSample>) {
        self.gyro.extend(samples);
    }

    /// Calibrates with each lens model and keeps the one that fits best.
    /// Returns it, with every model's rms.
    ///
    /// A wide lens can be anything from nearly rectilinear (a phone's
    /// ultrawide, corrected optically) to a true fisheye: the data decides.
    pub fn calibrate_best(&self) -> Result<(Calibration, Vec<(Model, f64)>), CalibrationError> {
        if self.views.len() < MIN_VIEWS {
            return Err(CalibrationError::TooFewViews(self.views.len()));
        }
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
        Ok((best, rms))
    }

    /// Calibrates the lens, then solves every measured frame's pose with it
    /// and aligns those poses with the gyroscope. The alignment can fail on
    /// its own (too little turning, no gyroscope) without losing the lens.
    pub fn finish(&self) -> Result<Finished, CalibrationError> {
        let (calibration, rms_by_model) = self.calibrate_best()?;
        let orientations: Vec<CameraOrientation> = self
            .frames
            .iter()
            .filter_map(|frame| {
                let fit = solve_pnp(&calibration.camera, &frame.view).ok()?;
                (fit.rms <= MAX_POSE_RMS).then(|| CameraOrientation {
                    t_ns: frame.t_ns,
                    camera_to_board: fit.pose.rotation.inverse(),
                })
            })
            .collect();
        let alignment = imu::align(&orientations, &self.gyro);
        Ok(Finished { calibration, rms_by_model, alignment })
    }
}

/// What [`Session::finish`] found.
pub struct Finished {
    pub calibration: Calibration,
    pub rms_by_model: Vec<(Model, f64)>,
    pub alignment: Result<CameraImu, ImuError>,
}

impl Finished {
    /// The [`Report`] with `rms_by_model`, and `imu` or `imu_error`.
    pub fn to_json(&self) -> serde_json::Value {
        let mut json = serde_json::to_value(Report::from(&self.calibration)).unwrap_or_default();
        let by_model: serde_json::Map<String, serde_json::Value> = self
            .rms_by_model
            .iter()
            .map(|(model, rms)| (format!("{model:?}").to_lowercase(), (*rms).into()))
            .collect();
        json["rms_by_model"] = by_model.into();
        match &self.alignment {
            Ok(alignment) => json["imu"] = serde_json::to_value(alignment).unwrap_or_default(),
            Err(error) => json["imu_error"] = error.to_string().into(),
        }
        json
    }
}

/// `view` with at most `max` of its points, evenly spread over the list.
fn thinned(view: &View, max: usize) -> View {
    let mut view = view.clone();
    if view.points.len() > max {
        let step = view.points.len().div_ceil(max);
        view.points = view.points.into_iter().step_by(step).collect();
    }
    view
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

/// The lens calibration as the app gets it, in JSON.
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
            .measure(
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

/// Views kept so far, after keeping the last frame if it was good enough.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_keep(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded(-1, || {
        let session = unsafe { session(handle) };
        session.keep();
        session.kept() as jint
    })
}

/// `[views, poses, coverage ‰, ready, columns, rows, inset ‰, cell…]`, cells
/// row-major, 1 where the board has been.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_status(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jintArray {
    let values = guarded(Vec::new(), || {
        let session = unsafe { session(handle) };
        let status = session.status();
        let mut values = vec![
            status.views as jint,
            status.poses as jint,
            (status.coverage * 1000.0).round() as jint,
            status.ready as jint,
            COVER_COLUMNS as jint,
            COVER_ROWS as jint,
            (COVER_INSET * 1000.0).round() as jint,
        ];
        values.extend(session.covered().iter().map(|&cell| cell as jint));
        values
    });
    let Ok(array) = env.new_int_array(values.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_int_array_region(&array, 0, &values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

/// `count` gyro readings: times in ns, rates as `x, y, z` triples in rad/s.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_addGyro(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    times: JLongArray,
    rates: JFloatArray,
    count: jint,
) {
    let count = count.max(0) as usize;
    let mut t = vec![0i64; count];
    let mut r = vec![0f32; count * 3];
    if env.get_long_array_region(&times, 0, &mut t).is_err() || env.get_float_array_region(&rates, 0, &mut r).is_err() {
        return;
    }
    guarded((), || {
        unsafe { session(handle) }.add_gyro(t.iter().zip(r.chunks(3)).map(|(&t_ns, rate)| GyroSample {
            t_ns,
            rate: Vector3::new(rate[0] as f64, rate[1] as f64, rate[2] as f64),
        }));
    })
}

/// [`Finished::to_json`], or `{"error": "..."}` if the lens couldn't be calibrated.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_finish(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let json = guarded(error_json("calibration crashed"), || match unsafe { session(handle) }.finish() {
        Ok(finished) => finished.to_json().to_string(),
        Err(e) => error_json(&e.to_string()),
    });
    env.new_string(json)
        .map_or(std::ptr::null_mut(), |string| string.into_raw())
}

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use nalgebra::{Rotation3, UnitQuaternion, Vector3};
    use vernier_camera::{RigidPose, Scene};

    fn luma(scene: &Scene, pose: &RigidPose) -> Vec<u8> {
        scene.render(pose).iter().map(|v| (v * 255.0).round() as u8).collect()
    }

    #[test]
    fn rendered_board_reads_straight_on() {
        let (width, height) = (480, 360);
        let pixels = render_board(width, height, 12.0);
        assert_eq!(pixels.len(), width * height);
        let mut session = Session::new(Response::Srgb, 1);
        let points = session.measure(&pixels, width, height, width, 0, 0, 0).expect("code read");
        assert!(points >= MIN_POINTS, "{points} points");
        assert!(session.keep());
        let coverage = session.status().coverage;
        assert_eq!(coverage, 1.0, "a board filling the frame covers all it can");
    }

    #[test]
    fn rejects_short_buffers() {
        let mut session = Session::new(Response::Linear, 1);
        assert_eq!(session.measure(&[0; 10], 480, 360, 480, 0, 0, 0), None);
        assert!(!session.keep());
    }

    #[test]
    fn turns_away_the_same_view_twice() {
        let (width, height) = (480, 360);
        let pixels = render_board(width, height, 12.0);
        let mut session = Session::new(Response::Srgb, 1);
        session.measure(&pixels, width, height, width, 0, 0, 0);
        assert!(session.keep());
        session.measure(&pixels, width, height, width, 1, 0, 0);
        assert!(!session.keep(), "a phone held still adds nothing");
        assert_eq!(session.kept(), 1);
        assert_eq!(session.status().poses, 2, "but every frame is a pose");
    }

    #[test]
    fn thins_views_evenly() {
        let (width, height) = (480, 360);
        let pixels = render_board(width, height, 6.0);
        let mut session = Session::new(Response::Srgb, 1);
        let points = session.measure(&pixels, width, height, width, 0, 0, 0).expect("code read");
        assert!(points > MAX_POINTS_PER_VIEW, "{points} points");
        let kept = &session.frames[0].view;
        assert!(kept.points.len() <= MAX_POINTS_PER_VIEW && kept.points.len() > MAX_POINTS_PER_VIEW / 2);
    }

    #[test]
    fn scales_a_calibration_down() {
        let mut camera = Camera::ideal(Model::Pinhole, 1280, 960, 996.0, 997.0, 641.0, 478.0);
        camera.distortion = vec![0.02, -0.02, 0.0, 0.0, 0.004];
        let half = scaled_to(&camera, 640, 480).expect("scales");
        assert_eq!((half.fx, half.cx, half.distortion.clone()), (498.0, 320.25, camera.distortion.clone()));
        assert!(scaled_to(&camera, 600, 480).is_none());
    }

    /// A phone screen (about 28 × 60 squares) filmed by an ultrawide from a
    /// handful of hand-held poses, spread over the frame.
    #[test]
    fn calibrates_an_ultrawide_from_a_phone_screen() {
        let (width, height) = (640, 480);
        let mut truth = Camera::ideal(Model::Fisheye, width, height, 330.0, 331.0, 318.0, 243.0);
        truth.distortion = vec![0.02, -0.01, 0.003, -0.0008];
        let board = target();
        let scene = Scene { camera: &truth, target: &board, half_size: [14.0, 30.0], background: 0.05, supersample: 2 };
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
        for (k, (rotation, translation)) in poses.into_iter().enumerate() {
            let pose = RigidPose::from_vectors(Vector3::from(rotation), Vector3::from(translation));
            session.measure(&luma(&scene, &pose), width, height, width, k as i64, 0, 0);
            session.keep();
        }
        assert!(session.kept() >= 6, "kept {}", session.kept());
        let (calibration, _) = session.calibrate_best().expect("calibrates");
        let camera = &calibration.camera;
        assert!((camera.fx / truth.fx - 1.0).abs() < 0.01, "fx {}", camera.fx);
        assert!((camera.fy / truth.fy - 1.0).abs() < 0.01, "fy {}", camera.fy);
        assert!((camera.cx - truth.cx).abs() < 2.0, "cx {}", camera.cx);
        assert!((camera.cy - truth.cy).abs() < 2.0, "cy {}", camera.cy);
        assert!(calibration.rms < 0.5, "rms {}", calibration.rms);
    }

    /// The whole pass as a phone makes it: frames of the board from a camera
    /// that wobbles and drifts in front of it, gyro readings of the same
    /// wobble stamped 8 ms late; `finish` calibrates the lens from the frames
    /// alone, then aligns.
    #[test]
    fn one_pass_calibrates_and_aligns() {
        let (width, height) = (640, 480);
        let mut camera = Camera::ideal(Model::Fisheye, width, height, 330.0, 331.0, 318.0, 243.0);
        camera.distortion = vec![0.02, -0.01, 0.003, -0.0008];
        let board = target();
        let scene = Scene { camera: &camera, target: &board, half_size: [14.0, 30.0], background: 0.05, supersample: 1 };

        // Gyro axes to camera axes: a back camera's usual quarter turns, 2° off.
        let square = Rotation3::from_matrix_unchecked(Matrix3::new(0.0, -1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, -1.0));
        let imu_to_camera = Rotation3::from_scaled_axis(Vector3::new(0.2, 0.9, -0.4).normalize() * 2f64.to_radians()) * square;
        let offset_s = 0.008;
        // A wobble of about ±15° about every axis, in gyro axes.
        let tau = std::f64::consts::TAU;
        let rate = |t: f64| {
            Vector3::new(0.4 * (tau * 0.29 * t).sin(), 0.35 * (tau * 0.21 * t + 1.0).sin(), 0.35 * (tau * 0.37 * t + 2.0).sin())
        };
        let (seconds, dt) = (30.0, 1e-4);
        let mut wobble = vec![UnitQuaternion::identity()];
        for step in 0..(seconds / dt) as usize {
            let last = *wobble.last().unwrap();
            wobble.push(last * UnitQuaternion::from_scaled_axis(rate((step as f64 + 0.5) * dt) * dt));
        }

        let mut session = Session::new(Response::Linear, 1);
        let mut t = 0.3;
        while t < seconds - 0.3 {
            // Board → camera: the camera faces the board from 30 squares away,
            // turned by the wobble seen in camera axes, while sliding about.
            let turn = wobble[(t / dt) as usize].to_rotation_matrix();
            let rotation = imu_to_camera * turn.inverse() * imu_to_camera.inverse();
            let slide = Vector3::new(9.0 * (tau * 0.13 * t).sin(), 14.0 * (tau * 0.09 * t + 0.5).sin(), 30.0);
            let pose = RigidPose { rotation, translation: rotation * slide };
            session.measure(&luma(&scene, &pose), width, height, width, (t * 1e9) as i64, 0, 0);
            session.keep();
            t += 0.4;
        }
        let gyro = (0..(seconds / 0.005) as usize).map(|k| {
            let t = k as f64 * 0.005;
            GyroSample { t_ns: ((t + offset_s) * 1e9) as i64, rate: rate(t) }
        });
        session.add_gyro(gyro);
        let status = session.status();
        assert!(status.poses >= 60 && status.views >= READY_VIEWS, "{status:?}");

        let finished = session.finish().expect("calibrates");
        let fx = finished.calibration.camera.fx;
        assert!((fx / camera.fx - 1.0).abs() < 0.01, "fx {fx}");
        let found = finished.alignment.as_ref().expect("aligns");
        let q = found.imu_to_camera_xyzw;
        let rotation = UnitQuaternion::from_quaternion(nalgebra::Quaternion::new(q[3], q[0], q[1], q[2]));
        let error = (rotation.to_rotation_matrix().inverse() * imu_to_camera).angle().to_degrees();
        assert!(error < 0.3, "rotation off by {error}°");
        assert!((found.time_offset_ms - 8.0).abs() < 2.0, "offset {} ms", found.time_offset_ms);
        assert!(found.pairs > 100, "{} pairs from {} poses", found.pairs, found.poses);
        let json = finished.to_json();
        assert!(json["imu"]["time_offset_ms"].is_number() && json["rms_by_model"]["pinhole"].is_number());
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
        let frame = luma(&scene, &pose);
        for bin in [1, 2] {
            let mut session = Session::new(Response::Linear, bin);
            for round in 0..3 {
                let started = std::time::Instant::now();
                let view = session.read(&frame, width, height, width).expect("reads");
                eprintln!("bin {bin} round {round}: {} points, measure {:?}", view.points.len(), started.elapsed());
            }
        }
    }

    /// A full pass's final step on a monitor board, binned 640×480.
    /// `cargo test --release timing_finish -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn timing_finish() {
        let (width, height) = (640, 480);
        let mut camera = Camera::ideal(Model::Pinhole, width, height, 250.0, 250.0, 320.0, 240.0);
        camera.distortion = vec![0.015, -0.02, 0.0, 0.0, 0.009];
        let board = target();
        let scene = Scene { camera: &camera, target: &board, half_size: [40.0, 22.5], background: 0.05, supersample: 1 };
        let mut session = Session::new(Response::Linear, 1);
        let mut measuring = std::time::Duration::ZERO;
        for k in 0..60 {
            let a = k as f64 * 0.37;
            let rotation = Vector3::new(0.35 * a.sin(), 0.35 * (1.3 * a).cos(), 0.6 * (0.7 * a).sin());
            let translation = Vector3::new(8.0 * (0.9 * a).cos(), 5.0 * (1.1 * a).sin(), 28.0 + 6.0 * a.sin());
            let frame = luma(&scene, &RigidPose::from_vectors(rotation, translation));
            let started = std::time::Instant::now();
            session.measure(&frame, width, height, width, k * 400_000_000, 0, 0);
            measuring += started.elapsed();
            session.keep();
        }
        let started = std::time::Instant::now();
        let finished = session.finish().expect("calibrates");
        eprintln!(
            "{:?}: measuring {:?} per frame, finishing {:?} (rms {:?})",
            session.status(),
            measuring / 60,
            started.elapsed(),
            finished.rms_by_model,
        );
    }
}
