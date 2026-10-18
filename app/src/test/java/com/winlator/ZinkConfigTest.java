package com.winlator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.winlator.container.Container;
import com.winlator.container.GraphicsDriverPicker;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contentdialog.ZinkConfigDialog;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.KeyValueSet;
import com.winlator.widget.TaggedSelectionBox;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.InputStream;

// Drives the real container-settings UI pieces: pick Zink options, press OK, save the container, load it again.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ZinkConfigTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Context themedContext() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        return new ContextThemeWrapper(activity, R.style.AppThemeDark);
    }

    private static TaggedSelectionBox openGLBox(LinearLayout pickerLayout) {
        return (TaggedSelectionBox)pickerLayout.getChildAt(1);
    }

    private static Dialog openZinkDialog(LinearLayout pickerLayout) {
        openGLBox(pickerLayout).findViewById(R.id.BTSettings).performClick();
        return ShadowDialog.getLatestDialog();
    }

    private static int indexOf(Spinner spinner, String item) {
        for (int i = 0; i < spinner.getCount(); i++) if (spinner.getItemAtPosition(i).toString().equals(item)) return i;
        throw new AssertionError("spinner has no item " + item);
    }

    private static EnvVars envVars(String openGLConfig, boolean vortek) {
        EnvVars envVars = new EnvVars();
        ZinkConfigDialog.setEnvVars(new KeyValueSet(openGLConfig), vortek, envVars);
        return envVars;
    }

    @Test
    public void versionAndGLVersionSurviveSaveAndReload() throws Exception {
        Context context = themedContext();

        LinearLayout layout = new LinearLayout(context);
        GraphicsDriverPicker picker = new GraphicsDriverPicker(layout, "vortek,zink", "");
        assertEquals("Zink", openGLBox(layout).getSelectedItem());

        Dialog dialog = openZinkDialog(layout);
        Spinner version = dialog.findViewById(R.id.SVersion);
        Spinner glVersion = dialog.findViewById(R.id.SGLVersion);
        assertEquals("25.1.9", version.getSelectedItem().toString());
        assertEquals("Auto", glVersion.getSelectedItem().toString());
        version.setSelection(indexOf(version, "22.2.5"));
        glVersion.setSelection(indexOf(glVersion, "4.1"));
        dialog.findViewById(R.id.BTConfirm).performClick();

        String config = picker.getGraphicsDriverConfig();
        System.out.println("graphicsDriverConfig after OK: [" + config + "]");

        File rootDir = tmp.newFolder("xuser-1");
        Container saved = new Container(1);
        saved.setRootDir(rootDir);
        saved.setGraphicsDriver(picker.getGraphicsDriver());
        saved.setGraphicsDriverConfig(config);
        saved.saveData();

        String json = FileUtils.readString(saved.getConfigFile());
        System.out.println(".container: " + json);

        Container loaded = new Container(1);
        loaded.setRootDir(rootDir);
        loaded.loadData(new JSONObject(json));
        assertEquals("vortek,zink", loaded.getGraphicsDriver());
        assertEquals(config, loaded.getGraphicsDriverConfig());

        LinearLayout layout2 = new LinearLayout(context);
        new GraphicsDriverPicker(layout2, loaded.getGraphicsDriver(), loaded.getGraphicsDriverConfig());
        Dialog dialog2 = openZinkDialog(layout2);
        assertEquals("22.2.5", ((Spinner)dialog2.findViewById(R.id.SVersion)).getSelectedItem().toString());
        assertEquals("4.1", ((Spinner)dialog2.findViewById(R.id.SGLVersion)).getSelectedItem().toString());

        // What XServerDisplayActivity does with the loaded container.
        KeyValueSet openGLConfig = GraphicsDrivers.parseConfigs(loaded.getGraphicsDriver(), loaded.getGraphicsDriverConfig())[1];
        assertEquals("22.2.5", ZinkConfigDialog.getVersion(openGLConfig));
        EnvVars envVars = new EnvVars();
        ZinkConfigDialog.setEnvVars(openGLConfig, true, envVars);
        assertEquals("zink", envVars.get("GALLIUM_DRIVER"));
        assertEquals("1", envVars.get("ZINK_CONTEXT_THREADED"));
        assertEquals("4.1", envVars.get("MESA_GL_VERSION_OVERRIDE"));
    }

    @Test
    public void autoIsStoredAsNoGLVersion() {
        Context context = themedContext();
        LinearLayout layout = new LinearLayout(context);
        GraphicsDriverPicker picker = new GraphicsDriverPicker(layout, "vortek,zink", "|version=22.2.5,glVersionOverride=4.5");

        Dialog dialog = openZinkDialog(layout);
        Spinner version = dialog.findViewById(R.id.SVersion);
        Spinner glVersion = dialog.findViewById(R.id.SGLVersion);
        assertEquals("4.5", glVersion.getSelectedItem().toString());
        version.setSelection(indexOf(version, "25.1.9"));
        glVersion.setSelection(indexOf(glVersion, "Auto"));
        dialog.findViewById(R.id.BTConfirm).performClick();

        KeyValueSet openGLConfig = GraphicsDrivers.parseConfigs(picker.getGraphicsDriver(), picker.getGraphicsDriverConfig())[1];
        assertEquals("version=25.1.9", openGLConfig.toString());
        // "Auto" is 3.3 under Vortek and nothing with Turnip.
        assertEquals("3.3", envVars(openGLConfig.toString(), true).get("MESA_GL_VERSION_OVERRIDE"));
        assertFalse(envVars(openGLConfig.toString(), false).has("MESA_GL_VERSION_OVERRIDE"));
    }

    @Test
    public void envVarsForEachCase() {
        // 25.1.9 (default): 3.3 under Vortek unless the dialog has a version, nothing with Turnip.
        assertEquals("3.3", envVars("", true).get("MESA_GL_VERSION_OVERRIDE"));
        assertEquals("3.3", envVars("version=25.1.9", true).get("MESA_GL_VERSION_OVERRIDE"));
        assertFalse(envVars("", false).has("MESA_GL_VERSION_OVERRIDE"));
        assertFalse(envVars("version=25.1.9", false).has("MESA_GL_VERSION_OVERRIDE"));
        assertEquals("4.6", envVars("version=25.1.9,glVersionOverride=4.6", true).get("MESA_GL_VERSION_OVERRIDE"));
        assertEquals("4.0", envVars("version=25.1.9,glVersionOverride=4.0", true).get("MESA_GL_VERSION_OVERRIDE"));
        assertEquals("3.3", envVars("version=25.1.9,glVersionOverride=3.3", false).get("MESA_GL_VERSION_OVERRIDE"));

        // 22.2.5: as before, 3.3 under Vortek unless the dialog has a version.
        assertEquals("3.3", envVars("version=22.2.5", true).get("MESA_GL_VERSION_OVERRIDE"));
        assertFalse(envVars("version=22.2.5", false).has("MESA_GL_VERSION_OVERRIDE"));
        assertEquals("4.1", envVars("version=22.2.5,glVersionOverride=4.1", true).get("MESA_GL_VERSION_OVERRIDE"));

        // A broken or unknown value falls back to the defaults; 26.2.5 (zink cannot start on Vortek) is gone.
        assertEquals("3.3", envVars("version=25.1.9,glVersionOverride=abc", true).get("MESA_GL_VERSION_OVERRIDE"));
        assertFalse(envVars("version=25.1.9,glVersionOverride=abc", false).has("MESA_GL_VERSION_OVERRIDE"));
        assertEquals(DefaultVersion.ZINK, ZinkConfigDialog.getVersion(new KeyValueSet("version=23.0")));
        assertEquals("25.1.9", ZinkConfigDialog.getVersion(new KeyValueSet("version=26.2.5")));
        assertEquals("25.1.9", ZinkConfigDialog.getVersion(new KeyValueSet("")));

        // Only 22.2.5 needs ZINK_CONTEXT_THREADED; newer Mesa ignores it and threads by default.
        EnvVars envVars = envVars("", true);
        assertEquals("zink", envVars.get("GALLIUM_DRIVER"));
        assertFalse(envVars.has("ZINK_CONTEXT_THREADED"));
        assertEquals("1", envVars("version=22.2.5", true).get("ZINK_CONTEXT_THREADED"));
    }

    @Test
    public void shortcutConfigOverridesTheContainer() throws Exception {
        Context context = themedContext();
        File rootDir = tmp.newFolder("xuser-2");
        Container container = new Container(2);
        container.setRootDir(rootDir);
        container.setGraphicsDriver("vortek,zink");
        container.setGraphicsDriverConfig("|version=25.1.9");

        File desktopFile = new File(tmp.newFolder("shortcuts"), "OpenTTD.desktop");
        FileUtils.writeString(desktopFile, "[Desktop Entry]\nName=OpenTTD\nExec=env WINEPREFIX=\"/home/xuser/.wine\" wine \"C:\\\\OpenTTD\\\\openttd.exe\"\nType=Application\n");
        Shortcut shortcut = new Shortcut(container, desktopFile);

        // Same steps as ShortcutSettingsDialog: picker on the shortcut's config, Zink dialog, OK, save.
        LinearLayout layout = new LinearLayout(context);
        GraphicsDriverPicker picker = new GraphicsDriverPicker(layout, shortcut.getExtra("graphicsDriver", container.getGraphicsDriver()), shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig()));
        Dialog dialog = openZinkDialog(layout);
        Spinner version = dialog.findViewById(R.id.SVersion);
        Spinner glVersion = dialog.findViewById(R.id.SGLVersion);
        version.setSelection(indexOf(version, "22.2.5"));
        glVersion.setSelection(indexOf(glVersion, "4.3"));
        dialog.findViewById(R.id.BTConfirm).performClick();
        String graphicsDriverConfig = picker.getGraphicsDriverConfig();
        shortcut.putExtra("graphicsDriverConfig", !graphicsDriverConfig.equals(container.getGraphicsDriverConfig()) ? graphicsDriverConfig : null);
        shortcut.saveData();
        System.out.println(".desktop: " + FileUtils.readString(desktopFile));

        // What XServerDisplayActivity reads when started from the shortcut.
        Shortcut reloaded = new Shortcut(container, desktopFile);
        String graphicsDriver = reloaded.getExtra("graphicsDriver", container.getGraphicsDriver());
        KeyValueSet openGLConfig = GraphicsDrivers.parseConfigs(graphicsDriver, reloaded.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig()))[1];
        assertEquals("22.2.5", ZinkConfigDialog.getVersion(openGLConfig));
        EnvVars envVars = new EnvVars();
        ZinkConfigDialog.setEnvVars(openGLConfig, true, envVars);
        assertEquals("4.3", envVars.get("MESA_GL_VERSION_OVERRIDE"));

        // The container itself keeps its own choice.
        KeyValueSet containerConfig = GraphicsDrivers.parseConfigs(container.getGraphicsDriver(), container.getGraphicsDriverConfig())[1];
        assertEquals("25.1.9", ZinkConfigDialog.getVersion(containerConfig));
    }

    @Test
    public void otherDriversSettingsInTheSlotAreIgnored() {
        // The OpenGL box keeps the previous driver's config until that driver's dialog is confirmed:
        // a VirGL container (VirGL always saves glVersion, 3.1 by default) switched to Zink and saved as it is.
        Context context = themedContext();
        LinearLayout layout = new LinearLayout(context);
        GraphicsDriverPicker picker = new GraphicsDriverPicker(layout, "vortek,virgl", "|glVersion=4.0,disableVertexArrayBGRA=1,coreProfile=1");
        openGLBox(layout).setSelectedItem("Zink");
        assertEquals("vortek,zink", picker.getGraphicsDriver());

        KeyValueSet openGLConfig = GraphicsDrivers.parseConfigs(picker.getGraphicsDriver(), picker.getGraphicsDriverConfig())[1];
        assertEquals(ZinkConfigDialog.VORTEK_GL_VERSION, envVars(openGLConfig.toString(), true).get("MESA_GL_VERSION_OVERRIDE"));
        assertFalse(envVars(openGLConfig.toString(), false).has("MESA_GL_VERSION_OVERRIDE"));
        assertFalse(envVars("glVersion=3.1,disableVertexArrayBGRA=1,coreProfile=0", false).has("MESA_GL_VERSION_OVERRIDE"));
        assertEquals(DefaultVersion.ZINK, ZinkConfigDialog.getVersion(openGLConfig));

        Dialog dialog = openZinkDialog(layout);
        Spinner glVersion = dialog.findViewById(R.id.SGLVersion);
        assertEquals("25.1.9", ((Spinner)dialog.findViewById(R.id.SVersion)).getSelectedItem().toString());
        assertEquals("Auto", glVersion.getSelectedItem().toString());

        // And the other way round: Zink's choice does not become VirGL's version.
        glVersion.setSelection(indexOf(glVersion, "4.6"));
        dialog.findViewById(R.id.BTConfirm).performClick();
        openGLBox(layout).setSelectedItem("VirGL");
        KeyValueSet virglConfig = GraphicsDrivers.parseConfigs(picker.getGraphicsDriver(), picker.getGraphicsDriverConfig())[1];
        EnvVars virglEnvVars = new EnvVars();
        VirGLConfigDialog.setEnvVars(virglConfig, virglEnvVars);
        assertEquals(VirGLConfigDialog.DEFAULT_GL_VERSION, virglEnvVars.get("MESA_GL_VERSION_OVERRIDE"));
    }

    @Test
    public void everyZinkVersionHasItsAsset() throws Exception {
        Context context = themedContext();
        for (String version : ZinkConfigDialog.VERSIONS) {
            long size = 0;
            try (InputStream inStream = context.getAssets().open("graphics_driver/zink-" + version + ".tzst")) {
                byte[] buffer = new byte[65536];
                for (int n; (n = inStream.read(buffer)) > 0; ) size += n;
            }
            assertTrue("zink-" + version + ".tzst looks empty", size > 1000000);
        }
    }
}
