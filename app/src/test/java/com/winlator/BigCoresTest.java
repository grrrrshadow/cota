package com.winlator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.winlator.core.CPUStatus;

import org.junit.Test;

public class BigCoresTest {
    // One /proc/cpuinfo block per core, as an arm64 kernel prints it.
    private static String cpuinfo(String implementer, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append("processor\t: ").append(i).append('\n')
              .append("BogoMIPS\t: 26.00\n")
              .append("Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n")
              .append("CPU implementer\t: ").append(implementer).append('\n')
              .append("CPU architecture: 8\n")
              .append("CPU variant\t: 0x1\n")
              .append("CPU part\t: ").append(parts[i]).append('\n')
              .append("CPU revision\t: 0\n\n");
        }
        return sb.toString();
    }

    @Test
    public void dimensity7400KeepsTheFourA78() {
        // 4x Cortex-A55 + 4x Cortex-A78 (Motorola Edge 60 Fusion)
        assertEquals("4,5,6,7", CPUStatus.parseBigCoreList(cpuinfo("0x41", "0xd05", "0xd05", "0xd05", "0xd05", "0xd41", "0xd41", "0xd41", "0xd41")));
    }

    @Test
    public void snapdragon8Gen3DropsOnlyTheA520() {
        assertEquals("2,3,4,5,6,7", CPUStatus.parseBigCoreList(cpuinfo("0x41", "0xd80", "0xd80", "0xd81", "0xd81", "0xd81", "0xd81", "0xd81", "0xd82")));
    }

    @Test
    public void olderSnapdragonWithKryoSilverCores() {
        assertEquals("4,5,6,7", CPUStatus.parseBigCoreList(cpuinfo("0x51", "0x805", "0x805", "0x805", "0x805", "0x804", "0x804", "0x804", "0x804")));
    }

    @Test
    public void c1NanoIsALittleCore() {
        // 4x C1-Nano + 4x C1-Pro
        assertEquals("4,5,6,7", CPUStatus.parseBigCoreList(cpuinfo("0x41", "0xd8a", "0xd8a", "0xd8a", "0xd8a", "0xd8b", "0xd8b", "0xd8b", "0xd8b")));
    }

    @Test
    public void twoBigCoresAreNotEnough() {
        // 2x Cortex-A75 + 6x Cortex-A55 (Redmi A5): keep all cores
        assertNull(CPUStatus.parseBigCoreList(cpuinfo("0x41", "0xd05", "0xd05", "0xd05", "0xd05", "0xd05", "0xd05", "0xd0a", "0xd0a")));
    }

    @Test
    public void noLittleCoresMeansAllCores() {
        assertNull(CPUStatus.parseBigCoreList(cpuinfo("0x41", "0xd82", "0xd82", "0xd82", "0xd82", "0xd81", "0xd81", "0xd81", "0xd81")));
    }

    @Test
    public void unknownFormatMeansAllCores() {
        assertNull(CPUStatus.parseBigCoreList("processor\t: 0\nvendor_id\t: GenuineIntel\nmodel name\t: Intel(R) Xeon(R)\n\nprocessor\t: 1\nvendor_id\t: GenuineIntel\n"));
        assertNull(CPUStatus.parseBigCoreList(""));
    }
}
