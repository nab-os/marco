//! Live visual-inertial tracking with Basalt (Monado's fork, BSD-3), loaded at
//! run time from `libbasalt.so` and driven through its VIT C interface, the
//! same one `tools/basalt_runner` uses.
//!
//! Basalt is stereo-only: frames come in pairs from the phone's ultrawide
//! (cam0) and main lens (cam1). Its calibration file is made from Marco's own
//! (lens models, the measured gyro-to-camera rotation, the stereo pose), and
//! frames are moved onto the gyro's clock by the measured offset.

use std::collections::VecDeque;
use std::ffi::{CString, c_char, c_void};
use std::path::Path;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

use libloading::Library;
use nalgebra::{Quaternion, UnitQuaternion, Vector3};
use serde_json::{Value, json};

/// Basalt's VIO settings for a phone's narrow-baseline lens pair (see the runner).
const PHONE_CONFIG: &str = include_str!("../tools/basalt_runner/phone_config.json");

// --- vit_interface.h, the parts used ---------------------------------------------

#[repr(C)]
struct VitConfig {
    file: *const c_char,
    cam_count: u32,
    imu_count: u32,
    show_ui: bool,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct VitImuSample {
    timestamp: i64,
    ax: f32,
    ay: f32,
    az: f32,
    wx: f32,
    wy: f32,
    wz: f32,
}

#[repr(C)]
struct VitImgSample {
    cam_index: u32,
    timestamp: i64,
    data: *const u8,
    width: u32,
    height: u32,
    stride: u32,
    size: u32,
    format: i32,
    mask_count: u32,
    masks: *const c_void,
}

const VIT_IMAGE_FORMAT_L8: i32 = 1;
const VIT_SUCCESS: i32 = 0;

/// A pose as Basalt gives it: the IMU's pose in a gravity-aligned world (z up).
#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct Pose {
    pub timestamp: i64,
    pub px: f32,
    pub py: f32,
    pub pz: f32,
    pub ox: f32,
    pub oy: f32,
    pub oz: f32,
    pub ow: f32,
    pub vx: f32,
    pub vy: f32,
    pub vz: f32,
}

type Create = unsafe extern "C" fn(*const VitConfig, *mut *mut c_void) -> i32;
type Handle = unsafe extern "C" fn(*mut c_void) -> i32;
type Destroy = unsafe extern "C" fn(*mut c_void);
type PushImu = unsafe extern "C" fn(*mut c_void, *const VitImuSample) -> i32;
type PushImg = unsafe extern "C" fn(*mut c_void, *const VitImgSample) -> i32;
type PopPose = unsafe extern "C" fn(*mut c_void, *mut *mut c_void) -> i32;
type PoseData = unsafe extern "C" fn(*const c_void, *mut Pose) -> i32;
type PoseDestroy = unsafe extern "C" fn(*mut c_void);

/// Basalt's entry points, resolved once from the loaded library.
struct Api {
    create: Create,
    start: Handle,
    stop: Handle,
    destroy: Destroy,
    push_imu: PushImu,
    push_img: PushImg,
    pop_pose: PopPose,
    pose_data: PoseData,
    pose_destroy: PoseDestroy,
    _library: Library,
}

impl Api {
    fn load() -> Result<Self, String> {
        let library = unsafe { Library::new("libbasalt.so") }.map_err(|e| format!("Basalt isn't in this build: {e}"))?;
        unsafe {
            macro_rules! symbol {
                ($name:literal) => {
                    *library.get($name).map_err(|e| format!("Basalt: {e}"))?
                };
            }
            Ok(Self {
                create: symbol!(b"vit_tracker_create\0"),
                start: symbol!(b"vit_tracker_start\0"),
                stop: symbol!(b"vit_tracker_stop\0"),
                destroy: symbol!(b"vit_tracker_destroy\0"),
                push_imu: symbol!(b"vit_tracker_push_imu_sample\0"),
                push_img: symbol!(b"vit_tracker_push_img_sample\0"),
                pop_pose: symbol!(b"vit_tracker_pop_pose\0"),
                pose_data: symbol!(b"vit_pose_get_data\0"),
                pose_destroy: symbol!(b"vit_pose_destroy\0"),
                _library: library,
            })
        }
    }
}

