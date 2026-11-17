// Builds the native Vernier bridge (../vision) into the app when local.properties
// has `marco.vision=true`. vernier-rs is private until it is relicensed, so CI and
// fresh clones build without it, and the camera tools report themselves unavailable.
//
// Needs Rust with the aarch64-linux-android target, and an NDK under the SDK.

import java.util.Properties

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
val visionEnabled = localProperties.getProperty("marco.vision") == "true"
val visionDir = rootProject.file("vision")
val visionLib = file("src/main/jniLibs/arm64-v8a/libmarco_vision.so")

if (visionEnabled) {
    val sdkDir = localProperties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    val ndkDir = File(sdkDir ?: "", "ndk").listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { dir -> dir.name.split('.').map { it.toIntOrNull() ?: 0 }.fold(0L) { acc, part -> acc * 100_000 + part } }
        ?: error("marco.vision=true needs an NDK in $sdkDir/ndk")
    val host = if (System.getProperty("os.name").lowercase().contains("mac")) "darwin-x86_64" else "linux-x86_64"
    val toolchain = File(ndkDir, "toolchains/llvm/prebuilt/$host/bin")
    val clang = File(toolchain, "aarch64-linux-android31-clang").path
    // Android Studio doesn't always inherit the shell's PATH.
    val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo").takeIf { it.exists() }?.path ?: "cargo"

    val cargoBuildVision by tasks.registering(Exec::class) {
        description = "Builds libmarco_vision.so for arm64 phones."
        workingDir = visionDir
        commandLine(cargo, "build", "--release", "--target", "aarch64-linux-android")
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", clang)
        environment("CC_aarch64_linux_android", clang)
        environment("AR_aarch64_linux_android", File(toolchain, "llvm-ar").path)
        inputs.dir(File(visionDir, "src"))
        inputs.files(File(visionDir, "Cargo.toml"), File(visionDir, "Cargo.lock"))
        outputs.file(File(visionDir, "target/aarch64-linux-android/release/libmarco_vision.so"))
    }
    val copyVision by tasks.registering(Copy::class) {
        from(cargoBuildVision.map { it.outputs.files })
        // Basalt for live tracking, if vision/basalt-android/build.sh has been run (or
        // marco.basalt.dir points at its work dir): loaded at run time, optional.
        val basaltLib = File(localProperties.getProperty("marco.basalt.dir") ?: "${visionDir.path}/basalt-android/build", "prefix/android")
        from(basaltLib) { include("libbasalt.so", "libtbb.so", "libc++_shared.so") }
        into(visionLib.parentFile)
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyVision) }
} else {
    // Don't ship libraries left over from an earlier build with vision on.
    val removeVision by tasks.registering(Delete::class) {
        delete(visionLib)
        listOf("libbasalt.so", "libtbb.so", "libc++_shared.so").forEach { delete(File(visionLib.parentFile, it)) }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(removeVision) }
}
