package com.winlator.xenvironment.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DynaCacheResetTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private SharedPreferences preferences;
    private File rootDir;

    private File file(String path) throws IOException {
        File file = new File(rootDir, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), new byte[]{1});
        return file;
    }

    @Before
    public void setUp() throws IOException {
        preferences = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        rootDir = tmp.newFolder("rootfs");
    }

    @Test
    public void cachesOfAnotherBuildAreDeletedOnceInEveryContainer() throws IOException {
        // What a phone has after playing on the stock 0.4.4 with DynaCache on: current_box64_version is reset by the update.
        preferences.edit().putString("current_box64_version", "0.4.4").apply();
        File cache1 = file("home/xuser-1/.cache/box64/openttd.exe-9290b302029-1086537065.box64");
        File cache2 = file("home/xuser-2/.cache/box64/ntdll.so-9290b302029-2583331050.box64");
        File mesaCache = file("home/xuser-1/.cache/mesa_shader_cache/index");
        File game = file("home/xuser-1/.wine/drive_c/Games/OpenTTD/openttd.exe");
        Files.createSymbolicLink(new File(rootDir, "home/xuser").toPath(), new File("xuser-1").toPath());

        GuestProgramLauncherComponent.clearDynaCachesOfOtherBox64(preferences, rootDir, "0.4.4-dl");

        assertFalse(cache1.getParentFile().exists());
        assertFalse(cache2.getParentFile().exists());
        assertTrue(mesaCache.isFile());
        assertTrue(game.isFile());
        assertEquals("0.4.4-dl", preferences.getString("dynacache_box64_version", ""));

        // Later starts and app updates with the same build keep what it wrote.
        File newCache = file("home/xuser-1/.cache/box64/openttd.exe-9290b302029-1086537065.box64");
        GuestProgramLauncherComponent.clearDynaCachesOfOtherBox64(preferences, rootDir, "0.4.4-dl");
        assertTrue(newCache.isFile());
    }

    @Test
    public void freshInstallWithoutContainers() {
        GuestProgramLauncherComponent.clearDynaCachesOfOtherBox64(preferences, rootDir, "0.4.4-dl");

        assertEquals("0.4.4-dl", preferences.getString("dynacache_box64_version", ""));
    }
}