/// How far past the newest frame IMU readings are passed on. Basalt's IMU queue
/// holds 300 readings (1.5 s at 200 Hz) and only drains as frames are processed:
/// pushing further ahead blocks once frames stop or are slow to start.
const IMU_LEAD_NS: i64 = 100_000_000;
/// Readings kept back at most (2 s): older ones are dropped while no frames come.
const IMU_PENDING_MAX: usize = 400;

/// One run of the tracker. IMU readings, frames and polling may come from
/// different threads at once: pushes are serialised here, and polling reads
/// Basalt's concurrent output queue without waiting on them (pushes block
/// while Basalt is behind).
pub struct Tracker {
    api: Api,
    tracker: *mut c_void,
    /// Added to camera timestamps: the measured gyro-minus-camera clock offset.
    offset_ns: i64,
    inputs: Mutex<Inputs>,
    stopped: AtomicBool,
}

struct Inputs {
    /// Last frame pushed, to keep frame timestamps strictly increasing.
    last_frame_ns: i64,
    /// IMU readings held back until a frame comes near them.
    pending: VecDeque<VitImuSample>,
}

// Basalt's VIT calls are safe from several threads; the pushes are serialised by `inputs`.
unsafe impl Send for Tracker {}
unsafe impl Sync for Tracker {}

impl Tracker {
    /// Writes Basalt's calibration and settings into `dir` and starts it, from a
    /// Marco stereo calibration (the JSON the Calibrator saves).
    pub fn start(calibration: &str, dir: &Path) -> Result<Self, String> {
        let calibration: Value = serde_json::from_str(calibration).map_err(|e| format!("calibration: {e}"))?;
        let offset_ns = calibration["imu"]["time_offset_ms"].as_f64().map(|ms| (ms * 1e6).round() as i64).unwrap_or(0);
        let basalt_calibration = basalt_calibration(&calibration)?;
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        let write = |name: &str, text: &str| std::fs::write(dir.join(name), text).map_err(|e| format!("{name}: {e}"));
        write("calib.json", &basalt_calibration.to_string())?;
        write("config.json", PHONE_CONFIG)?;
        let toml = format!(
            "show-gui=0\ncam-calib=\"{}\"\nconfig-path=\"{}\"\ndeterministic=0\nnum-threads=0\n",
            dir.join("calib.json").display(),
            dir.join("config.json").display(),
        );
        write("basalt.toml", &toml)?;

        let api = Api::load()?;
        let file = CString::new(dir.join("basalt.toml").to_string_lossy().as_bytes()).map_err(|e| e.to_string())?;
        let config = VitConfig { file: file.as_ptr(), cam_count: 2, imu_count: 1, show_ui: false };
        let mut tracker = std::ptr::null_mut();
        let created = unsafe { (api.create)(&config, &mut tracker) };
        if created != VIT_SUCCESS || tracker.is_null() {
            return Err(format!("vit_tracker_create failed ({created})"));
        }
        if unsafe { (api.start)(tracker) } != VIT_SUCCESS {
            unsafe { (api.destroy)(tracker) };
            return Err("vit_tracker_start failed".into());
        }
        Ok(Self {
            api,
            tracker,
            offset_ns,
            inputs: Mutex::new(Inputs { last_frame_ns: i64::MIN, pending: VecDeque::new() }),
            stopped: AtomicBool::new(false),
        })
    }

