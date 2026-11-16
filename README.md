# VoltGuard — Android Battery Optimizer (Kotlin, minSdk 26+)

VoltGuard is a transparent, non-root-first Android battery longevity and thermal companion built for the POCO X3 (Snapdragon 732G / 5160 mAh Li-Po) and modern Android 8.0+ devices.

## Architecture & Control Surfaces

1. **Standard Visible Launcher App + Jetpack Compose UI**
   - Appears normally in the launcher drawer and `Settings > Apps`.
   - Required by Android 10+ and MIUI/HyperOS so the Quick Settings Tile & WorkManager stay active (icon-less packages are blocked or kept in stopped state on Android 10+). You only need to open it once; after that, use the Quick Settings Tile daily.
2. **Quick Settings Tile (`OptimizerTileService`)**
   - Uses `android.service.quicksettings.TileService`.
   - **Single tap** cycles: `Auto → Performance → Longevity → Auto` and displays live battery % & °C right in the tile subtitle.
   - **Long-press** opens `Settings` directly via `ACTION_QS_TILE_PREFERENCES`.
   - On POCO X3 (MIUI/HyperOS): Pull down Quick Settings → tap **Edit** (pencil icon) → drag **VoltGuard** up into your active tiles.
3. **Zero-Notification Silent Mode (Default) + Part 1B Control System**
   - Runs quietly via `SilentTelemetryWorker` (15-min WorkManager) + `OptimizerTileService` with **zero notification cards in the shade** by default. Optional *"Persistent Shade Notification Card"* remains opt-in inside `Settings`.
   - **State Estimation**: 1-D constant-velocity Kalman filter smooths noisy `BatteryManager` %, voltage, and temperature into clean `dV/dt` and `dT/dt` slopes inside a 14-dim context vector using `ApplicationInfo.category`.
   - **Bayesian Linear Contextual Bandit**: Thompson Sampling over `{PERFORMANCE, BALANCED, LONGEVITY, PACED_CHARGE}` maximizing `reward_t = -(w1*wear + w2*thermal) + w3*responsiveness` while keeping `THERMAL_STATUS_SEVERE+` strictly outside learned policy as a hard guardrail override to `LONGEVITY`.
   - **4-State HMM Unplug Predictor**: Forward inference over `{ACTIVE, WINDING_DOWN, ASLEEP_IDLE, WAKING_UP}` for `P(unplug <= 120m)`, plus nightly Viterbi refitting in `ModelRetrainWorker` (strictly while charging & device idle).
4. **Room Persistence (`BatteryDatabase` v3)**
   - Stores timestamped samples (`BatterySampleEntity`), hourly weights (`UsagePatternEntity`), Bandit arm posteriors (`BanditArmStateEntity`), and HMM Viterbi state (`HmmModelStateEntity`) to surface **Cycles Saved**, **Extra Lifespan**, and **Est. Wear Avoided** on Trends (with Bandit Regret & HMM MAE isolated in Diagnostics).

---

## What VoltGuard CAN Control Without Root (Public Android APIs)

| Capability | Android Public API Used |
| :--- | :--- |
| Charge level, voltage, temperature, plugged type | `Intent.ACTION_BATTERY_CHANGED` + `BatteryManager.EXTRA_*` |
| Instantaneous current (µA) & remaining charge (µAh) | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW / CHARGE_COUNTER)` |
| Thermal throttling & headroom prediction | `PowerManager.getThermalHeadroom()` & `addThermalStatusListener()` (API 29+) |
| Doze / Power Saver / Exemption state | `PowerManager.isDeviceIdleMode()`, `isPowerSaveMode`, `isIgnoringBatteryOptimizations()` |
| Screen state, brightness & refresh rate (e.g. 60/120Hz) | `DisplayManager`, `Display.Mode.getRefreshRate()`, `Settings.System.SCREEN_BRIGHTNESS` |
| Network transport & signal quality | `ConnectivityManager.registerDefaultNetworkCallback()`, `TelephonyManager` |
| Foreground app detection (gaming vs idle) | `UsageStatsManager.queryEvents()` (requires user-granted Usage Access) |
| App Standby Bucket inspection | `UsageStatsManager.getAppStandbyBucket()` (API 28+) |
| Developer options battery risk flags | `Settings.Global.STAY_ON_WHILE_PLUGGED_IN`, `ADB_ENABLED` |
| Deferrable background scheduling | `WorkManager` (`setRequiresBatteryNotLow`, `setRequiresDeviceIdle`, `setRequiresCharging`) |
| Native OEM Charge Limiter detection & deep-link | Intent resolution to Xiaomi/HyperOS, Pixel, Samsung, OnePlus battery settings |

---

## What VoltGuard CANNOT Control Without Root

On stock, non-rooted Android, third-party apps run inside an unprivileged sandbox. VoltGuard **will never** use hidden/reflection APIs or exploit attempts to fake these:
- **Cannot** write kernel `cpufreq` / `devfreq` governors or disable CPU cores.
- **Cannot** write charger IC sysfs nodes (`constant_charge_current`, `input_suspend`, `voltage_max`).
- **Cannot** force-stop or override another app's wakelocks directly; instead, Diagnostics explains the culprit and deep-links to `Settings > App Info > Battery` so the user can restrict it cleanly.

---

## Optional Advanced (Root) Module — Manual Opt-In Only

If you have **already** manually unlocked and rooted your device (e.g., via Magisk) and want sysfs charge-current limiting:
1. Open VoltGuard → **Settings** → **Advanced (Root)**.
2. Read the safety disclosure and toggle **Enable Optional Root Sysfs Bridge**.
3. Approve the standard **Magisk Superuser** prompt when prompted.
4. VoltGuard remains 100% inert regarding root unless you explicitly enable this toggle yourself.

---

## How to Build the APK on GitHub (No Local Android Studio Required)

This project includes a pre-configured **GitHub Actions** workflow at `.github/workflows/build-apk.yml`.

1. Extract the downloaded ZIP (`VoltGuardBatteryOptimizer-AndroidStudio.zip`).
2. Inside the `VoltGuardBatteryOptimizer/` folder, push all files (including the hidden `.github/` folder) to a new GitHub repository.
3. Open your repository on GitHub and click the **Actions** tab.
4. Select **Build Android APK** (it runs automatically on push, or click **Run workflow**).
5. Once the workflow finishes (~2–3 minutes), scroll to **Artifacts** at the bottom of the run summary and download **VoltGuard-debug-apk** (`app-debug.apk`).
6. Transfer `app-debug.apk` to your POCO X3 and tap it to install (enable *Install unknown apps* if prompted).
