//! Camera-to-gyroscope alignment: the rotation between the camera and the
//! gyroscope, the delay between their clocks, and the gyroscope's bias.
//!
//! The phone turns in front of the board. Between two frames whose pose
//! Vernier measured, the camera turned by `ΔR_c`; over the same interval the
//! gyroscope, integrated, turned by `ΔR_i`. Both are the same motion seen from
//! two frames, `ΔR_c = R · ΔR_i · Rᵀ` with `R` taking gyroscope axes to
//! camera axes, so their rotation vectors obey `v_c = R · v_i`. Pairs of frames
//! rather than consecutive frames: measuring a frame takes a good fraction of
//! a second on a phone, too slow to differentiate poses into rates.

use nalgebra::{Matrix3, Rotation3, UnitQuaternion, Vector3};
use serde::Serialize;

/// One gyroscope reading: rad/s about the phone's axes, at `t_ns`.
#[derive(Clone, Copy, Debug)]
pub struct GyroSample {
    pub t_ns: i64,
    pub rate: Vector3<f64>,
}

/// The camera's orientation relative to the board at `t_ns`: takes camera
/// axes to board axes.
#[derive(Clone, Copy, Debug)]
pub struct CameraOrientation {
    pub t_ns: i64,
    pub camera_to_board: Rotation3<f64>,
}

#[derive(Clone, Debug, Serialize)]
pub struct CameraImu {
    /// Takes gyroscope (phone) axes to camera axes, `x, y, z, w`.
    pub imu_to_camera_xyzw: [f64; 4],
    /// The gyroscope stamps a moment `time_offset_ms` later than the camera
    /// does: `t_imu = t_camera + time_offset_ms`.
    pub time_offset_ms: f64,
    /// rad/s, already subtracted from the gyroscope's readings.
    pub gyro_bias: [f64; 3],
    /// Angle between `imu_to_camera` and the nearest axis-aligned mounting,
    /// degrees: how far off square the camera sits.
    pub off_axis_deg: f64,
    /// Over the pairs used, degrees.
    pub rms_deg: f64,
    pub pairs: usize,
    /// How evenly the motion turned about each camera axis, 0..1: the
    /// smallest over the largest spread. Small means one axis was barely
    /// turned about, and the rotation about it is poorly known.
    pub axis_coverage: f64,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum ImuError {
    TooFewPairs {
        pairs: usize,
        poses: usize,
        /// Median time between consecutive poses, seconds.
        median_gap_s: f64,
    },
    NoGyro,
}

impl std::fmt::Display for ImuError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::TooFewPairs { pairs, poses, median_gap_s } => write!(
                f,
                "{pairs} usable frame pairs from {poses} poses {median_gap_s:.1} s apart (pairs need \
                 {PAIR_MIN_S}–{PAIR_MAX_S} s and {PAIR_MIN_DEG}° of turn); turn the phone more while \
                 keeping the board in view"
            ),
            Self::NoGyro => write!(f, "no gyroscope readings over the frames"),
        }
    }
}

impl std::error::Error for ImuError {}

/// Pairs closer than this in time say little; further, the gyro has drifted.
const PAIR_MIN_S: f64 = 0.1;
/// Generous: measuring a frame can take a second or more on a phone, and the
/// bias is estimated, so the gyro's drift over a few seconds stays well below
/// what the poses resolve.
const PAIR_MAX_S: f64 = 6.0;
/// Pairs that barely turned only add noise to the axis.
const PAIR_MIN_DEG: f64 = 3.0;
const MIN_PAIRS: usize = 15;
/// Clock offsets searched, milliseconds either way.
const SEARCH_MS: f64 = 150.0;

