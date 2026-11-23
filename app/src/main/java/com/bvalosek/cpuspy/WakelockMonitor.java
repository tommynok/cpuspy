package com.bvalosek.cpuspy;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads wakelock data:
 *   1. Root (su): dumpsys batterystats --checkin (apps + wakeups + running times)
 *      + dumpsys power (currently active locks)
 *   2. Fallback: /sys/class/wakeup/ or /sys/kernel/debug/wakeup_sources
 */
public class WakelockMonitor {

    private static final String TAG = "WakelockMonitor";

    public static class WakelockEntry {
        public String  name;          // tag or driver name
        public String  packageName;   // e.g. "com.google.android.gms", "android", null for kernel
        public String  appName;       // human-readable label ("Google Play services", "Settings")
        public long    totalTimeMs;   // total running time (ms)
        public long    activeSinceMs; // if currently active (ms)
        public long    hitCount;      // wake count (e.g. 164)
        public boolean isActive;
        public boolean isApp = true;  // true = user/system app, false = kernel/hardware driver

        /** Display name suitable for UI */
        public String displayName() {
            if (appName != null && !appName.isEmpty()) return appName;
            if (name == null) return "";
            int colon = name.indexOf(':');
            if (colon > 0 && packageName != null) {
                return name.substring(colon + 1);
            }
            return name;
        }
    }

    private List<WakelockEntry> _entries = new ArrayList<>();

    public List<WakelockEntry> getEntries() {
        return _entries;
    }

