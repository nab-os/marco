// Runs Basalt on a dataset written by marco_to_basalt.py, through the same VIT
// C interface the phone will use, and writes the trajectory.
//
//   basalt_runner DATASET_DIR CONFIG_JSON TRAJECTORY_CSV [live]
//
// DATASET_DIR holds mav0/cam0, mav0/cam1, mav0/imu0 and calib.json. All frames
// are decoded into memory first, so the timing is Basalt's alone. By default
// Basalt runs deterministically, as fast as it can, and a run gives the same
// trajectory every time. With `live`, it runs pipelined as on the phone and
// frames are fed at the pace they were recorded: the latency from pushing a
// frame to getting its pose says whether the device keeps up.

// The header declares the functions only for implementations; we link to one directly.
#define VIT_INTERFACE_IMPLEMENTATION
#include <vit_interface.h>

#include <opencv2/imgcodecs.hpp>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <map>
#include <thread>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct Imu {
  int64_t t;
  float w[3], a[3];
};

struct Frame {
  int64_t t;
  std::string file;
};

std::vector<Imu> read_imu(const std::string &path) {
  std::vector<Imu> out;
  std::ifstream in(path);
  std::string line;
  while (std::getline(in, line)) {
    if (line.empty() || line[0] == '#') continue;
    std::replace(line.begin(), line.end(), ',', ' ');
    std::istringstream row(line);
    Imu s;
    row >> s.t >> s.w[0] >> s.w[1] >> s.w[2] >> s.a[0] >> s.a[1] >> s.a[2];
    if (row) out.push_back(s);
  }
  return out;
}

std::vector<Frame> read_frames(const std::string &dir) {
  std::vector<Frame> out;
  std::ifstream in(dir + "/data.csv");
  std::string line;
  while (std::getline(in, line)) {
    if (line.empty() || line[0] == '#') continue;
    auto comma = line.find(',');
    out.push_back({std::stoll(line.substr(0, comma)), dir + "/data/" + line.substr(comma + 1)});
  }
  return out;
}

bool check(vit_result_t result, const char *what) {
  if (result != VIT_SUCCESS) std::cerr << what << " failed: " << result << "\n";
  return result == VIT_SUCCESS;
}

}  // namespace