pub fn align(orientations: &[CameraOrientation], gyro: &[GyroSample]) -> Result<CameraImu, ImuError> {
    if gyro.len() < 2 {
        return Err(ImuError::NoGyro);
    }
    let mut frames = orientations.to_vec();
    frames.sort_by_key(|frame| frame.t_ns);
    let pairs = pairs(&frames);
    if pairs.len() < MIN_PAIRS {
        let mut gaps: Vec<f64> = frames.windows(2).map(|w| (w[1].t_ns - w[0].t_ns) as f64 * 1e-9).collect();
        gaps.sort_by(f64::total_cmp);
        return Err(ImuError::TooFewPairs {
            pairs: pairs.len(),
            poses: frames.len(),
            median_gap_s: gaps.get(gaps.len() / 2).copied().unwrap_or(0.0),
        });
    }

    let mut bias = Vector3::zeros();
    let mut offset_s = coarse_offset(&pairs, &Integrated::new(gyro, &bias))?;
    for _ in 0..3 {
        let integrated = Integrated::new(gyro, &bias);
        offset_s = golden(offset_s - 0.003, offset_s + 0.003, |offset| {
            fit(&pairs, &integrated, offset).map_or(f64::INFINITY, |(_, cost)| cost)
        });
        let (rotation, _) = fit(&pairs, &integrated, offset_s).ok_or(ImuError::NoGyro)?;
        bias += bias_step(&pairs, &integrated, offset_s, &rotation);
    }
    let integrated = Integrated::new(gyro, &bias);
    let (rotation, cost) = fit(&pairs, &integrated, offset_s).ok_or(ImuError::NoGyro)?;
    let used = pairs.iter().filter(|pair| integrated.covers(pair, offset_s)).count();

    let quaternion = UnitQuaternion::from_rotation_matrix(&rotation);
    let q = quaternion.as_ref();
    Ok(CameraImu {
        imu_to_camera_xyzw: [q.i, q.j, q.k, q.w],
        time_offset_ms: offset_s * 1000.0,
        gyro_bias: [bias.x, bias.y, bias.z],
        off_axis_deg: off_axis(&rotation).to_degrees(),
        rms_deg: (cost / used.max(1) as f64).sqrt().to_degrees(),
        pairs: used,
        axis_coverage: axis_coverage(&pairs),
    })
}

/// Two measured frames and how the camera turned between them.
struct Pair {
    t0_s: f64,
    t1_s: f64,
    /// Rotation vector of the camera's turn, in camera axes at the first frame.
    camera: Vector3<f64>,
}

fn pairs(frames: &[CameraOrientation]) -> Vec<Pair> {
    let mut pairs = Vec::new();
    for (i, first) in frames.iter().enumerate() {
        for second in &frames[i + 1..] {
            let dt = (second.t_ns - first.t_ns) as f64 * 1e-9;
            if dt > PAIR_MAX_S {
                break;
            }
            if dt < PAIR_MIN_S {
                continue;
            }
            let turn = first.camera_to_board.inverse() * second.camera_to_board;
            let camera = turn.scaled_axis();
            if camera.norm().to_degrees() >= PAIR_MIN_DEG {
                pairs.push(Pair {
                    t0_s: first.t_ns as f64 * 1e-9,
                    t1_s: second.t_ns as f64 * 1e-9,
                    camera,
                });
            }
        }
    }
    pairs
}

/// The gyroscope's readings integrated once, so that its turn between any two
/// moments is a lookup.
struct Integrated {
    times_s: Vec<f64>,
    /// Orientation at each reading, relative to the first.
    orientations: Vec<UnitQuaternion<f64>>,
}

impl Integrated {
    fn new(gyro: &[GyroSample], bias: &Vector3<f64>) -> Self {
        let mut times_s = Vec::with_capacity(gyro.len());
        let mut orientations = Vec::with_capacity(gyro.len());
        let mut orientation = UnitQuaternion::identity();
        for (index, sample) in gyro.iter().enumerate() {
            if index > 0 {
                let previous = &gyro[index - 1];
                let dt = (sample.t_ns - previous.t_ns) as f64 * 1e-9;
                // Midpoint rate over the step, in the body frame.
                let rate = (previous.rate + sample.rate) * 0.5 - bias;
                orientation *= UnitQuaternion::from_scaled_axis(rate * dt);
            }
            times_s.push(sample.t_ns as f64 * 1e-9);
            orientations.push(orientation);
        }
        Self { times_s, orientations }
    }

    fn at(&self, t_s: f64) -> Option<UnitQuaternion<f64>> {
        let after = self.times_s.partition_point(|&time| time < t_s);
        if after == 0 || after >= self.times_s.len() {
            return None;
        }
        let (t0, t1) = (self.times_s[after - 1], self.times_s[after]);
        let fraction = (t_s - t0) / (t1 - t0);
        Some(self.orientations[after - 1].slerp(&self.orientations[after], fraction))
    }

    /// Rotation vector of the gyroscope's turn over a pair, in gyro axes.
    fn turn(&self, pair: &Pair, offset_s: f64) -> Option<Vector3<f64>> {
        let start = self.at(pair.t0_s + offset_s)?;
        let end = self.at(pair.t1_s + offset_s)?;
        Some((start.inverse() * end).scaled_axis())
    }

    fn covers(&self, pair: &Pair, offset_s: f64) -> bool {
        self.turn(pair, offset_s).is_some()
    }
}

