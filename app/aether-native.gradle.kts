/*
 * SPDX-FileCopyrightText: 2026 Alutube contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Builds the Aether bridge crate (`aether-bridge/`) for Android ABIs with
 * cargo-ndk and places the resulting `libaether_bridge.so` into the default
 * jniLibs source directory, so a plain `assemble<BuildType>` APK/AAB contains
 * it without any manual copy step. The bridge crate links the vendored
 * `aether` crate (path dependency), so this single `.so` carries the whole
 * engine plus the JNI glue.
 *
 * Requirements (see docs/toolchain.md):
 *   - Rust stable >= 1.98 with targets aarch64-linux-android,
 *     armv7-linux-androideabi, x86_64-linux-android
 *   - cargo-ndk
 *   - Android NDK r26d (ANDROID_NDK_HOME / ANDROID_NDK_ROOT)
 *   - cmake + C/C++ toolchain on the host (BoringSSL is built by boring-sys)
 *   - libclang (bindgen) available on the host, exposed via LIBCLANG_PATH
 *     when the host libclang is not on the default search path
 *
 * Control:
 *   ./gradlew assembleDebug                       -> builds Aether natively
 *   ./gradlew assembleDebug -PaetherNative=off    -> skips the native build
 *   ./gradlew :app:aetherBuildNative              -> build only
 */

val aetherEnabled: Boolean = providers.gradleProperty("aetherNative")
    .orElse(providers.provider { "on" })
    .map { it.equals("on", ignoreCase = true) || it.equals("true", ignoreCase = true) }
    .get()

val aetherAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val aetherBridgeDir = rootProject.projectDir.resolve("aether-bridge")
val aetherCrateDir = rootProject.projectDir.resolve("aether/aether")
val aetherJniLibsDir = project.layout.projectDirectory.dir("src/main/jniLibs")

val aetherNdkPlatform: String = providers.gradleProperty("aetherNdkPlatform")
    .orElse(providers.provider { "23" }) // match app minSdk; adjust after Phase-1 verification
    .get()

val ndkHomeProvider = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))

val aetherBuildNative by tasks.registering(Exec::class) {
    group = "aether"
    description = "Builds the Aether bridge (engine + JNI glue) for Android ABIs with cargo-ndk"
    workingDir(aetherBridgeDir)

    inputs.dir(aetherBridgeDir.resolve("src"))
    inputs.file(aetherBridgeDir.resolve("Cargo.toml"))
    inputs.file(aetherBridgeDir.resolve("Cargo.lock"))
    inputs.dir(aetherCrateDir.resolve("src"))
    inputs.file(aetherCrateDir.resolve("Cargo.toml"))
    inputs.file(aetherCrateDir.resolve("Cargo.lock"))
    inputs.dir(rootProject.projectDir.resolve("aether/quiche/quiche"))
    inputs.dir(rootProject.projectDir.resolve("aether/quiche/octets"))
    outputs.dir(aetherJniLibsDir)

    val ndkDir = ndkHomeProvider
    environment("ANDROID_NDK_HOME", ndkDir)
    environment("ANDROID_NDK_ROOT", ndkDir)

    // Pass the caller's LIBCLANG_PATH/LD_LIBRARY_PATH through so boring-sys /
    // bindgen can find the host libclang when it is not on default paths.
    environment("LIBCLANG_PATH", providers.environmentVariable("LIBCLANG_PATH"))
    environment("LD_LIBRARY_PATH", providers.environmentVariable("LD_LIBRARY_PATH"))

    commandLine(
        "cargo", "ndk",
        *aetherAbis.flatMap { listOf("-t", it) }.toTypedArray(),
        "--platform", aetherNdkPlatform,
        "-o", aetherJniLibsDir.asFile.absolutePath,
        "build", "--release", "--locked"
    )

    doFirst {
        if (ndkDir.orNull.isNullOrBlank()) {
            throw GradleException(
                "Aether native build requires the Android NDK. Set ANDROID_NDK_HOME " +
                    "(or ANDROID_NDK_ROOT). See docs/toolchain.md."
            )
        }
    }

    onlyIf { aetherEnabled }
}

// Make sure jniLibs sources are available before packaging and that a normal
// `assembleDebug` triggers the native build (unless opted out).
if (aetherEnabled) {
    afterEvaluate {
        tasks.matching { it.name.startsWith("pre") && it.name.contains("Build") }
            .configureEach { dependsOn(aetherBuildNative) }
    }
}