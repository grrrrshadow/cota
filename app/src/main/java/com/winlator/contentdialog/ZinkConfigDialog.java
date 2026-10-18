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
    // Bruno's build, kept for games that worked with it; its version under Vortek was always overridden to 3.3.
    public static final String LEGACY_VERSION = "22.2.5";
    public static final String[] VERSIONS = {DefaultVersion.ZINK, LEGACY_VERSION};
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
        envVars.put("ZINK_CONTEXT_THREADED", "1");

        String glVersion = getGLVersion(config);
        if (!glVersion.isEmpty()) {
            envVars.put("MESA_GL_VERSION_OVERRIDE", glVersion);
        }
        else if (vortek && getVersion(config).equals(LEGACY_VERSION)) {
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");
        }
        // 26.2.5 on "Auto" reports what zink can really do on this GPU.
    }
}