    /**
     * Check if root binaries exist on the device (fast check)
     */
    public static boolean isRootAvailable() {
        String[] paths = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
            "/data/adb/ap/bin/su"
        };
        for (String p : paths) {
            try {
                if (new File(p).exists()) return true;
            } catch (Exception ignored) {}
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            int exit = p.waitFor();
            if (exit == 0) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Overload without context for compatibility */
    public void updateEntries() {
        updateEntries(null);
    }

    /**
     * Update entries from all available sources.
     * Call from a background thread (e.g. AsyncTask).
     */
    public void updateEntries(Context context) {
        Map<String, WakelockEntry> appMap = new HashMap<>();
        Map<Integer, String> uidMap = new HashMap<>();

        // Map well-known system UIDs
        uidMap.put(1000, "android");

        // Pre-populate UIDs from installed packages if context is available
        PackageManager pm = null;
        if (context != null) {
            try {
                pm = context.getPackageManager();
            } catch (Exception ignored) {}
        }

        // 1. Try Root session with dumpsys batterystats --checkin + dumpsys power
        boolean rootSuccess = readViaRoot(appMap, uidMap, pm);

        // 2. If batterystats checkin didn't yield apps, fallback to sysfs / dumpsys power
        if (appMap.isEmpty()) {
            readDirectSysfs(appMap);
        }

        // 3. Resolve human-readable app names
        resolveAppNames(appMap, pm);

        List<WakelockEntry> result = new ArrayList<>(appMap.values());

        // Sort:
        // 1) Active right now
        // 2) Apps before kernel drivers
        // 3) By wake count (hitCount) desc
        // 4) By running time desc
        Collections.sort(result, new Comparator<WakelockEntry>() {
            @Override
            public int compare(WakelockEntry a, WakelockEntry b) {
                if (a.isActive != b.isActive) return a.isActive ? -1 : 1;
                if (a.isApp != b.isApp) return a.isApp ? -1 : 1;
                if (a.hitCount != b.hitCount) return Long.compare(b.hitCount, a.hitCount);
                return Long.compare(b.totalTimeMs, a.totalTimeMs);
            }
        });

        _entries = result;
    }

    // -------------------------------------------------------------------------
    // Method 1: Interactive Root Shell (KernelSU / Magisk / APatch)
    // Runs dumpsys batterystats --checkin + dumpsys power in ONE root session.
    // -------------------------------------------------------------------------
    private boolean readViaRoot(Map<String, WakelockEntry> map, Map<Integer, String> uidMap, PackageManager pm) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
            // Try charged checkin first, then general checkin
            writer.write("echo '===BATTERYSTATS_CHECKIN==='\n");
            writer.write("dumpsys batterystats --charged --checkin 2>/dev/null || dumpsys batterystats --checkin 2>/dev/null\n");
            writer.write("echo '===DUMPSYS_POWER==='\n");
            writer.write("dumpsys power 2>/dev/null\n");
            writer.write("echo '===SYSFS_WAKEUP==='\n");
            writer.write("if [ -f /sys/kernel/debug/wakeup_sources ]; then cat /sys/kernel/debug/wakeup_sources; ");
            writer.write("elif [ -d /sys/class/wakeup ]; then for d in /sys/class/wakeup/wakeup*; do ");
            writer.write("if [ -d \"$d\" ]; then echo \"$(cat $d/name 2>/dev/null) $(cat $d/active_count 2>/dev/null) 0 0 0 $(cat $d/active_time_ms 2>/dev/null) $(cat $d/total_time_ms 2>/dev/null)\"; fi; done; fi\n");
            writer.write("echo '===END==='\n");
            writer.write("exit\n");
            writer.flush();
            writer.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int section = 0; // 1 = checkin, 2 = dumpsys power, 3 = sysfs
            boolean inPowerWakeLocks = false;

            while ((line = reader.readLine()) != null) {
                if (line.contains("===BATTERYSTATS_CHECKIN===")) {
                    section = 1;
                    continue;
                } else if (line.contains("===DUMPSYS_POWER===")) {
                    section = 2;
                    continue;
                } else if (line.contains("===SYSFS_WAKEUP===")) {
                    section = 3;
                    continue;
                } else if (line.contains("===END===")) {
                    break;
                }

                if (section == 1) {
                    parseCheckinLine(line, map, uidMap, pm);
                } else if (section == 2) {
                    if (line.contains("Wake Locks:")) {
                        inPowerWakeLocks = true;
                        continue;
                    }
                    if (inPowerWakeLocks) {
                        if (line.trim().isEmpty() || line.startsWith("  Suspend Blockers")) {
                            inPowerWakeLocks = false;
                        } else {
                            parsePowerActiveLine(line, map);
                        }
                    }
                } else if (section == 3) {
                    // Only use sysfs if no app wakelocks found yet
                    if (map.isEmpty()) {
                        parseWakeupSourceLine(line, map);
                    }
                }
            }

            p.waitFor();
            reader.close();
            return !map.isEmpty();
        } catch (Exception e) {
            Log.d(TAG, "Root execution failed: " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Parse Checkin CSV lines from dumpsys batterystats
    // Format: version,uid,category,type,args...
    // -------------------------------------------------------------------------
    private void parseCheckinLine(String line, Map<String, WakelockEntry> map,
                                  Map<Integer, String> uidMap, PackageManager pm) {
        try {
            if (line == null || line.length() < 6) return;
            String[] p = line.split(",");
            if (p.length < 5) return;

            int uid = safeInt(p[1]);
            String cat = p[2].trim();
            String type = p[3].trim();

            // We accept 'l' (since charge), 'u' (unplugged), or 'i' (cumulative)
            if ("apk".equals(type) && p.length >= 6) {
                // p[4] = wakeups count, p[5] = package name
                long wakeups = safeLong(p[4]);
                String pkg = p[5].trim();
                if (!pkg.isEmpty()) {
                    uidMap.put(uid, pkg);
                    WakelockEntry e = map.get(pkg);
                    if (e == null) {
                        e = new WakelockEntry();
                        e.name = pkg;
                        e.packageName = pkg;
                        e.isApp = true;
                        map.put(pkg, e);
                    }
                    e.hitCount += wakeups;
                }
            } else if ("wl".equals(type)) {
                // p[4] = wakelock name
                // Look for "p" (partial wakelock) tag in remaining tokens
                String lockName = p[4].trim();
                long partTime = 0;
                long partCount = 0;
                for (int i = 5; i < p.length; i++) {
                    if ("p".equalsIgnoreCase(p[i].trim())) {
                        partTime = safeLong(p[i - 1]);
                        partCount = (i + 1 < p.length) ? safeLong(p[i + 1]) : 0;
                        break;
                    }
                }

                if (partTime > 0 || partCount > 0) {
                    String pkg = uidMap.get(uid);
                    if (pkg == null && pm != null) {
                        try {
                            String[] pkgs = pm.getPackagesForUid(uid);
                            if (pkgs != null && pkgs.length > 0) {
                                pkg = pkgs[0];
                                uidMap.put(uid, pkg);
                            }
                        } catch (Exception ignored) {}
                    }
                    if (pkg == null) {
                        if (uid == 1000) pkg = "android";
                        else pkg = "uid:" + uid;
                    }

                    WakelockEntry e = map.get(pkg);
                    if (e == null) {
                        e = new WakelockEntry();
                        e.name = pkg;
                        e.packageName = pkg.startsWith("uid:") ? null : pkg;
                        e.isApp = true;
                        map.put(pkg, e);
                    }
                    e.totalTimeMs += partTime;
                    if (e.hitCount == 0) e.hitCount += partCount;
                }
            } else if ("kwl".equals(type) && p.length >= 6) {
                // Kernel wake lock: p[4] = name, p[5] = totalTimeMs, p[6] = count
                String kwlName = p[4].trim();
                long timeMs = safeLong(p[5]);
                long count = (p.length > 6) ? safeLong(p[6]) : 0;
                if (timeMs > 0 || count > 0) {
                    WakelockEntry e = map.get(kwlName);
                    if (e == null) {
                        e = new WakelockEntry();
                        e.name = kwlName;
                        e.isApp = false;
                        map.put(kwlName, e);
                    }
                    e.totalTimeMs += timeMs;
                    e.hitCount += count;
                }
            }
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Parse active wake locks from dumpsys power
    // -------------------------------------------------------------------------
    private void parsePowerActiveLine(String line, Map<String, WakelockEntry> map) {
        try {
            line = line.trim();
            if (line.isEmpty()) return;

            String name = null;
            int q1 = line.indexOf('\'');
            int q2 = line.lastIndexOf('\'');
            if (q1 >= 0 && q2 > q1) {
                name = line.substring(q1 + 1, q2);
            } else {
                return;
            }

            String pkg = null;
            int pkgIdx = line.indexOf("packageName=");
            if (pkgIdx >= 0) {
                String rest = line.substring(pkgIdx + "packageName=".length());
                int space = rest.indexOf(' ');
                pkg = space > 0 ? rest.substring(0, space) : rest.trim();
            } else if (name.startsWith("*alarm*:")) {
                String sub = name.substring("*alarm*:".length());
                int slash = sub.indexOf('/');
                pkg = slash > 0 ? sub.substring(0, slash) : sub;
            } else {
                pkg = extractPackage(name);
            }

            String key = (pkg != null) ? pkg : name;
            WakelockEntry e = map.get(key);
            if (e != null) {
                e.isActive = true;
            } else {
                e = new WakelockEntry();
                e.name = name;
                e.packageName = pkg;
                e.isActive = true;
                e.isApp = (pkg != null);
                if (e.hitCount == 0) e.hitCount = 1;
                map.put(key, e);
            }
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Fallback: Direct sysfs read (/sys/class/wakeup/)
    // -------------------------------------------------------------------------
    private void readDirectSysfs(Map<String, WakelockEntry> map) {
        try {
            File dir = new File("/sys/class/wakeup");
            if (dir.exists() && dir.isDirectory()) {
                File[] wakeups = dir.listFiles();
                if (wakeups != null) {
                    for (File w : wakeups) {
                        if (!w.isDirectory()) continue;
                        String name = readFileFirstLine(new File(w, "name"));
                        if (name == null || name.isEmpty()) continue;
                        long activeCount = safeLong(readFileFirstLine(new File(w, "active_count")));
                        long activeTime  = safeLong(readFileFirstLine(new File(w, "active_time_ms")));
                        long totalTime   = safeLong(readFileFirstLine(new File(w, "total_time_ms")));

                        if (totalTime == 0 && activeTime == 0 && activeCount == 0) continue;

                        WakelockEntry e = new WakelockEntry();
                        e.name = name;
                        e.totalTimeMs = totalTime;
                        e.activeSinceMs = activeTime;
                        e.hitCount = activeCount;
                        e.isActive = (activeTime > 0);
                        e.isApp = false;
                        map.put(name, e);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "direct sysfs read failed: " + e.getMessage());
        }
    }

    private void parseWakeupSourceLine(String line, Map<String, WakelockEntry> map) {
        try {
            String[] f = line.trim().split("\\s+");
            if (f.length < 7) return;

            String name = f[0];
            if ("name".equalsIgnoreCase(name)) return; // skip header

            long hitCount      = safeLong(f[1]);
            long activeSinceMs = safeLong(f[5]);
            long totalTimeMs   = safeLong(f[6]);

            if (hitCount == 0 && totalTimeMs == 0 && activeSinceMs == 0) return;

            WakelockEntry e = new WakelockEntry();
            e.name = name;
            e.hitCount = hitCount;
            e.activeSinceMs = activeSinceMs;
            e.totalTimeMs = totalTimeMs;
            e.isActive = (activeSinceMs > 0);
            e.isApp = false;
            map.put(name, e);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Resolve user-friendly App Names from package names
    // -------------------------------------------------------------------------
    private void resolveAppNames(Map<String, WakelockEntry> map, PackageManager pm) {
        for (WakelockEntry e : map.values()) {
            if (e.packageName == null) {
                if (e.isApp) e.appName = e.name;
                continue;
            }

            if ("android".equals(e.packageName)) {
                e.appName = "Android System";
                continue;
            }

            if (pm != null) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(e.packageName, 0);
                    CharSequence label = pm.getApplicationLabel(ai);
                    if (label != null && label.length() > 0) {
                        e.appName = label.toString();
                    }
                } catch (Exception ignored) {}
            }

            if (e.appName == null) {
                e.appName = e.packageName;
            }
        }
    }

    private static String extractPackage(String name) {
        if (name == null) return null;
        int colon = name.indexOf(':');
        String candidate = colon > 0 ? name.substring(0, colon) : name;
        if (candidate.contains(".") && candidate.length() > 4
                && Character.isLowerCase(candidate.charAt(0))) {
            return candidate;
        }
        return null;
    }

    private static String readFileFirstLine(File file) {
        if (!file.exists() || !file.canRead()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static int safeInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static long safeLong(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }
}
