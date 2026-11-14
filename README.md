# Redmi 4X Hardware Lab

**DEEP HARDWARE MONITORING & DIAGNOSTICS**  
Native Android (Kotlin + Jetpack Compose + Material 3) hardware telemetry and kernel diagnostic suite tailored for **Xiaomi Redmi 4X (Santoni / Snapdragon 435 / Adreno 505)** and compatible Android 6.0+ (API 23+) devices.

---

## Key Engineering Features

- **100% Real Telemetry (Zero Fake Values):** Reads directly from `/sys/class/power_supply/`, `/sys/class/thermal/thermal_zone*/`, `/proc/cpuinfo`, `/proc/stat`, `/proc/loadavg`, `/sys/devices/system/cpu/cpu*/cpufreq/`, `/sys/class/kgsl/kgsl-3d0/`, `/sys/class/devfreq/`, `/proc/meminfo`, `/proc/mounts`, `/sys/block/`, Android `BatteryManager`, `PowerManager`, `ActivityManager`, `StatFs`, and `SensorManager`. Missing or SELinux-restricted nodes are reported transparently as `N/A` or `UNAVAILABLE`.
- **Battery & Charging Lab (Strict Rail Separation):** Auto-detects `battery`, `bms`, `usb`, `ac`, `mains`, and `wireless` power supply directories, verifies units ($\mu V \to mV$, $\mu A \to mA$, $\mu Ah \to mAh$), displays data source provenance, estimates battery capacity retention from `charge_full / charge_full_design` (explicitly labeled as an estimate), and calculates $P = V \times I$ separately for the battery rail and USB input rail without mixing sides.
- **Thermal Lab:** Enumerates all `/sys/class/thermal/thermal_zone*` sensors with millidegree/decidegree unit parsing, classifies CPU/GPU/SoC/Battery/PMIC zones, tracks session peak temperatures, and reads `PowerManager` thermal status on Android 10+ (API 29+).
- **CPU & GPU Lab:** Real-time per-core frequency, governor, online state, `/proc/stat` utilization delta, load averages, and Qualcomm Adreno KGSL / devfreq / OpenGL ES telemetry.
- **Memory & Storage Lab:** Physical RAM, cached, buffers, swap/zRAM utilization, mount points, block devices, and an optional **non-destructive 4 MB I/O test** inside `cacheDir`.
- **Sensor Lab:** Full `SensorManager` hardware inventory plus battery-friendly live sensor streaming (`SENSOR_DELAY_UI`) that automatically unregisters on exit.
- **Kernel & Read-Only Root Lab:** Actual root capability verification via `su -c id` (`ROOT AVAILABLE`, `ROOT DENIED`, `ROOT NOT FOUND`), Magisk version detection, SELinux mode, and an explicit read-only allowlist. **Zero kernel parameters or sysfs files are ever modified.**
- **Diagnostic Center & Reports:** Automated checks (`PASS`, `WARNING`, `UNAVAILABLE`, `ERROR`) with technical explanations and TXT / CSV report export via Storage Access Framework (no broad storage permissions required).

---

## Project Architecture

```text
app/src/main/java/com/example/
├── data/           # SysfsReader, BatteryAndCharging, Thermal, CpuGpu, MemoryStorage, KernelSystem, Repository
├── diagnostics/    # DiagnosticEngine & TXT/CSV report formatter
├── domain/         # Models, UnitConverter, PowerCalculator, BatteryHealthEstimator, ThermalParser, CpuFreqParser
├── monitoring/     # HardwareLabViewModel, HardwareMonitorService (optional foreground monitoring)
├── root/           # RootShellManager & RootCommandAllowlist (strictly read-only)
├── sensors/        # DeviceSensorManager (SensorManager wrapper)
├── ui/
│   ├── components/ # RealTimeLineChart, MetricTile, StatusPill, MonitoringControlBar
│   ├── screens/    # Overview, Battery, Charging, Thermal, CpuGpu, MemoryStorage, Sensors, KernelRoot, Diagnostics
│   └── theme/      # Dark Navy/Charcoal engineering ColorScheme, Typography (Space Grotesk / Inter / JetBrains Mono)
└── utils/          # FormatUtils, StorageBenchmark
```

---

## Build & Installation Instructions

### Requirements
- Android Studio Ladybug / Narwhal or newer (Gradle 8/9 + Android Gradle Plugin 9.1.1, JDK 11+)
- Android SDK Platform 36 (`compileSdk = 36`, `minSdk = 23`, `targetSdk = 36`)

### Build via Android Studio
1. Open the root project folder in **Android Studio**.
2. Wait for Gradle Sync to resolve dependencies.
3. Connect your Xiaomi Redmi 4X (Santoni) or compatible Android 6.0+ device with USB Debugging enabled (or select a physical/virtual target).
4. Click **Run** (`app`) or build an APK via **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### Build & Test via CLI
```bash
# Run unit tests & Robolectric Compose UI tests
gradle :app:testDebugUnitTest

# Build Debug APK
gradle :app:assembleDebug
```
The generated debug APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

Install onto a connected device via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
