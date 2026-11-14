package com.example.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.domain.BatteryHealthEstimator
import com.example.domain.BatteryTelemetry
import com.example.domain.ChargingSessionRecord
import com.example.domain.ChargingTelemetry
import com.example.domain.DataSourceOrigin
import com.example.domain.HealthSeverity
import com.example.domain.PowerCalculator
import com.example.domain.PowerSupplyNodeInfo
import com.example.domain.SourcedValue
import com.example.domain.ThermalParser
import com.example.domain.UnitConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

class BatteryAndChargingDataSource(
    private val context: Context,
    private val sysfsReader: SysfsReader
) {
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val sessionLock = Any()
    private val sessionHistory = mutableListOf<ChargingSessionRecord>()
    private var activeSession: ChargingSessionRecord? = null
    private var nextSessionId = 1L

    suspend fun readTelemetry(): Pair<BatteryTelemetry, ChargingTelemetry> = withContext(Dispatchers.IO) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val supplies = scanPowerSupplies()

        val batteryNode = supplies.firstOrNull {
            it.type.equals("Battery", ignoreCase = true) ||
                it.name.equals("battery", ignoreCase = true)
        }
        val bmsNode = supplies.firstOrNull { it.name.equals("bms", ignoreCase = true) }
        val usbNode = supplies.firstOrNull {
            val t = it.type?.lowercase() ?: ""
            t.startsWith("usb") || it.name.contains("usb", ignoreCase = true)
        }
        val acOrMainsNode = supplies.firstOrNull {
            val t = it.type?.lowercase() ?: ""
            t == "mains" || t == "ac" || it.name.equals("ac", ignoreCase = true) || it.name.equals("mains", ignoreCase = true)
        }
        val wirelessNode = supplies.firstOrNull {
            it.type.equals("Wireless", ignoreCase = true) || it.name.contains("wireless", ignoreCase = true)
        }

        // Percentage: sysfs battery/capacity -> BatteryManager capacity -> sticky intent
        val bmCapacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        val intentPct = intent?.let {
            val lvl = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (lvl >= 0 && scale > 0) (lvl * 100) / scale else null
        }
        val percentage = when {
            batteryNode?.capacityPercent != null -> SourcedValue(
                batteryNode.capacityPercent, "%", batteryNode.sourceOrigin, "${batteryNode.path}/capacity"
            )
            bmCapacity != null -> SourcedValue(
                bmCapacity, "%", DataSourceOrigin.ANDROID_BATTERY_MANAGER, "BatteryManager.BATTERY_PROPERTY_CAPACITY"
            )
            intentPct != null -> SourcedValue(
                intentPct, "%", DataSourceOrigin.ANDROID_BATTERY_MANAGER, "ACTION_BATTERY_CHANGED"
            )
            else -> SourcedValue(null, "%", DataSourceOrigin.UNAVAILABLE)
        }

        // Battery Voltage (mV): sysfs battery -> sysfs bms -> intent EXTRA_VOLTAGE
        val intentVoltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }?.toDouble()
        val voltageMv = when {
            batteryNode?.voltageMv != null -> SourcedValue(
                batteryNode.voltageMv, "mV", batteryNode.sourceOrigin, "${batteryNode.path}/voltage_now"
            )
            bmsNode?.voltageMv != null -> SourcedValue(
                bmsNode.voltageMv, "mV", bmsNode.sourceOrigin, "${bmsNode.path}/voltage_now"
            )
            intentVoltageMv != null -> SourcedValue(
                intentVoltageMv, "mV", DataSourceOrigin.ANDROID_BATTERY_MANAGER, "EXTRA_VOLTAGE"
            )
            else -> SourcedValue(null, "mV", DataSourceOrigin.UNAVAILABLE)
        }

        // Battery Current Now (mA): sysfs battery -> sysfs bms -> BatteryManager CURRENT_NOW
        val bmCurrentNowRaw = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Int.MIN_VALUE }
        val bmCurrentNowMa = bmCurrentNowRaw?.let { UnitConverter.toMilliamperes(it.toLong()).value }
        val currentNowMa = when {
            batteryNode?.currentMa != null -> SourcedValue(
                batteryNode.currentMa, "mA", batteryNode.sourceOrigin, "${batteryNode.path}/current_now"
            )
            bmsNode?.currentMa != null -> SourcedValue(
                bmsNode.currentMa, "mA", bmsNode.sourceOrigin, "${bmsNode.path}/current_now"
            )
            bmCurrentNowMa != null -> SourcedValue(
                bmCurrentNowMa, "mA", DataSourceOrigin.ANDROID_BATTERY_MANAGER, "BATTERY_PROPERTY_CURRENT_NOW"
            )
            else -> SourcedValue(null, "mA", DataSourceOrigin.UNAVAILABLE)
        }

        // Battery Current Avg (mA)
        val bmCurrentAvgRaw = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            ?.takeIf { it != Int.MIN_VALUE }
        val bmCurrentAvgMa = bmCurrentAvgRaw?.let { UnitConverter.toMilliamperes(it.toLong()).value }
        val currentAvgMa = when {
            batteryNode?.currentAvgMa != null -> SourcedValue(
                batteryNode.currentAvgMa, "mA", batteryNode.sourceOrigin, "${batteryNode.path}/current_avg"
            )
            bmsNode?.currentAvgMa != null -> SourcedValue(
                bmsNode.currentAvgMa, "mA", bmsNode.sourceOrigin, "${bmsNode.path}/current_avg"
            )
            bmCurrentAvgMa != null -> SourcedValue(
                bmCurrentAvgMa, "mA", DataSourceOrigin.ANDROID_BATTERY_MANAGER, "BATTERY_PROPERTY_CURRENT_AVERAGE"
            )
            else -> SourcedValue(null, "mA", DataSourceOrigin.UNAVAILABLE)
        }

        // Battery Temperature (°C)
        val intentTempC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE && it > -500 && it < 1500 }
            ?.let { it / 10.0 }
        val temperatureC = when {
            batteryNode?.tempCelsius != null -> SourcedValue(
                batteryNode.tempCelsius, "°C", batteryNode.sourceOrigin, "${batteryNode.path}/temp"
            )
            bmsNode?.tempCelsius != null -> SourcedValue(
                bmsNode.tempCelsius, "°C", bmsNode.sourceOrigin, "${bmsNode.path}/temp"
            )
            intentTempC != null -> SourcedValue(
                intentTempC, "°C", DataSourceOrigin.ANDROID_BATTERY_MANAGER, "EXTRA_TEMPERATURE"
            )
            else -> SourcedValue(null, "°C", DataSourceOrigin.UNAVAILABLE)
        }

        // Status & Charging state
        val intentStatusInt = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val intentStatusLabel = mapAndroidBatteryStatus(intentStatusInt)
        val statusValue = when {
            !batteryNode?.status.isNullOrBlank() -> SourcedValue(
                batteryNode?.status, "", batteryNode?.sourceOrigin ?: DataSourceOrigin.SYSFS_BATTERY, "${batteryNode?.path}/status"
            )
            intent != null -> SourcedValue(
                intentStatusLabel, "", DataSourceOrigin.ANDROID_BATTERY_MANAGER, "EXTRA_STATUS"
            )
            else -> SourcedValue(null, "", DataSourceOrigin.UNAVAILABLE)
        }

        val pluggedFlags = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val usbOnline = usbNode?.online == true
        val acOnline = acOrMainsNode?.online == true
        val wirelessOnline = wirelessNode?.online == true
        val isChargerConnected = pluggedFlags != 0 || usbOnline || acOnline || wirelessOnline
        val isCharging = statusValue.value.equals("Charging", ignoreCase = true) ||
            intentStatusInt == BatteryManager.BATTERY_STATUS_CHARGING

        val chargingStateLabel = when {
            statusValue.value.equals("Full", ignoreCase = true) -> "Full (External power connected)"
            isCharging -> "Charging"
            isChargerConnected -> "Connected (Not charging)"
            else -> "Discharging"
        }

        // Explain sign convention transparently (Qualcomm kernels often invert current_now sign)
        val currentDirectionNote = buildCurrentDirectionNote(
            currentMa = currentNowMa.value,
            isCharging = isCharging,
            status = statusValue.value
        )

        // Health & Technology
        val intentHealth = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val healthAndroid = when {
            intent != null -> SourcedValue(
                mapAndroidHealth(intentHealth), "", DataSourceOrigin.ANDROID_BATTERY_MANAGER, "EXTRA_HEALTH"
            )
            !batteryNode?.health.isNullOrBlank() -> SourcedValue(
                batteryNode?.health, "", batteryNode?.sourceOrigin ?: DataSourceOrigin.SYSFS_BATTERY, "${batteryNode?.path}/health"
            )
            else -> SourcedValue(null, "", DataSourceOrigin.UNAVAILABLE)
        }

        val intentTech = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }
        val technology = when {
            !batteryNode?.technology.isNullOrBlank() -> SourcedValue(
                batteryNode?.technology, "", batteryNode?.sourceOrigin ?: DataSourceOrigin.SYSFS_BATTERY, "${batteryNode?.path}/technology"
            )
            intentTech != null -> SourcedValue(
                intentTech, "", DataSourceOrigin.ANDROID_BATTERY_MANAGER, "EXTRA_TECHNOLOGY"
            )
            else -> SourcedValue(null, "", DataSourceOrigin.UNAVAILABLE)
        }

        // Charge counter, full, full_design, cycle_count
        val bmChargeCounterRaw = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 && it != Int.MIN_VALUE }
        val bmChargeCounterMah = bmChargeCounterRaw?.let { UnitConverter.toMilliampHours(it.toLong())?.value }
        val chargeCounterMah = when {
            batteryNode?.chargeCounterMah != null -> SourcedValue(
                batteryNode.chargeCounterMah, "mAh", batteryNode.sourceOrigin, "${batteryNode.path}/charge_counter"
            )
            bmsNode?.chargeCounterMah != null -> SourcedValue(
                bmsNode.chargeCounterMah, "mAh", bmsNode.sourceOrigin, "${bmsNode.path}/charge_counter"
            )
            bmChargeCounterMah != null -> SourcedValue(
                bmChargeCounterMah, "mAh", DataSourceOrigin.ANDROID_BATTERY_MANAGER, "BATTERY_PROPERTY_CHARGE_COUNTER"
            )
            else -> SourcedValue(null, "mAh", DataSourceOrigin.UNAVAILABLE)
        }

        val chargeFullMah = when {
            batteryNode?.chargeFullMah != null -> SourcedValue(
                batteryNode.chargeFullMah, "mAh", batteryNode.sourceOrigin, "${batteryNode.path}/charge_full"
            )
            bmsNode?.chargeFullMah != null -> SourcedValue(
                bmsNode.chargeFullMah, "mAh", bmsNode.sourceOrigin, "${bmsNode.path}/charge_full"
            )
            else -> SourcedValue(null, "mAh", DataSourceOrigin.UNAVAILABLE)
        }

        val chargeFullDesignMah = when {
            batteryNode?.chargeFullDesignMah != null -> SourcedValue(
                batteryNode.chargeFullDesignMah, "mAh", batteryNode.sourceOrigin, "${batteryNode.path}/charge_full_design"
            )
            bmsNode?.chargeFullDesignMah != null -> SourcedValue(
                bmsNode.chargeFullDesignMah, "mAh", bmsNode.sourceOrigin, "${bmsNode.path}/charge_full_design"
            )
            else -> SourcedValue(null, "mAh", DataSourceOrigin.UNAVAILABLE)
        }

        val cycleCount = when {
            batteryNode?.cycleCount != null -> SourcedValue(
                batteryNode.cycleCount, "cycles", batteryNode.sourceOrigin, "${batteryNode.path}/cycle_count"
            )
            bmsNode?.cycleCount != null -> SourcedValue(
                bmsNode.cycleCount, "cycles", bmsNode.sourceOrigin, "${bmsNode.path}/cycle_count"
            )
            else -> SourcedValue(null, "cycles", DataSourceOrigin.UNAVAILABLE)
        }

        val estimatedHealth = BatteryHealthEstimator.estimate(
            chargeFullMah = chargeFullMah.value,
            chargeFullDesignMah = chargeFullDesignMah.value
        )

        val batterySeverity = evaluateBatterySeverity(
            tempC = temperatureC.value,
            healthLabel = healthAndroid.value,
            voltageMv = voltageMv.value
        )

        val batteryTelemetry = BatteryTelemetry(
            percentage = percentage,
            voltageMv = voltageMv,
            currentNowMa = currentNowMa,
            currentAvgMa = currentAvgMa,
            currentDirectionNote = currentDirectionNote,
            temperatureC = temperatureC,
            status = statusValue,
            chargingState = chargingStateLabel,
            healthAndroid = healthAndroid,
            technology = technology,
            chargeCounterMah = chargeCounterMah,
            chargeFullMah = chargeFullMah,
            chargeFullDesignMah = chargeFullDesignMah,
            cycleCount = cycleCount,
            estimatedHealth = estimatedHealth,
            detectedPowerSupplies = supplies,
            severity = batterySeverity
        )

        // Charging Telemetry: STRICT separation of USB input rail vs Battery rail
        val usbVoltageMv = when {
            usbNode?.voltageMv != null -> SourcedValue(
                usbNode.voltageMv, "mV",
                if (usbNode.sourceOrigin == DataSourceOrigin.ROOT_KERNEL_INTERFACE) DataSourceOrigin.ROOT_KERNEL_INTERFACE else DataSourceOrigin.SYSFS_USB,
                "${usbNode.path}/voltage_now"
            )
            acOrMainsNode?.voltageMv != null -> SourcedValue(
                acOrMainsNode.voltageMv, "mV", acOrMainsNode.sourceOrigin, "${acOrMainsNode.path}/voltage_now"
            )
            else -> SourcedValue(null, "mV", DataSourceOrigin.UNAVAILABLE, note = "USB voltage node not exposed by kernel")
        }

        val usbCurrentVal = usbNode?.inputCurrentMa ?: usbNode?.currentMa ?: acOrMainsNode?.inputCurrentMa ?: acOrMainsNode?.currentMa
        val usbCurrentNode = when {
            usbNode?.inputCurrentMa != null -> "${usbNode.path}/input_current_now"
            usbNode?.currentMa != null -> "${usbNode.path}/current_now"
            acOrMainsNode?.inputCurrentMa != null -> "${acOrMainsNode.path}/input_current_now"
            acOrMainsNode?.currentMa != null -> "${acOrMainsNode.path}/current_now"
            else -> null
        }
        val usbCurrentMa = if (usbCurrentVal != null) {
            val origin = if (usbNode?.sourceOrigin == DataSourceOrigin.ROOT_KERNEL_INTERFACE ||
                acOrMainsNode?.sourceOrigin == DataSourceOrigin.ROOT_KERNEL_INTERFACE
            ) {
                DataSourceOrigin.ROOT_KERNEL_INTERFACE
            } else {
                DataSourceOrigin.SYSFS_USB
            }
            SourcedValue(usbCurrentVal, "mA", origin, usbCurrentNode)
        } else {
            SourcedValue(null, "mA", DataSourceOrigin.UNAVAILABLE, note = "Kernel does not expose USB input current")
        }

        // USB Input Power ONLY if BOTH USB voltage and USB current are present
        val usbPowerWattsVal = PowerCalculator.calculatePowerWatts(usbVoltageMv.value, usbCurrentMa.value)
        val usbPowerWatts = if (usbPowerWattsVal != null) {
            SourcedValue(usbPowerWattsVal, "W", DataSourceOrigin.SYSFS_USB, "P = V_usb × I_usb")
        } else {
            SourcedValue(null, "W", DataSourceOrigin.UNAVAILABLE, note = "Requires both USB voltage and USB current")
        }

        // Battery Power ONLY from Battery voltage and Battery current
        val batteryPowerVal = PowerCalculator.calculatePowerWatts(voltageMv.value, currentNowMa.value)
        val batteryPowerWatts = if (batteryPowerVal != null) {
            SourcedValue(batteryPowerVal, "W", voltageMv.source, "P = V_batt × I_batt")
        } else {
            SourcedValue(null, "W", DataSourceOrigin.UNAVAILABLE)
        }

        val chargingSourceLabel = buildChargingSourceLabel(pluggedFlags, usbNode, acOrMainsNode, wirelessNode)
        val primaryPowerForSpeed = usbPowerWatts.value ?: if (isCharging) batteryPowerWatts.value else null
        val chargingSpeedLabel = PowerCalculator.classifyChargingSpeed(primaryPowerForSpeed, isCharging)

        val (currentSession, history) = updateChargingSession(
            isCharging = isCharging,
            isConnected = isChargerConnected,
            source = chargingSourceLabel,
            percent = percentage.value,
            batteryPowerWatts = if (isCharging) batteryPowerWatts.value else null,
            usbPowerWatts = if (isCharging) usbPowerWatts.value else null,
            tempC = temperatureC.value
        )

        val chargingTelemetry = ChargingTelemetry(
            isChargerConnected = isChargerConnected,
            isCharging = isCharging,
            chargingSource = chargingSourceLabel,
            chargingSpeedLabel = chargingSpeedLabel,
            usbVoltageMv = usbVoltageMv,
            usbCurrentMa = usbCurrentMa,
            usbPowerWatts = usbPowerWatts,
            batteryVoltageMv = voltageMv,
            batteryCurrentMa = currentNowMa,
            batteryPowerWatts = batteryPowerWatts,
            chargingTemperatureC = temperatureC,
            activeSession = currentSession,
            sessionHistory = history
        )

        batteryTelemetry to chargingTelemetry
    }

    private suspend fun scanPowerSupplies(): List<PowerSupplyNodeInfo> {
        val baseDir = "/sys/class/power_supply"
        val (entries, dirUsedRoot) = sysfsReader.listDirectory(baseDir)
        if (entries.isEmpty()) return emptyList()

        val candidateFiles = listOf(
            "type", "online", "present", "status", "health", "technology",
            "capacity", "voltage_now", "current_now", "current_avg",
            "input_current_now", "temp", "charge_counter", "charge_full",
            "charge_full_design", "cycle_count", "voltage_max", "current_max"
        )
        val allPaths = entries.flatMap { name ->
            candidateFiles.map { file -> "$baseDir/$name/$file" }
        }
        val batch = sysfsReader.preloadBatchForRoot(allPaths)

        return entries.map { name ->
            val path = "$baseDir/$name"
            var usedRoot = dirUsedRoot

            fun readStr(file: String): String? {
                val res = batch["$path/$file"] ?: return null
                if (res.usedRoot) usedRoot = true
                return res.content
            }

            fun readNum(file: String): Long? {
                val res = batch["$path/$file"] ?: return null
                if (res.usedRoot) usedRoot = true
                return res.content?.trim()?.toLongOrNull()
            }

            val type = readStr("type")
            val online = readNum("online")?.let { it != 0L }
            val present = readNum("present")?.let { it != 0L }
            val status = readStr("status")
            val health = readStr("health")
            val technology = readStr("technology")
            val capacity = readNum("capacity")?.toInt()?.takeIf { it in 0..100 }
            val voltageMv = readNum("voltage_now")?.let { UnitConverter.toMillivolts(it)?.value }
            val currentMa = readNum("current_now")?.let { UnitConverter.toMilliamperes(it).value }
            val currentAvgMa = readNum("current_avg")?.let { UnitConverter.toMilliamperes(it).value }
            val inputCurrentMa = readNum("input_current_now")?.let { UnitConverter.toMilliamperes(it).value }
            val tempC = readNum("temp")?.let { ThermalParser.parseTemperatureCelsius(it, isBatteryPowerSupplyTemp = true)?.celsius }
            val chargeCounter = readNum("charge_counter")?.let { UnitConverter.toMilliampHours(it)?.value }
            val chargeFull = readNum("charge_full")?.let { UnitConverter.toMilliampHours(it)?.value }
            val chargeFullDesign = readNum("charge_full_design")?.let { UnitConverter.toMilliampHours(it)?.value }
            val cycleCount = readNum("cycle_count")?.toInt()?.takeIf { it >= 0 }
            val voltageMax = readNum("voltage_max")?.let { UnitConverter.toMillivolts(it)?.value }
            val currentMax = readNum("current_max")?.let { UnitConverter.toMilliamperes(it).value }

            val defaultOrigin = when {
                type.equals("Battery", ignoreCase = true) || name.equals("battery", ignoreCase = true) -> DataSourceOrigin.SYSFS_BATTERY
                type?.lowercase()?.startsWith("usb") == true || name.contains("usb", ignoreCase = true) -> DataSourceOrigin.SYSFS_USB
                else -> DataSourceOrigin.SYSFS_POWER_SUPPLY
            }

            PowerSupplyNodeInfo(
                name = name,
                path = path,
                type = type,
                online = online,
                present = present,
                status = status,
                health = health,
                technology = technology,
                capacityPercent = capacity,
                voltageMv = voltageMv,
                currentMa = currentMa,
                currentAvgMa = currentAvgMa,
                inputCurrentMa = inputCurrentMa,
                tempCelsius = tempC,
                chargeCounterMah = chargeCounter,
                chargeFullMah = chargeFull,
                chargeFullDesignMah = chargeFullDesign,
                cycleCount = cycleCount,
                voltageMaxMv = voltageMax,
                currentMaxMa = currentMax,
                sourceOrigin = sysfsReader.resolveSource(defaultOrigin, usedRoot)
            )
        }
    }

    private fun updateChargingSession(
        isCharging: Boolean,
        isConnected: Boolean,
        source: String,
        percent: Int?,
        batteryPowerWatts: Double?,
        usbPowerWatts: Double?,
        tempC: Double?
    ): Pair<ChargingSessionRecord?, List<ChargingSessionRecord>> = synchronized(sessionLock) {
        val now = System.currentTimeMillis()
        if (isCharging || isConnected) {
            val current = activeSession
            if (current == null) {
                activeSession = ChargingSessionRecord(
                    id = nextSessionId++,
                    startTimeMs = now,
                    endTimeMs = null,
                    source = source,
                    startPercent = percent,
                    latestPercent = percent,
                    peakBatteryPowerWatts = batteryPowerWatts,
                    peakUsbPowerWatts = usbPowerWatts,
                    maxTempCelsius = tempC
                )
            } else {
                activeSession = current.copy(
                    source = source,
                    latestPercent = percent ?: current.latestPercent,
                    peakBatteryPowerWatts = maxNullable(current.peakBatteryPowerWatts, batteryPowerWatts),
                    peakUsbPowerWatts = maxNullable(current.peakUsbPowerWatts, usbPowerWatts),
                    maxTempCelsius = maxNullable(current.maxTempCelsius, tempC)
                )
            }
        } else {
            val finished = activeSession
            if (finished != null) {
                sessionHistory.add(0, finished.copy(endTimeMs = now))
                if (sessionHistory.size > 15) {
                    sessionHistory.removeAt(sessionHistory.lastIndex)
                }
                activeSession = null
            }
        }
        return activeSession to sessionHistory.toList()
    }

    private fun maxNullable(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun buildCurrentDirectionNote(currentMa: Double?, isCharging: Boolean, status: String?): String {
        if (currentMa == null) return "Current sensor unavailable"
        if (abs(currentMa) < 1.0) return "Near zero net cell current"
        return when {
            isCharging && currentMa < 0 -> "Negative sign while Charging (Qualcomm qpnp convention: negative = into cell)"
            isCharging && currentMa > 0 -> "Positive sign while Charging (positive = into cell)"
            !isCharging && currentMa < 0 -> "Negative sign while ${status ?: "Discharging"} (negative = draining cell)"
            else -> "Positive sign while ${status ?: "Discharging"} (positive = draining cell)"
        }
    }

    private fun buildChargingSourceLabel(
        pluggedFlags: Int,
        usbNode: PowerSupplyNodeInfo?,
        acNode: PowerSupplyNodeInfo?,
        wirelessNode: PowerSupplyNodeInfo?
    ): String {
        val parts = mutableListOf<String>()
        if ((pluggedFlags and BatteryManager.BATTERY_PLUGGED_AC) != 0 || acNode?.online == true) {
            parts.add(acNode?.type?.let { "AC ($it)" } ?: "AC Charger")
        }
        if ((pluggedFlags and BatteryManager.BATTERY_PLUGGED_USB) != 0 || usbNode?.online == true) {
            val usbType = usbNode?.type?.takeIf { !it.equals("USB", ignoreCase = true) }
            parts.add(if (usbType != null) "USB ($usbType)" else "USB")
        }
        if ((pluggedFlags and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0 || wirelessNode?.online == true) {
            parts.add("Wireless")
        }
        return if (parts.isEmpty()) "Disconnected (Battery)" else parts.joinToString(" + ")
    }

    private fun evaluateBatterySeverity(
        tempC: Double?,
        healthLabel: String?,
        voltageMv: Double?
    ): HealthSeverity {
        if (healthLabel == "Overheat" || healthLabel == "Over Voltage" || healthLabel == "Dead") {
            return HealthSeverity.CRITICAL
        }
        if (tempC != null) {
            if (tempC >= 45.0 || tempC <= 0.0) return HealthSeverity.CRITICAL
            if (tempC >= 40.0 || tempC < 5.0) return HealthSeverity.WARNING
        }
        if (voltageMv != null && (voltageMv < 3100.0 || voltageMv > 4550.0)) {
            return HealthSeverity.CRITICAL
        }
        if (healthLabel == "Cold") return HealthSeverity.WARNING
        return if (tempC == null && voltageMv == null) HealthSeverity.UNAVAILABLE else HealthSeverity.NORMAL
    }

    private fun mapAndroidBatteryStatus(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    private fun mapAndroidHealth(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }
}
