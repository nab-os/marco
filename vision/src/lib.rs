//! Marco's bridge to Vernier.
//!
//! One phone shows Vernier's coded checkerboard full-screen ([`render_board`]);
//! the other films it and turns the frames into a camera calibration
//! ([`Session`]). The JNI entry points at the bottom are what the app calls.

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jstring};
use nalgebra::{Matrix3, Vector3};
use serde::Serialize;
use vernier_camera::geometry::homography;
use vernier_camera::{
    Calibration, CalibrationError, Camera, Model, Response, Target, View, calibrate, measure_view,
    measure_view_after,
};
use vernier_patterns::PatternPose;
use vernier_patterns::checkerboard::CodePacking;

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
    in_pixels
        .checkerboard()
        .expect("code size 5 is supported")
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
    /// Last view whose code was read, to start the next frame's search from.
    previous: Option<View>,
    /// Last view good enough to keep, until the next frame.
    candidate: Option<View>,
    views: Vec<View>,
    /// Of each kept view, to turn away near-copies.
    footprints: Vec<Footprint>,
}

impl Session {
    /// `response` is the transfer curve the camera encoded its frames with.
    pub fn new(response: Response) -> Self {
        Self {
            target: target(),
            response,
            previous: None,
            candidate: None,
            views: Vec::new(),
            footprints: Vec::new(),
        }
    }

    /// Measures one frame of 8-bit luma, `stride` bytes per row. Returns how
    /// many board points it found, or `None` if the code wasn't read.
    pub fn measure(&mut self, luma: &[u8], width: usize, height: usize, stride: usize) -> Option<usize> {
        self.candidate = None;
        if width == 0 || height == 0 || stride < width || luma.len() < stride * (height - 1) + width {
            return None;
        }
        let mut frame = Vec::with_capacity(width * height);
        for row in luma.chunks(stride).take(height) {
            frame.extend(row[..width].iter().map(|&value| value as f32 / 255.0));
        }
        self.response.linearize(&mut frame);
        let measured = match &self.previous {
            Some(previous) => measure_view_after(&frame, width, height, &self.target, previous),
            None => measure_view(&frame, width, height, &self.target),
        };
        match measured {
            Ok(view) if view.is_absolute() => {
                let points = view.points.len();
                if points >= MIN_POINTS {
                    self.candidate = Some(view.clone());
                }
                self.previous = Some(view);
                Some(points)
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
}

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
) -> jlong {
    let response = if srgb != 0 { Response::Srgb } else { Response::Linear };
    Box::into_raw(Box::new(Session::new(response))) as jlong
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

/// A [`Report`] as JSON, or `{"error": "..."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nabos_marco_vision_Vernier_calibrate(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    fisheye: jboolean,
) -> jstring {
    let json = guarded(r#"{"error":"calibration crashed"}"#.to_string(), || {
        let session = unsafe { session(handle) };
        let model = if fisheye != 0 { Model::Fisheye } else { Model::Pinhole };
        match session.calibrate(model) {
            Ok(calibration) => serde_json::to_string(&Report::from(&calibration))
                .unwrap_or_else(|e| serde_json::json!({ "error": e.to_string() }).to_string()),
            Err(e) => serde_json::json!({ "error": e.to_string() }).to_string(),
        }
    });
    env.new_string(json)
        .map_or(std::ptr::null_mut(), |string| string.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    use nalgebra::Vector3;
    use vernier_camera::{RigidPose, Scene};

    #[test]
    fn rendered_board_reads_straight_on() {
        let (width, height) = (480, 360);
        let pixels = render_board(width, height, 12.0);
        assert_eq!(pixels.len(), width * height);
        let mut session = Session::new(Response::Srgb);
        let points = session.measure(&pixels, width, height, width).expect("code read");
        assert!(points >= MIN_POINTS, "{points} points");
        assert!(session.keep());
    }

    #[test]
    fn turns_away_the_same_view_twice() {
        let (width, height) = (480, 360);
        let pixels = render_board(width, height, 12.0);
        let mut session = Session::new(Response::Srgb);
        session.measure(&pixels, width, height, width);
        assert!(session.keep());
        session.measure(&pixels, width, height, width);
        assert!(!session.keep(), "a phone held still adds nothing");
        assert_eq!(session.kept(), 1);
    }

    #[test]
    fn rejects_short_buffers() {
        let mut session = Session::new(Response::Linear);
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
        let mut session = Session::new(Response::Linear);
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
}
