package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;

public class ZinkConfigDialog extends ContentDialog {
    // Bruno's build, kept for games that worked with it.
    public static final String LEGACY_VERSION = "22.2.5";
    public static final String[] VERSIONS = {DefaultVersion.ZINK, LEGACY_VERSION};
    // "Auto" under Vortek, for both versions (Bruno always set it for 22.2.5). Through Vortek, Mali's zink only
    // reaches OpenGL 3.1 by itself (no VK_EXT_depth_clip_enable for ARB_depth_clamp; then no dual-source blending,
    // vertex attribute divisors or 2_10_10_10 vertex formats for 3.3), so core 3.2+ contexts (OpenTTD, WineD3D)
    // would fail. 3.3 lets them start; 4.0 would also promise fp64, which Mali does not have, and OpenTTD only maps
    // buffers persistently from 4.4 on. Vortek fakes some core features (dualSrcBlend, logicOp, fillModeNonSolid,
    // clip/cull distances, multiViewport) and drops them at vkCreateDevice, so a higher version is no more honest.
    // See tools/mesa-zink/vortek-filter-check.sh.
    public static final String VORTEK_GL_VERSION = "3.3";
    // Not "glVersion": the OpenGL slot keeps VirGL's config after switching to Zink until this dialog is confirmed,
    // and VirGL always saves its glVersion (3.1 by default), which would silently cap Zink.
    private static final String GL_VERSION_KEY = "glVersionOverride";

    public ZinkConfigDialog(final View anchor) {
        super(anchor.getContext(), R.layout.zink_config_dialog);
        Context context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("Zink "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sGLVersion = findViewById(R.id.SGLVersion);
        sVersion.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, VERSIONS));

        KeyValueSet config = new KeyValueSet(anchor.getTag());
        AppUtils.setSpinnerSelectionFromValue(sVersion, getVersion(config));
        AppUtils.setSpinnerSelectionFromNumber(sGLVersion, getGLVersion(config));

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("version", sVersion.getSelectedItem());
            // "Auto" is stored as no GL version at all.
            String glVersion = StringUtils.parseNumber(sGLVersion.getSelectedItem());
            if (!glVersion.isEmpty()) newConfig.put(GL_VERSION_KEY, glVersion);
            anchor.setTag(newConfig.toString());
        });
    }

    public static String getVersion(KeyValueSet config) {
        String version = config.get("version", DefaultVersion.ZINK);
        for (String knownVersion : VERSIONS) if (knownVersion.equals(version)) return version;
        return DefaultVersion.ZINK;
    }

    private static String getGLVersion(KeyValueSet config) {
        String glVersion = config.get(GL_VERSION_KEY);
        try {
            return !glVersion.isEmpty() && Float.parseFloat(glVersion) > 0 ? glVersion : "";
        }
        catch (NumberFormatException e) {
            return "";
        }
    }

    public static void setEnvVars(KeyValueSet config, boolean vortek, EnvVars envVars) {
        envVars.put("GALLIUM_DRIVER", "zink");
        // 22.2.5 only threads with this; newer Mesa ignores it and threads by default (GALLIUM_THREAD).
        if (getVersion(config).equals(LEGACY_VERSION)) envVars.put("ZINK_CONTEXT_THREADED", "1");

        String glVersion = getGLVersion(config);
        if (!glVersion.isEmpty()) {
            envVars.put("MESA_GL_VERSION_OVERRIDE", glVersion);
        }
        else if (vortek) {
            envVars.put("MESA_GL_VERSION_OVERRIDE", VORTEK_GL_VERSION);
        }
        // With Turnip, "Auto" reports what zink can really do on the GPU.
    }
}
