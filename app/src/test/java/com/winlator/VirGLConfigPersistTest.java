package com.winlator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.winlator.container.Container;
import com.winlator.container.DXWrapperPicker;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDriverPicker;
import com.winlator.contentdialog.VirGLConfigDialog;
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

// Drives the real container-settings UI pieces: pick VirGL options, press OK, save the container, load it again.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VirGLConfigPersistTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Context themedContext() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        return new ContextThemeWrapper(activity, R.style.AppThemeDark);
    }

    private static TaggedSelectionBox openGLBox(LinearLayout pickerLayout) {
        return (TaggedSelectionBox)pickerLayout.getChildAt(1);
    }

    private static Dialog openVirGLDialog(LinearLayout pickerLayout) {
        openGLBox(pickerLayout).findViewById(R.id.BTSettings).performClick();
        return ShadowDialog.getLatestDialog();
    }

    private static int indexOf(Spinner spinner, String item) {
        for (int i = 0; i < spinner.getCount(); i++) if (spinner.getItemAtPosition(i).toString().equals(item)) return i;
        throw new AssertionError("spinner has no item " + item);
    }

    @Test
    public void virglVersionAndCoreProfileSurviveSaveAndReload() throws Exception {
        Context context = themedContext();

        LinearLayout layout = new LinearLayout(context);
        GraphicsDriverPicker picker = new GraphicsDriverPicker(layout, "vortek,virgl", "");
        assertEquals("VirGL", openGLBox(layout).getSelectedItem());

        Dialog dialog = openVirGLDialog(layout);
        Spinner version = dialog.findViewById(R.id.SVersion);
        CheckBox core = dialog.findViewById(R.id.CBCoreProfile);
        assertEquals("3.1", version.getSelectedItem().toString());
        version.setSelection(indexOf(version, "4.3"));
        core.setChecked(true);
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
        assertEquals(config, loaded.getGraphicsDriverConfig());

        LinearLayout layout2 = new LinearLayout(context);
        new GraphicsDriverPicker(layout2, loaded.getGraphicsDriver(), loaded.getGraphicsDriverConfig());
        Dialog dialog2 = openVirGLDialog(layout2);
        Spinner version2 = dialog2.findViewById(R.id.SVersion);
        CheckBox core2 = dialog2.findViewById(R.id.CBCoreProfile);
        assertEquals("4.3", version2.getSelectedItem().toString());
        assertTrue("core profile must stay checked", core2.isChecked());
    }

    @Test
    public void versionsAbove43AreCappedForOldContainers() {
        // Builds before the cap could save 4.4-4.6; virgl has no ARB_buffer_storage, so they must run as 4.3.
        Context context = themedContext();
        LinearLayout layout = new LinearLayout(context);
        new GraphicsDriverPicker(layout, "vortek,virgl", "|glVersion=4.6,disableVertexArrayBGRA=1,coreProfile=1");
        Dialog dialog = openVirGLDialog(layout);
        assertEquals("4.3", ((Spinner)dialog.findViewById(R.id.SVersion)).getSelectedItem().toString());

        EnvVars envVars = new EnvVars();
        VirGLConfigDialog.setEnvVars(new KeyValueSet("glVersion=4.6,coreProfile=1"), envVars);
        assertEquals("4.3FC", envVars.get("MESA_GL_VERSION_OVERRIDE"));
        envVars = new EnvVars();
        VirGLConfigDialog.setEnvVars(new KeyValueSet(""), envVars);
        assertEquals(VirGLConfigDialog.DEFAULT_GL_VERSION, envVars.get("MESA_GL_VERSION_OVERRIDE"));
    }

    @Test
    public void vkd3dConfigSurvivesWhenDirect3DConfigIsEmpty() {
        Context context = themedContext();
        GraphicsDriverPicker graphicsDriverPicker = new GraphicsDriverPicker(new LinearLayout(context), "vortek,virgl", "");

        LinearLayout layout = new LinearLayout(context);
        DXWrapperPicker picker = new DXWrapperPicker(layout, graphicsDriverPicker, "dxvk", "");
        layout.getChildAt(1).setTag("version=2.14.1,featureLevel=12.1");

        String config = picker.getDXWrapperConfig();
        System.out.println("dxwrapperConfig: [" + config + "]");
        KeyValueSet[] configs = DXWrappers.parseConfigs(picker.getDXWrapper(), config);
        assertEquals("", configs[0].toString());
        assertEquals("12.1", configs[1].get("featureLevel"));
    }
}
