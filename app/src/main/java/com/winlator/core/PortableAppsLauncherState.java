package com.winlator.core;

import com.winlator.container.Container;
import com.winlator.container.Drive;

import java.io.File;
import java.util.ArrayList;

// PortableApps.com launchers keep Data/PortableApps.comLauncherRuntimeData-<AppID>.ini while running. When Wine
// is killed the file stays and the next launch only shows "did not close properly" and quits, so remove it
// before any Windows program starts.
public abstract class PortableAppsLauncherState {
    private static final String RUNTIME_DATA_PREFIX = "PortableApps.comLauncherRuntimeData-";
    private static final int MAX_DEPTH = 2;

    public static void removeStale(Container container) {
        ArrayList<File> roots = new ArrayList<>();
        roots.add(new File(container.getRootDir(), ".wine/drive_c"));
        for (Drive drive : container.drivesIterator()) roots.add(new File(drive.path));
        for (File root : roots) scan(root, 0);
    }

    private static void scan(File dir, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isDirectory() || FileUtils.isSymlink(file)) continue;
            if (file.getName().equals("Data")) {
                removeRuntimeData(file);
            }
            else if (depth < MAX_DEPTH) scan(file, depth + 1);
        }
    }

    private static void removeRuntimeData(File dataDir) {
        File[] files = dataDir.listFiles((dir, name) -> name.startsWith(RUNTIME_DATA_PREFIX) && name.endsWith(".ini"));
        if (files == null) return;
        for (File file : files) file.delete();
    }
}