/// The offset that best matches how far each pair turned. Turn angles don't
/// depend on the axes, so this needs no rotation yet.
fn coarse_offset(pairs: &[Pair], integrated: &Integrated) -> Result<f64, ImuError> {
    let mut best = (f64::INFINITY, 0.0);
    let steps = (SEARCH_MS * 2.0) as i32;
    for step in 0..=steps {
        let offset_s = (step as f64 - SEARCH_MS) * 1e-3;
        let (mut cost, mut count) = (0.0, 0);
        for pair in pairs {
            if let Some(gyro) = integrated.turn(pair, offset_s) {
                cost += (pair.camera.norm() - gyro.norm()).powi(2);
                count += 1;
            }
        }
        // Offsets that push pairs off the end of the gyro record don't get to
        // win by having fewer pairs to fit.
        if count * 10 >= pairs.len() * 9 && cost / (count as f64) < best.0 {
            best = (cost / count as f64, offset_s);
        }
    }
    if best.0.is_finite() { Ok(best.1) } else { Err(ImuError::NoGyro) }
}

/// The rotation taking gyro turns onto camera turns at `offset_s` (Wahba's
/// problem, by SVD), and its summed squared residual.
fn fit(pairs: &[Pair], integrated: &Integrated, offset_s: f64) -> Option<(Rotation3<f64>, f64)> {
    let matched: Vec<_> = pairs
        .iter()
        .filter_map(|pair| integrated.turn(pair, offset_s).map(|gyro| (pair.camera, gyro)))
        .collect();
    if matched.len() < 3 {
        return None;
    }
    let mut correlation = Matrix3::zeros();
    for (camera, gyro) in &matched {
        correlation += camera * gyro.transpose();
    }
    let svd = correlation.svd(true, true);
    let (u, v_t) = (svd.u?, svd.v_t?);
    let mut fix = Matrix3::identity();
    fix[(2, 2)] = (u * v_t).determinant().signum();
    let rotation = Rotation3::from_matrix_unchecked(u * fix * v_t);
    let cost = matched.iter().map(|(camera, gyro)| (camera - rotation * gyro).norm_squared()).sum();
    Some((rotation, cost))
}

/// Least-squares correction to the bias: a constant bias `b` adds `b·Δt` to
/// every gyro turn, to first order.
fn bias_step(pairs: &[Pair], integrated: &Integrated, offset_s: f64, rotation: &Rotation3<f64>) -> Vector3<f64> {
    let (mut sum, mut weight) = (Vector3::zeros(), 0.0);
    for pair in pairs {
        if let Some(gyro) = integrated.turn(pair, offset_s) {
            let dt = pair.t1_s - pair.t0_s;
            sum += (gyro - rotation.inverse() * pair.camera) * dt;
            weight += dt * dt;
        }
    }
    if weight > 0.0 { sum / weight } else { Vector3::zeros() }
}

/// Golden-section search for the minimum of `cost` on `[low, high]`.
fn golden(mut low: f64, mut high: f64, cost: impl Fn(f64) -> f64) -> f64 {
    let ratio = (5f64.sqrt() - 1.0) / 2.0;
    let mut a = high - ratio * (high - low);
    let mut b = low + ratio * (high - low);
    let (mut cost_a, mut cost_b) = (cost(a), cost(b));
    while high - low > 1e-5 {
        if cost_a < cost_b {
            high = b;
            b = a;
            cost_b = cost_a;
            a = high - ratio * (high - low);
            cost_a = cost(a);
        } else {
            low = a;
            a = b;
            cost_a = cost_b;
            b = low + ratio * (high - low);
            cost_b = cost(b);
        }
    }
    (low + high) / 2.0
}

/// Angle to the nearest of the 24 rotations that map axes onto axes.
fn off_axis(rotation: &Rotation3<f64>) -> f64 {
    let m = rotation.matrix();
    let mut best = f64::INFINITY;
    for permutation in [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]] {
        for signs in 0..8 {
            let mut square = Matrix3::zeros();
            for (row, &column) in permutation.iter().enumerate() {
                square[(row, column)] = if signs >> row & 1 == 1 { -1.0 } else { 1.0 };
            }
            if square.determinant() < 0.0 {
                continue;
            }
            let between = Rotation3::from_matrix_unchecked(square.transpose() * m);
            best = best.min(between.angle());
        }
    }
    best
}

