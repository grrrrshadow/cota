package com.winlator.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public abstract class CPUStatus {
    // "implementer:part" from /proc/cpuinfo of the in-order LITTLE cores: Cortex-A35/A53/A55/A510/A520/A520AE, C1-Nano
    // and the Qualcomm Kryo Silver cores built on them.
    private static final String[] LITTLE_CPU_PARTS = {"0x41:0xd04", "0x41:0xd03", "0x41:0xd05", "0x41:0xd46", "0x41:0xd80", "0x41:0xd88", "0x41:0xd8a", "0x51:0x801", "0x51:0x803", "0x51:0x805"};
    private static String bigCoreList;

    /** The cores that are not LITTLE ones, e.g. "4,5,6,7", or null if the CPU has no LITTLE cores or fewer than 4 others. */
    public static synchronized String getBigCoreList() {
        if (bigCoreList == null) {
            byte[] data = FileUtils.read(new File("/proc/cpuinfo"));
            if (data == null) return null;
            String cpuinfo = new String(data, StandardCharsets.UTF_8);
            // /proc/cpuinfo lists only the online cores; ask again later instead of remembering a partial answer.
            if (countProcessors(cpuinfo) != Runtime.getRuntime().availableProcessors()) return null;
            String result = parseBigCoreList(cpuinfo);
            bigCoreList = result != null ? result : "";
        }
        return !bigCoreList.isEmpty() ? bigCoreList : null;
    }

    private static int countProcessors(String cpuinfo) {
        int count = 0;
        for (String line : cpuinfo.split("\n")) if (line.startsWith("processor") && line.contains(":")) count++;
        return count;
    }

    public static String parseBigCoreList(String cpuinfo) {
        String bigCoreList = "";
        int numBigCores = 0;
        int numProcessors = 0;
        int numKnownProcessors = 0;
        String processor = null;
        String implementer = null;

        for (String line : cpuinfo.split("\n")) {
            int index = line.indexOf(':');
            if (index == -1) continue;
            String key = line.substring(0, index).trim();
            String value = line.substring(index + 1).trim().toLowerCase(Locale.ENGLISH);

            if (key.equals("processor")) {
                processor = value;
                implementer = null;
                numProcessors++;
            }
            else if (key.equals("CPU implementer")) {
                implementer = value;
            }
            else if (key.equals("CPU part") && processor != null && implementer != null) {
                numKnownProcessors++;
                if (!ArrayUtils.contains(LITTLE_CPU_PARTS, implementer+":"+value)) {
                    bigCoreList += (!bigCoreList.isEmpty() ? "," : "")+processor;
                    numBigCores++;
                }
                processor = null;
            }
        }

        boolean hasLittleCores = numBigCores < numKnownProcessors;
        return numProcessors > 0 && numKnownProcessors == numProcessors && hasLittleCores && numBigCores >= 4 ? bigCoreList : null;
    }

    public static short[] getCurrentClockSpeeds() {
        int numProcessors = Runtime.getRuntime().availableProcessors();
        short[] clockSpeeds = new short[numProcessors];
        for (int i = 0; i < numProcessors; i++) {
            int currFreq = FileUtils.readInt("/sys/devices/system/cpu/cpu"+i+"/cpufreq/scaling_cur_freq");
            clockSpeeds[i] = (short)(currFreq / 1000);
        }
        return clockSpeeds;
    }

    public static short getMaxClockSpeed(int cpuIndex) {
        int maxFreq = FileUtils.readInt("/sys/devices/system/cpu/cpu"+cpuIndex+"/cpufreq/cpuinfo_max_freq");
        return (short)(maxFreq / 1000);
    }

    public static int getTemperature() {
        final String[] sensorFiles = {
            "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp",
            "/sys/devices/system/cpu/cpu0/cpufreq/FakeShmoo_cpu_temp",
            "/sys/devices/platform/tegra-i2c.3/i2c-4/4-004c/temperature",
            "/sys/devices/platform/omap/omap_temp_sensor.0/temperature",
            "/sys/devices/platform/tegra_tmon/temp1_input",
            "/sys/devices/platform/s5p-tmu/temperature",
            "/sys/devices/platform/s5p-tmu/curr_temp",
            "/sys/devices/virtual/thermal/thermal_zone10/temp",
            "/sys/devices/virtual/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone3/temp",
            "/sys/class/thermal/thermal_zone4/temp",
            "/sys/class/hwmon/hwmon0/device/temp1_input",
            "/sys/class/hwmon/hwmonX/temp1_input",
            "/sys/class/i2c-adapter/i2c-4/4-004c/temperature",
            "/sys/kernel/debug/tegra_thermal/temp_tj",
            "/sys/htc/cpu_temp",
            "/sys/devices/platform/tegra-i2c.3/i2c-4/4-004c/ext_temperature",
            "/sys/devices/platform/tegra-tsensor/tsensor_temperature",
            "/sys/devices/virtual/sec/sec-lp-thermistor/temperature"
        };

        for (String path : sensorFiles) {
            File file = new File(path);
            if (file.exists()) {
                try (RandomAccessFile reader = new RandomAccessFile(file , "r")) {
                    String value = reader.readLine();
                    int temperature = value != null ? Integer.parseInt(value) : 0;
                    if (temperature > 100 && temperature <= 1000) {
                        return (int)(temperature / 10.0f);
                    }
                    else if (temperature > 1000 && temperature <= 10000) {
                        return (int)(temperature / 100.0f);
                    }
                    else if (temperature > 10000) {
                        return (int)(temperature / 1000.0f);
                    }
                }
                catch (IOException | NumberFormatException e) {}
            }
        }

        return 0;
    }

    public static String formatClockSpeed(int clockSpeed) {
        return clockSpeed >= 1000 ? String.format(Locale.ENGLISH, "%.2f", clockSpeed / 1000.0f)+" GHz" : clockSpeed+" MHz";
    }
}