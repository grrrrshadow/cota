package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.EnvVars;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;

import java.util.ArrayList;

public class VirGLConfigDialog extends ContentDialog {
    public static final String DEFAULT_GL_VERSION = "3.1";
    // The host has no ARB_buffer_storage, so above 4.3 apps such as OpenTTD switch to persistent mapping and fail.
    public static final String MAX_GL_VERSION = "4.3";

    public VirGLConfigDialog(final View anchor) {
        super(anchor.getContext(), R.layout.virgl_config_dialog);
        Context context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("VirGL "+context.getString(R.string.configuration));

        final Spinner sGLVersion = findViewById(R.id.SVersion);
        final CheckBox cbDisableVertexArrayBGRA = findViewById(R.id.CBDisableVertexArrayBGRA);
        final CheckBox cbCoreProfile = findViewById(R.id.CBCoreProfile);

        KeyValueSet config = new KeyValueSet(anchor.getTag());
        AppUtils.setSpinnerSelectionFromIdentifier(sGLVersion, getGLVersion(config));
        cbDisableVertexArrayBGRA.setChecked(config.getBoolean("disableVertexArrayBGRA", true));
        cbCoreProfile.setChecked(config.getBoolean("coreProfile", false));

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("glVersion", StringUtils.parseNumber(sGLVersion.getSelectedItem()));
            newConfig.put("disableVertexArrayBGRA", cbDisableVertexArrayBGRA.isChecked() ? "1" : "0");
            newConfig.put("coreProfile", cbCoreProfile.isChecked() ? "1" : "0");
            anchor.setTag(newConfig.toString());
        });
    }

    public static void setEnvVars(KeyValueSet config, EnvVars envVars) {
        ArrayList<String> disabledExtensions = new ArrayList<>();
        disabledExtensions.add("GL_KHR_debug");
        if (config.getBoolean("disableVertexArrayBGRA", true)) disabledExtensions.add("GL_EXT_vertex_array_bgra");

        String mesaExtensionOverride = "";
        for (String disabledExtension : disabledExtensions) {
            mesaExtensionOverride += (!mesaExtensionOverride.isEmpty() ? " " : "")+"-"+disabledExtension;
        }

        if (!mesaExtensionOverride.isEmpty()) envVars.put("MESA_EXTENSION_OVERRIDE", mesaExtensionOverride);

        String glVersion = getGLVersion(config);
        // The version caps core and legacy contexts alike; games that ask for a core context (OpenTTD, WineD3D) get one without "FC".
        // "FC" also turns the legacy contexts into core ones: virgl caps GLSL at 1.40 in legacy contexts on this host.
        boolean coreProfile = config.getBoolean("coreProfile", false) && Float.parseFloat(glVersion) >= 3.0f;
        envVars.put("MESA_GL_VERSION_OVERRIDE", coreProfile ? glVersion+"FC" : glVersion);
    }

    private static String getGLVersion(KeyValueSet config) {
        String glVersion = config.get("glVersion", DEFAULT_GL_VERSION);
        try {
            return Float.parseFloat(glVersion) > Float.parseFloat(MAX_GL_VERSION) ? MAX_GL_VERSION : glVersion;
        }
        catch (NumberFormatException e) {
            return DEFAULT_GL_VERSION;
        }
    }
}