fn axis_coverage(pairs: &[Pair]) -> f64 {
    let mut scatter = Matrix3::zeros();
    for pair in pairs {
        scatter += pair.camera * pair.camera.transpose();
    }
    let eigen = scatter.symmetric_eigenvalues();
    let (low, high) = (eigen.min(), eigen.max());
    if high > 0.0 { (low / high).sqrt() } else { 0.0 }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::PI;

    /// A smooth wobble about every axis, in gyro axes, rad/s.
    fn rate_at(t: f64) -> Vector3<f64> {
        Vector3::new(
            0.9 * (2.0 * PI * 0.31 * t).sin() + 0.3 * (2.0 * PI * 1.1 * t + 0.4).sin(),
            0.7 * (2.0 * PI * 0.23 * t + 1.0).sin() + 0.25 * (2.0 * PI * 0.9 * t).sin(),
            0.6 * (2.0 * PI * 0.17 * t + 2.0).sin() + 0.2 * (2.0 * PI * 1.3 * t + 0.7).sin(),
        )
    }

    struct Truth {
        imu_to_camera: Rotation3<f64>,
        offset_s: f64,
        bias: Vector3<f64>,
    }

    /// Camera poses every ~0.65 s with 0.05° of noise, and 200 Hz gyro
    /// readings stamped `offset_s` late, biased and noisy.
    fn simulate(truth: &Truth, seconds: f64) -> (Vec<CameraOrientation>, Vec<GyroSample>) {
        let dt = 1e-4;
        let mut imu_orientation = UnitQuaternion::identity();
        let mut true_orientations = Vec::new(); // (t, imu → world)
        let mut t = 0.0;
        while t < seconds {
            true_orientations.push((t, imu_orientation));
            imu_orientation *= UnitQuaternion::from_scaled_axis(rate_at(t + dt / 2.0) * dt);
            t += dt;
        }
        let mut seed = 7u64;
        let mut noise = move || {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            ((seed >> 11) as f64 / (1u64 << 53) as f64) * 2.0 - 1.0
        };
        let camera_to_imu = truth.imu_to_camera.inverse();
        let mut frames = Vec::new();
        let mut frame_t = 0.2;
        while frame_t < seconds - 0.2 {
            let (_, imu_to_world) = true_orientations[(frame_t / dt) as usize];
            let jitter = Rotation3::from_scaled_axis(Vector3::new(noise(), noise(), noise()) * 0.05f64.to_radians());
            frames.push(CameraOrientation {
                t_ns: (frame_t * 1e9) as i64,
                camera_to_board: imu_to_world.to_rotation_matrix() * camera_to_imu * jitter,
            });
            frame_t += 0.55 + 0.2 * (noise() + 1.0) / 2.0;
        }
        let mut gyro = Vec::new();
        let mut gyro_t = 0.0;
        while gyro_t < seconds {
            let rate = rate_at(gyro_t) + truth.bias + Vector3::new(noise(), noise(), noise()) * 0.003;
            gyro.push(GyroSample {
                t_ns: ((gyro_t + truth.offset_s) * 1e9) as i64,
                rate,
            });
            gyro_t += 0.005;
        }
        (frames, gyro)
    }

    #[test]
    fn recovers_rotation_offset_and_bias() {
        let square = Rotation3::from_matrix_unchecked(Matrix3::new(0.0, -1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, -1.0));
        let truth = Truth {
            imu_to_camera: Rotation3::from_scaled_axis(Vector3::new(0.8, -0.5, 0.3).normalize() * 1.5f64.to_radians())
                * square,
            offset_s: 0.012,
            bias: Vector3::new(0.004, -0.003, 0.002),
        };
        let (frames, gyro) = simulate(&truth, 40.0);
        let found = align(&frames, &gyro).expect("aligns");
        let q = found.imu_to_camera_xyzw;
        let rotation = UnitQuaternion::from_quaternion(nalgebra::Quaternion::new(q[3], q[0], q[1], q[2]));
        let error = rotation.to_rotation_matrix().inverse() * truth.imu_to_camera;
        assert!(error.angle().to_degrees() < 0.1, "rotation off by {}°", error.angle().to_degrees());
        assert!((found.time_offset_ms - 12.0).abs() < 1.0, "offset {} ms", found.time_offset_ms);
        assert!((found.off_axis_deg - 1.5).abs() < 0.1, "off axis {}°", found.off_axis_deg);
        let bias_error = (Vector3::from(found.gyro_bias) - truth.bias).norm();
        assert!(bias_error < 5e-4, "bias off by {bias_error} rad/s");
        assert!(found.axis_coverage > 0.3, "coverage {}", found.axis_coverage);
    }

    #[test]
    fn needs_motion() {
        let still = vec![
            CameraOrientation { t_ns: 0, camera_to_board: Rotation3::identity() },
            CameraOrientation { t_ns: 500_000_000, camera_to_board: Rotation3::identity() },
        ];
        let gyro = vec![
            GyroSample { t_ns: 0, rate: Vector3::zeros() },
            GyroSample { t_ns: 1_000_000_000, rate: Vector3::zeros() },
        ];
        assert_eq!(align(&still, &gyro).unwrap_err(), ImuError::TooFewPairs { pairs: 0, poses: 2, median_gap_s: 0.5 });
    }
}
