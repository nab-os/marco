// Runs Basalt on a dataset written by marco_to_basalt.py, through the same VIT
// C interface the phone will use, and writes the trajectory.
//
//   basalt_runner DATASET_DIR CONFIG_JSON TRAJECTORY_CSV
//
// DATASET_DIR holds mav0/cam0, mav0/cam1, mav0/imu0 and calib.json. Frames and
// IMU samples are pushed in time order; Basalt runs deterministically, so a run
// gives the same trajectory every time.

// The header declares the functions only for implementations; we link to one directly.
#define VIT_INTERFACE_IMPLEMENTATION
#include <vit_interface.h>

#include <opencv2/imgcodecs.hpp>

#include <algorithm>
#include <cmath>
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
  if (argc != 4) {
    std::cerr << "usage: basalt_runner DATASET_DIR CONFIG_JSON TRAJECTORY_CSV\n";
    return 2;
  }
  const std::string dataset = argv[1], config = argv[2], trajectory = argv[3];

  auto imu = read_imu(dataset + "/mav0/imu0/data.csv");
  std::vector<std::vector<Frame>> cams = {read_frames(dataset + "/mav0/cam0"), read_frames(dataset + "/mav0/cam1")};
  if (cams[0].empty() || cams[0].size() != cams[1].size()) {
    std::cerr << "need stereo frames: cam0 has " << cams[0].size() << ", cam1 " << cams[1].size() << "\n";
    return 1;
  }
  std::cout << imu.size() << " IMU samples, " << cams[0].size() << " stereo frames\n";

  // Basalt's unified config: where its calibration and VIO settings are.
  const std::string unified = trajectory + ".toml";
  {
    std::ofstream toml(unified);
    toml << "show-gui=0\n"
         << "cam-calib=\"" << dataset << "/calib.json\"\n"
         << "config-path=\"" << config << "\"\n"
         << "deterministic=1\n"
         << "num-threads=0\n";
  }

  vit_config_t vit_config{unified.c_str(), 2, 1, false};
  vit_tracker_t *tracker = nullptr;
  if (!check(vit_tracker_create(&vit_config, &tracker), "vit_tracker_create")) return 1;
  if (!check(vit_tracker_start(tracker), "vit_tracker_start")) return 1;

  std::ofstream out(trajectory);
  out << "#t_ns,px,py,pz,qx,qy,qz,qw\n";
  std::vector<vit_pose_data_t> poses;
  auto drain = [&] {
    vit_pose_t *pose = nullptr;
    while (vit_tracker_pop_pose(tracker, &pose) == VIT_SUCCESS && pose != nullptr) {
      vit_pose_data_t d;
      if (vit_pose_get_data(pose, &d) == VIT_SUCCESS) {
        poses.push_back(d);
        out << d.timestamp << ',' << d.px << ',' << d.py << ',' << d.pz << ',' << d.ox << ',' << d.oy << ',' << d.oz
            << ',' << d.ow << '\n';
      }
      vit_pose_destroy(pose);
      pose = nullptr;
    }
  };

  size_t next_imu = 0;
  for (size_t f = 0; f < cams[0].size(); f++) {
    const int64_t t = cams[0][f].t;
    // Basalt integrates IMU up to the first sample after a frame, and in deterministic mode waits
    // for each frame to come out: give it the samples just past the frame first.
    for (; next_imu < imu.size() && imu[next_imu].t <= t + 20000000; next_imu++) {
      const Imu &s = imu[next_imu];
      vit_imu_sample_t sample{s.t, s.a[0], s.a[1], s.a[2], s.w[0], s.w[1], s.w[2]};
      vit_tracker_push_imu_sample(tracker, &sample);
    }
    for (uint32_t cam = 0; cam < 2; cam++) {
      cv::Mat image = cv::imread(cams[cam][f].file, cv::IMREAD_GRAYSCALE);
      if (image.empty()) {
        std::cerr << "can't read " << cams[cam][f].file << "\n";
        return 1;
      }
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
  vit_tracker_stop(tracker);
  drain();
  vit_tracker_destroy(tracker);

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
