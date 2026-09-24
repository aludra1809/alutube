# Toolchain setup

This project is a single unified repository: the Alutube Android app (derived
from NewPipe) plus the vendored Aether Rust source at `aether/`. Building the
app **with** the Aether native component requires the Rust toolchain in
addition to the usual Android/JDK setup.

See also `.github/workflows/aether-native.yml` (CI), which mirrors the
upstream Aether `android` job (NDK r26d + cargo-ndk) and verifies that Gradle
packages the built libraries into the APK.

## Host requirements (Android app only)

- JDK 21 (Temurin or equivalent)
- Android SDK: AGP 9.3.1 needs `platforms;android-37`, `build-tools`, and
  `platform-tools`. `ANDROID_HOME` must point at the SDK.
- Gradle 9.7.1 comes from the wrapper (`./gradlew`).

## Additional requirements (Aether native component)

| Tool | Version / notes |
|---|---|
| Rust (rustup) | stable >= 1.98 (crate `rust-version = 1.98`, see `aether/aether/Cargo.toml`) |
| Rust Android targets | `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android` |
| cargo-ndk | install with `cargo install cargo-ndk --locked` |
| Android NDK | **r26d** (matching upstream Aether CI), exposed via `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT` |
| cmake | required by `boring-sys` to build BoringSSL (any recent 3.x) |
| C/C++ compiler | clang/gcc on the host (BoringSSL compile) |
| Network at build time | crates.io fetch is needed (a `Cargo.lock` is committed for `--locked` builds); BoringSSL source is fetched by `boring-sys` unless `BORING_BSSL_PATH` is set |

### One-time setup

```bash
rustup update stable
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked
```

Install the Android NDK r26d and export it for the native build:

```bash
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/26.3.11579264"   # r26d
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
```

### Verifying the native build manually

```bash
# 1. Aether builds standalone on the host:
cd aether/aether && cargo build --release --locked

# 2. Aether cross-compiles for each Android ABI:
cd aether/aether
export ANDROID_NDK_HOME=... ANDROID_NDK_ROOT=...
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  --platform 23 -o ../../app/src/main/jniLibs build --release --locked
```

### Building through Gradle (recommended)

`app/aether-native.gradle.kts` registers an `Exec` task (`:app:aetherBuildNative`)
that runs `cargo ndk` and writes `libaether.so` into
`app/src/main/jniLibs/<abi>/`, which standard AGP packaging then merges into
the APK/AAB. `assembleDebug` triggers it automatically; disable with
`-PaetherNative=off`.

`--platform` defaults to **23** (the app's `minSdk`). Upstream Aether ships
API 24 builds; if the Phase-1 verification finds a dependency incompatibility
at API 23, either bump the app's `minSdk` to 24 (needs authorization) or run
with `-PaetherNdkPlatform=24`.

## FAQ / troubleshooting

- **`cargo ndk` fails with "ndk not found"**: `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT`
  not set. Set it to the NDK r26d directory.
- **`boring-sys` build fails without CMake**: install CMake 3.x and make sure
  `cmake` is on `PATH` for the Gradle/CI user.
- **Slow first build**: quiche + BoringSSL compile for each ABI; use
  `cargo build --release --locked` once per ABI to warm the shared target dir.