    /// One IMU reading on the sensors' clock: accelerometer m/s², gyroscope rad/s.
    pub fn push_imu(&self, t_ns: i64, accel: [f32; 3], gyro: [f32; 3]) {
        if self.stopped.load(Ordering::Acquire) {
            return;
        }
        let mut inputs = self.inputs.lock().unwrap_or_else(|e| e.into_inner());
        if inputs.pending.len() == IMU_PENDING_MAX {
            inputs.pending.pop_front();
        }
        inputs.pending.push_back(VitImuSample { timestamp: t_ns, ax: accel[0], ay: accel[1], az: accel[2], wx: gyro[0], wy: gyro[1], wz: gyro[2] });
        self.flush_imu(&mut inputs);
    }

    /// Passes on the readings up to [IMU_LEAD_NS] past the newest frame.
    fn flush_imu(&self, inputs: &mut Inputs) {
        let until = inputs.last_frame_ns.saturating_add(IMU_LEAD_NS);
        while let Some(sample) = inputs.pending.front().filter(|s| s.timestamp <= until) {
            unsafe { (self.api.push_imu)(self.tracker, sample) };
            inputs.pending.pop_front();
        }
    }

    /// A stereo pair from one capture: `t_ns` is the camera's moment for both
    /// (mid-exposure of the middle row), on the camera clock. Returns false if
    /// the pair was dropped (out of order).
    pub fn push_frames(&self, t_ns: i64, cam0: (&[u8], u32, u32), cam1: (&[u8], u32, u32)) -> bool {
        let t = t_ns + self.offset_ns;
        let mut inputs = self.inputs.lock().unwrap_or_else(|e| e.into_inner());
        if self.stopped.load(Ordering::Acquire) || t <= inputs.last_frame_ns {
            return false;
        }
        if inputs.last_frame_ns == i64::MIN {
            // The first frame: readings from long before it are no use.
            inputs.pending.retain(|s| s.timestamp >= t - IMU_LEAD_NS);
        }
        for (index, (luma, width, height)) in [cam0, cam1].into_iter().enumerate() {
            if luma.len() < (width * height) as usize {
                return false;
            }
            let sample = VitImgSample {
                cam_index: index as u32,
                timestamp: t,
                data: luma.as_ptr(),
                width,
                height,
                stride: width,
                size: width * height,
                format: VIT_IMAGE_FORMAT_L8,
                mask_count: 0,
                masks: std::ptr::null(),
            };
            // Basalt copies the image before returning.
            unsafe { (self.api.push_img)(self.tracker, &sample) };
        }
        inputs.last_frame_ns = t;
        self.flush_imu(&mut inputs);
        true
    }

    /// Poses produced since the last call, oldest first.
    pub fn poll(&self) -> Vec<Pose> {
        let mut poses = Vec::new();
        if self.stopped.load(Ordering::Acquire) {
            return poses;
        }
        loop {
            let mut pose = std::ptr::null_mut();
            if unsafe { (self.api.pop_pose)(self.tracker, &mut pose) } != VIT_SUCCESS || pose.is_null() {
                break;
            }
            let mut data = Pose::default();
            if unsafe { (self.api.pose_data)(pose, &mut data) } == VIT_SUCCESS {
                poses.push(data);
            }
            unsafe { (self.api.pose_destroy)(pose) };
        }
        poses
    }

    /// Stops the pipeline. Poses can't be polled afterwards: Basalt then queues
    /// an empty end-of-stream state that its pop function dereferences.
    pub fn stop(&self) {
        if !self.stopped.swap(true, Ordering::AcqRel) {
            unsafe { (self.api.stop)(self.tracker) };
        }
    }
}

impl Drop for Tracker {
    fn drop(&mut self) {
        self.stop();
        unsafe { (self.api.destroy)(self.tracker) };
    }
}