int main(int argc, char **argv) {
  if (argc != 4 && !(argc == 5 && std::string(argv[4]) == "live")) {
    std::cerr << "usage: basalt_runner DATASET_DIR CONFIG_JSON TRAJECTORY_CSV [live]\n";
    return 2;
  }
  const std::string dataset = argv[1], config = argv[2], trajectory = argv[3];
  const bool live = argc == 5;
  using Clock = std::chrono::steady_clock;
  auto seconds = [](Clock::duration d) { return std::chrono::duration<double>(d).count(); };

  auto imu = read_imu(dataset + "/mav0/imu0/data.csv");
  std::vector<std::vector<Frame>> cams = {read_frames(dataset + "/mav0/cam0"), read_frames(dataset + "/mav0/cam1")};
  if (cams[0].empty() || cams[0].size() != cams[1].size()) {
    std::cerr << "need stereo frames: cam0 has " << cams[0].size() << ", cam1 " << cams[1].size() << "\n";
    return 1;
  }
  std::cout << imu.size() << " IMU samples, " << cams[0].size() << " stereo frames\n";

  const auto decode_start = Clock::now();
  std::vector<std::vector<cv::Mat>> images(2);
  for (uint32_t cam = 0; cam < 2; cam++) {
    for (const auto &frame : cams[cam]) {
      images[cam].push_back(cv::imread(frame.file, cv::IMREAD_GRAYSCALE));
      if (images[cam].back().empty()) {
        std::cerr << "can't read " << frame.file << "\n";
        return 1;
      }
    }
  }
  std::printf("decoded in %.1f s\n", seconds(Clock::now() - decode_start));

  // Basalt's unified config: where its calibration and VIO settings are.
  const std::string unified = trajectory + ".toml";
  {
    std::ofstream toml(unified);
    toml << "show-gui=0\n"
         << "cam-calib=\"" << dataset << "/calib.json\"\n"
         << "config-path=\"" << config << "\"\n"
         << "deterministic=" << (live ? 0 : 1) << "\n"
         << "num-threads=0\n";
  }

  vit_config_t vit_config{unified.c_str(), 2, 1, false};
  vit_tracker_t *tracker = nullptr;
  if (!check(vit_tracker_create(&vit_config, &tracker), "vit_tracker_create")) return 1;
  // Features per pose: how many each camera tracks, and how deep Basalt thinks they are.
  check(vit_tracker_enable_extension(tracker, VIT_TRACKER_EXTENSION_POSE_FEATURES, true), "enable pose features");
  if (!check(vit_tracker_start(tracker), "vit_tracker_start")) return 1;
  int64_t next_report = 0;

  std::ofstream out(trajectory);
  out << "#t_ns,px,py,pz,qx,qy,qz,qw\n";
  std::vector<vit_pose_data_t> poses;
  std::map<int64_t, Clock::time_point> pushed;  // frame timestamp → when it was pushed
  std::vector<double> latencies;                // seconds, frame push to pose out
  auto drain = [&] {
    vit_pose_t *pose = nullptr;
    while (vit_tracker_pop_pose(tracker, &pose) == VIT_SUCCESS && pose != nullptr) {
      vit_pose_data_t d;
      if (vit_pose_get_data(pose, &d) == VIT_SUCCESS) {
        poses.push_back(d);
        if (auto it = pushed.find(d.timestamp); it != pushed.end()) latencies.push_back(seconds(Clock::now() - it->second));
        if (d.timestamp >= next_report) {
          next_report = d.timestamp + 1'000'000'000;
          std::printf("t=%6.1f s  pos (%+.2f %+.2f %+.2f)", (d.timestamp - poses.front().timestamp) / 1e9, d.px, d.py, d.pz);
          for (uint32_t cam = 0; cam < 2; cam++) {
            vit_pose_features_t features{};
            if (vit_pose_get_features(pose, cam, &features) != VIT_SUCCESS) continue;
            std::vector<float> depths;
            for (uint32_t i = 0; i < features.count; i++)
              if (features.features[i].depth > 0) depths.push_back(features.features[i].depth);
            std::sort(depths.begin(), depths.end());
            std::printf("  cam%u %3u features, %3zu with depth (median %.2f m)", cam, features.count, depths.size(),
                        depths.empty() ? 0.0 : depths[depths.size() / 2]);
          }
          std::printf("\n");
        }
        out << d.timestamp << ',' << d.px << ',' << d.py << ',' << d.pz << ',' << d.ox << ',' << d.oy << ',' << d.oz
            << ',' << d.ow << '\n';
      }
      vit_pose_destroy(pose);
      pose = nullptr;
    }
  };

  size_t next_imu = 0;
  const auto run_start = Clock::now();
  for (size_t f = 0; f < cams[0].size(); f++) {
    const int64_t t = cams[0][f].t;
    if (live) {
      // As a camera would: this frame no sooner than its time after the first.
      std::this_thread::sleep_until(run_start + std::chrono::nanoseconds(t - cams[0][0].t));
    }
    // Basalt integrates IMU up to the first sample after a frame, and in deterministic mode waits
    // for each frame to come out: give it the samples just past the frame first.
    for (; next_imu < imu.size() && imu[next_imu].t <= t + 20000000; next_imu++) {
      const Imu &s = imu[next_imu];
      vit_imu_sample_t sample{s.t, s.a[0], s.a[1], s.a[2], s.w[0], s.w[1], s.w[2]};
      vit_tracker_push_imu_sample(tracker, &sample);
    }
    pushed[t] = Clock::now();
    for (uint32_t cam = 0; cam < 2; cam++) {
      const cv::Mat &image = images[cam][f];
      vit_img_sample_t sample{};
      sample.cam_index = cam;
      sample.timestamp = cams[cam][f].t;
      sample.data = image.data;
      sample.width = image.cols;
      sample.height = image.rows;
      sample.stride = image.step;
      sample.size = image.step * image.rows;
      sample.format = VIT_IMAGE_FORMAT_L8;
      vit_tracker_push_img_sample(tracker, &sample);
    }
    drain();
  }
  if (live) {
    // Let the pipeline finish what it has queued, then stop it.
    for (int i = 0; i < 300 && poses.size() < cams[0].size(); i++) {
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
      drain();
    }
  }
  if (!live) drain();
  // Not after stopping: the pipeline then queues an empty end-of-stream state that
  // vit_tracker_pop_pose dereferences (crashes in live mode).
  vit_tracker_stop(tracker);
  vit_tracker_destroy(tracker);
  const double elapsed = seconds(Clock::now() - run_start);
  const double recorded = (cams[0].back().t - cams[0].front().t) / 1e9;
  std::printf("tracking took %.1f s for %.1f s of frames (%.2fx real time)\n", elapsed, recorded, recorded / elapsed);
  if (!latencies.empty()) {
    auto sorted = latencies;
    std::sort(sorted.begin(), sorted.end());
    auto at = [&](double q) { return sorted[std::min(sorted.size() - 1, size_t(q * sorted.size()))] * 1000; };
    const size_t tenth = std::max<size_t>(1, latencies.size() / 10);
    double first = 0, last = 0;
    for (size_t i = 0; i < tenth; i++) {
      first += latencies[i];
      last += latencies[latencies.size() - 1 - i];
    }
    std::printf("latency push→pose: median %.0f ms, 95%% %.0f ms; first tenth %.0f ms, last tenth %.0f ms\n", at(0.5),
                at(0.95), first / tenth * 1000, last / tenth * 1000);
  }

  if (poses.empty()) {
    std::cerr << "no poses\n";
    return 1;
  }
  double path = 0;
  for (size_t i = 1; i < poses.size(); i++)
    path += std::hypot(std::hypot(poses[i].px - poses[i - 1].px, poses[i].py - poses[i - 1].py), poses[i].pz - poses[i - 1].pz);
  const auto &a = poses.front(), &b = poses.back();
  std::printf("%zu poses over %.1f s, path %.2f m, start to end %.2f m\n", poses.size(), (b.timestamp - a.timestamp) / 1e9,
              path, std::hypot(std::hypot(b.px - a.px, b.py - a.py), b.pz - a.pz));
  return 0;
}
