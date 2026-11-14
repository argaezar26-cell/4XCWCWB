# Redmi 4X Hardware Lab (v2.0.0)

**DEEP HARDWARE MONITORING & KERNEL DIAGNOSTICS**  
Native Android (Kotlin + Jetpack Compose + Material 3) hardware telemetry and kernel diagnostic suite tailored for **Xiaomi Redmi 4X (Santoni / Snapdragon 435 / Adreno 505)** and compatible Android 6.0+ (API 23+) devices.

📥 **Direct APK Download:** Open the repository **Releases** page and download `Redmi4X-Hardware-Lab-v2.0.0.apk` (automatically built & published via GitHub Actions).

---

## Key Engineering Features (v2.0.0)

- **100% Real Telemetry (Zero Fake Values):** Reads directly from `/sys/class/power_supply/`, `/sys/class/thermal/thermal_zone*/`, `/proc/cpuinfo`, `/proc/stat`, `/proc/loadavg`, `/sys/devices/system/cpu/cpu*/cpufreq/`, `/sys/class/kgsl/kgsl-3d0/`, `/sys/class/devfreq/`, `/proc/meminfo`, `/sys/block/zram0/mm_stat`, `/proc/partitions`, `/sys/block/mmcblk0/device/`, `/proc/mounts`, Android `BatteryManager`, `PowerManager`, `ActivityManager`, `DisplayManager`, `StatFs`, and `SensorManager`. Missing or SELinux-restricted nodes are reported transparently as `N/A` or `UNAVAILABLE`.
- **Battery & Charging Lab (Strict Rail Separation):** Auto-detects `battery`, `bms`, `usb`, `ac`, `mains`, and `wireless` power supply directories, verifies units ($\mu V \to mV$, $\mu A \to mA$, $\mu Ah \to mAh$), reads `charge_type`, `resistance` ($m\Omega$), and `voltage_max_design`, calculates live `%/hr` drain/charge rate & ETA, estimates battery capacity retention from `charge_full / charge_full_design`, and computes $P = V \times I$ separately for the battery rail vs USB input rail.
- **Thermal Lab (Filters & Trip Points):** Enumerates all `/sys/class/thermal/thermal_zone*` sensors with category filters (`All`, `Active`, `CPU`, `GPU`, `SoC`, `Battery`, `PMIC`), `trip_point_0..3` throttling thresholds, session peak tracking, and `PowerManager` thermal status on Android 10+.
- **CPU, Deep Sleep & GPU Lab:** Real-time per-core frequency, governor, online state, `/proc/stat` utilization delta, **Deep Sleep vs Awake uptime** efficiency, **CPU cluster `time_in_state` frequency residency bars**, and Qualcomm Adreno KGSL / devfreq / OpenGL ES telemetry.
- **Memory, zRAM & eMMC Flash Lab:** Physical RAM, Kernel `Slab`/`PageTables`/`Dirty`, **`/sys/block/zram0/mm_stat` real-time compression ratio**, **JEDEC eMMC 5.0+ Wear Level (`life_time` Type A/B) & Pre-EOL health**, `/proc/partitions`, `/proc/mounts`, and optional **non-destructive 4 MB I/O test**.
- **Sensor Lab:** Full `SensorManager` hardware inventory plus battery-friendly live sensor streaming (`SENSOR_DELAY_UI`) that automatically unregisters on exit.
- **Kernel, Display & Read-Only Root Lab:** Persistent read-only `su` batch shell (with `su -c id` probe, Magisk version detection, SELinux mode, strict allowlist & timeouts), Display hardware mode/Hz/HDR inspection, and Read-Only Kernel Tunables Audit (`/proc/sys/vm/*`, TCP congestion, `sched_boost`, workqueue). **Zero kernel parameters or sysfs files are ever modified.**
- **Diagnostic Center & Multi-Format Export:** Automated checks (`PASS`, `WARNING`, `UNAVAILABLE`, `ERROR`) with **TXT, CSV, and structured JSON** export via Storage Access Framework plus **Share** sheet integration.

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