/// Basalt's calibration (cereal JSON, as in its `data/euroc/*_calib.json`) from
/// Marco's: lens intrinsics, `T_imu_cam` for both lenses from the measured
/// gyro-to-camera rotation and stereo pose. Mirrors `tools/marco_to_basalt.py`.
pub fn basalt_calibration(calibration: &Value) -> Result<Value, String> {
    let camera = |c: &Value| -> Result<Value, String> {
        let get = |k: &str| c[k].as_f64().ok_or(format!("camera.{k} missing"));
        let d: Vec<f64> = c["distortion"].as_array().ok_or("camera.distortion missing")?.iter().filter_map(Value::as_f64).collect();
        let base = json!({ "fx": get("fx")?, "fy": get("fy")?, "cx": get("cx")?, "cy": get("cy")? });
        let mut intrinsics = base.as_object().unwrap().clone();
        let camera_type = if c["model"] == "fisheye" {
            for (k, v) in ["k1", "k2", "k3", "k4"].iter().zip(&d) {
                intrinsics.insert(k.to_string(), json!(v));
            }
            "kb4"
        } else {
            // Vernier's pinhole is OpenCV's k1 k2 p1 p2 k3; radtan8 adds k4..k6, rpmax -1 for no limit.
            for (k, v) in ["k1", "k2", "p1", "p2", "k3"].iter().zip(&d) {
                intrinsics.insert(k.to_string(), json!(v));
            }
            for k in ["k4", "k5", "k6"] {
                intrinsics.insert(k.into(), json!(0.0));
            }
            intrinsics.insert("rpmax".into(), json!(-1.0));
            "pinhole-radtan8"
        };
        Ok(json!({ "camera_type": camera_type, "intrinsics": intrinsics }))
    };
    let quaternion = |v: &Value| -> Result<UnitQuaternion<f64>, String> {
        let q: Vec<f64> = v.as_array().ok_or("quaternion missing")?.iter().filter_map(Value::as_f64).collect();
        if q.len() != 4 {
            return Err("quaternion must have 4 values".into());
        }
        Ok(UnitQuaternion::from_quaternion(Quaternion::new(q[3], q[0], q[1], q[2])))
    };
    let pose = |q: &UnitQuaternion<f64>, p: &Vector3<f64>| {
        let q = q.as_ref();
        json!({ "px": p.x, "py": p.y, "pz": p.z, "qx": q.i, "qy": q.j, "qz": q.k, "qw": q.w })
    };
    let size = |c: &Value| json!([c["width"], c["height"]]);

    let imu = &calibration["imu"];
    if imu.is_null() {
        return Err("the calibration has no gyroscope alignment".into());
    }
    let second = &calibration["second"];
    let stereo = &calibration["stereo"];
    if second.is_null() || stereo.is_null() || stereo["translation_m"].is_null() {
        return Err("Basalt needs a stereo calibration (both lenses, with a metric baseline)".into());
    }
    // Marco gives the rotation taking gyro axes to camera axes; Basalt wants the camera's pose in
    // the IMU frame. The lever arm (a cm or two) isn't measured.
    let imu_cam0 = quaternion(&imu["imu_to_camera_xyzw"])?.inverse();
    // Vernier: x_cam1 = R x_cam0 + t, so T_cam0_cam1 = (Rᵀ, −Rᵀ t) and T_imu_cam1 = T_imu_cam0 · T_cam0_cam1.
    let r = quaternion(&stereo["second_from_first_xyzw"])?;
    let t: Vec<f64> = stereo["translation_m"].as_array().unwrap().iter().filter_map(Value::as_f64).collect();
    let r_inv = r.inverse();
    let cam0_cam1_p = -(r_inv * Vector3::new(t[0], t[1], t[2]));
    let imu_cam1_q = imu_cam0 * r_inv;
    let imu_cam1_p = imu_cam0 * cam0_cam1_p;
    let identity = json!({ "px": 0.0, "py": 0.0, "pz": 0.0, "qx": 0.0, "qy": 0.0, "qz": 0.0, "qw": 1.0 });
    Ok(json!({ "value0": {
        "T_imu_cam": [pose(&imu_cam0, &Vector3::zeros()), pose(&imu_cam1_q, &imu_cam1_p)],
        "intrinsics": [camera(&calibration["camera"])?, camera(&second["camera"])?],
        "resolution": [size(&calibration["camera"]), size(&second["camera"])],
        "calib_accel_bias": vec![0.0; 9],
        "calib_gyro_bias": vec![0.0; 12],
        "imu_update_rate": 200.0,
        // A phone's MEMS IMU, noisier than EuRoC's.
        "accel_noise_std": [0.02, 0.02, 0.02],
        "gyro_noise_std": [0.002, 0.002, 0.002],
        "accel_bias_std": [0.001, 0.001, 0.001],
        "gyro_bias_std": [0.0001, 0.0001, 0.0001],
        "T_mocap_world": identity,
        "T_imu_marker": identity,
        "mocap_time_offset_ns": 0,
        "mocap_to_imu_offset_ns": 0,
        // Applied to the frames by the tracker.
        "cam_time_offset_ns": 0,
        "vignette": [],
    }}))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The same calibration file the Python converter writes for a real stereo calibration.
    #[test]
    fn matches_the_converter() {
        let calibration = json!({
            "camera": { "model": "pinhole", "width": 640, "height": 480, "fx": 253.3, "fy": 253.2, "cx": 322.0, "cy": 239.3,
                        "distortion": [0.0248, -0.0353, 0.0006, 0.0004, 0.0131] },
            "imu": { "imu_to_camera_xyzw": [-0.703, 0.711, -0.0045, 0.0059], "time_offset_ms": -6.45 },
            "second": { "camera": { "model": "pinhole", "width": 320, "height": 240, "fx": 238.8, "fy": 238.7, "cx": 157.8, "cy": 121.4,
                                     "distortion": [0.073, -0.098, 0.0017, -0.0006, -0.0053] } },
            "stereo": { "second_from_first_xyzw": [0.0, 0.0023, -0.0019, 1.0], "translation_m": [0.0006, 0.0131, -0.0012] },
        });
        let basalt = basalt_calibration(&calibration).unwrap();
        let v = &basalt["value0"];
        assert_eq!(v["intrinsics"][0]["camera_type"], "pinhole-radtan8");
        assert_eq!(v["resolution"][1], json!([320, 240]));
        // cam1 sits 13 mm from cam0, along the IMU-frame direction the stereo pose says.
        let p = &v["T_imu_cam"][1];
        let offset = Vector3::new(p["px"].as_f64().unwrap(), p["py"].as_f64().unwrap(), p["pz"].as_f64().unwrap());
        assert!((offset.norm() - 0.01316).abs() < 1e-4, "{offset}");
        // Rotations compose: cam1's orientation differs from cam0's by the stereo rotation.
        let q = |p: &Value| UnitQuaternion::from_quaternion(Quaternion::new(
            p["qw"].as_f64().unwrap(), p["qx"].as_f64().unwrap(), p["qy"].as_f64().unwrap(), p["qz"].as_f64().unwrap()));
        let between = (q(&v["T_imu_cam"][0]).inverse() * q(p)).angle().to_degrees();
        assert!((between - 0.34).abs() < 0.05, "{between}°");
    }

    #[test]
    fn needs_a_stereo_calibration() {
        let mono = json!({ "camera": {}, "imu": { "imu_to_camera_xyzw": [0, 0, 0, 1] } });
        assert!(basalt_calibration(&mono).is_err());
    }

    /// Drives the real Basalt through this bridge with a Marco stereo recording,
    /// as the app does: checks the C structs line up and poses come out.
    ///
    ///   MARCO_RECORDING=<recording dir> LD_LIBRARY_PATH=<dir with libbasalt.so> \
    ///     cargo test --release tracks_a_recording -- --ignored --nocapture
    #[test]
    #[ignore]
    fn tracks_a_recording() {
        let dir = std::path::PathBuf::from(std::env::var("MARCO_RECORDING").expect("MARCO_RECORDING"));
        let csv = |name: &str| -> Vec<Vec<String>> {
            std::fs::read_to_string(dir.join(name)).unwrap().lines().skip(1)
                .map(|l| l.split(',').map(String::from).collect()).collect()
        };
        let meta: Value = serde_json::from_str(&std::fs::read_to_string(dir.join("meta.json")).unwrap()).unwrap();
        let sizes = [
            (meta["camera"]["width"].as_u64().unwrap() as u32, meta["camera"]["height"].as_u64().unwrap() as u32),
            (meta["second"]["width"].as_u64().unwrap() as u32, meta["second"]["height"].as_u64().unwrap() as u32),
        ];
        let frames: Vec<Vec<u8>> = ["frames.y8", "frames1.y8"].iter().map(|f| std::fs::read(dir.join(f)).unwrap()).collect();
        let index = |name: &str| -> std::collections::HashMap<i64, usize> {
            csv(name).iter().map(|r| (r[1].parse().unwrap(), r[0].parse().unwrap())).collect()
        };
        let (index0, index1) = (index("frames.csv"), index("frames1.csv"));
        let info: std::collections::HashMap<i64, (i64, i64)> = csv("capture.csv").iter()
            .map(|r| (r[0].parse().unwrap(), (r[1].parse().unwrap_or(0), r[2].parse().unwrap_or(0)))).collect();
        let mut times: Vec<i64> = index0.keys().filter(|t| index1.contains_key(t)).copied().collect();
        times.sort();
        // IMU: gyro with the latest accelerometer reading, as the app pairs them.
        let mut accel = [0f32; 3];
        let mut imu = Vec::new();
        for r in csv("imu.csv") {
            let v = [r[2].parse().unwrap(), r[3].parse().unwrap(), r[4].parse().unwrap()];
            if r[1] == "a" { accel = v } else { imu.push((r[0].parse::<i64>().unwrap(), accel, v)) }
        }

        let calibration = std::fs::read_to_string(dir.join("calibration.json")).unwrap();
        let work = std::env::temp_dir().join("marco-tracker-test");
        let tracker = Tracker::start(&calibration, &work).expect("starts");
        let started = std::time::Instant::now();
        let mut poses = Vec::new();
        let mut next_imu = 0;
        for t in &times {
            let (exposure, skew) = info.get(t).copied().unwrap_or((0, 0));
            let moment = t + exposure / 2 + skew / 2;
            // Live, readings arrive continuously; Basalt needs those just past each frame.
            while next_imu < imu.len() && imu[next_imu].0 <= moment + tracker.offset_ns + 20_000_000 {
                tracker.push_imu(imu[next_imu].0, imu[next_imu].1, imu[next_imu].2);
                next_imu += 1;
            }
            let frame = |lens: usize, i: usize| {
                let (w, h) = sizes[lens];
                &frames[lens][i * (w * h) as usize..(i + 1) * (w * h) as usize]
            };
            assert!(tracker.push_frames(moment, (frame(0, index0[t]), sizes[0].0, sizes[0].1), (frame(1, index1[t]), sizes[1].0, sizes[1].1)));
            poses.extend(tracker.poll());
            // Real-time pace, as from a camera.
            std::thread::sleep(std::time::Duration::from_millis(30));
        }
        for _ in 0..100 {
            if poses.len() >= times.len() { break; }
            std::thread::sleep(std::time::Duration::from_millis(20));
            poses.extend(tracker.poll());
        }
        tracker.stop();
        let path: f32 = poses.windows(2).map(|w| ((w[1].px - w[0].px).powi(2) + (w[1].py - w[0].py).powi(2) + (w[1].pz - w[0].pz).powi(2)).sqrt()).sum();
        let (a, b) = (poses.first().unwrap(), poses.last().unwrap());
        let closing = ((b.px - a.px).powi(2) + (b.py - a.py).powi(2) + (b.pz - a.pz).powi(2)).sqrt();
        eprintln!("{} poses for {} frames in {:.1} s, path {path:.2} m, start to end {closing:.2} m", poses.len(), times.len(), started.elapsed().as_secs_f64());
        assert!(poses.len() > times.len() * 9 / 10);
    }
}
