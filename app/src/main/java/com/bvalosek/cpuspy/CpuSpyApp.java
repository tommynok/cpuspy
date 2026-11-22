//-----------------------------------------------------------------------------
//
// (C) Brandon Valosek, 2011 <bvalosek@gmail.com>
//
//-----------------------------------------------------------------------------

package com.bvalosek.cpuspy;

// imports
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import com.bvalosek.cpuspy.CpuStateMonitor.CpuState;
import com.bvalosek.cpuspy.CpuStateMonitor.CpuStateMonitorException;

/** main application class */
public class CpuSpyApp extends Application {

    private static final String KERNEL_VERSION_PATH = "/proc/version";

    private static final String TAG = "CpuSpyApp";

    private static final String PREF_NAME = "CpuSpyPreferences";
    private static final String PREF_OFFSETS = "offsets";

    /** the long-living object used to monitor the system frequency states */
    private CpuStateMonitor _monitor = new CpuStateMonitor();

    private String _kernelVersion = "";

    /**
     * On application start, load the saved offsets and stash the
     * current kernel version string
     */
    @Override public void onCreate(){
        super.onCreate();
        try {
            loadOffsets();
        } catch (Exception e) {
            Log.e(TAG, "Error loading offsets", e);
        }
        try {
            updateKernelVersion();
        } catch (Exception e) {
            Log.e(TAG, "Error updating kernel version", e);
        }
    }

    /** @return the kernel version string */
    public String getKernelVersion() {
        if (_kernelVersion == null || _kernelVersion.trim().isEmpty()) {
            updateKernelVersion();
        }
        return _kernelVersion;
    }

    /** @return the internal CpuStateMonitor object */
    public CpuStateMonitor getCpuStateMonitor() {
        return _monitor;
    }

    /**
     * Load the saved string of offsets from preferences and put it into
     * the state monitor
     */
    public void loadOffsets() {
        try {
            SharedPreferences settings = getSharedPreferences(
                    PREF_NAME, MODE_PRIVATE);
            String prefs = settings.getString (PREF_OFFSETS, "");

            if (prefs == null || prefs.length() < 1) {
                return;
            }

            Map<Long, Long> offsets = new HashMap<Long, Long>();
            String[] sOffsets = prefs.split(",");
            for (String offset : sOffsets) {
                try {
                    String[] parts = offset.split(" ");
                    if (parts.length >= 2) {
                        long freq = Long.parseLong(parts[0].trim());
                        long duration = Long.parseLong(parts[1].trim());

                        // Disregard implausible saved values
                        if (freq >= 0 && duration >= 0) {
                            offsets.put(freq, duration);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Skipping malformed offset: " + offset, e);
                }
            }

            _monitor.setOffsets(offsets);
        } catch (Exception e) {
            Log.e(TAG, "Error loading offsets", e);
        }
    }

    /**
     * Save the state-time offsets as a string
     * e.g. "100 24, 200 251, 500 124 etc
     */
    public void saveOffsets() {
        try {
            SharedPreferences settings = getSharedPreferences(
                    PREF_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = settings.edit();

            String str = "";
            for (Map.Entry<Long, Long> entry :
                    _monitor.getOffsets().entrySet()) {
                str += entry.getKey() + " " + entry.getValue() + ",";
            }

            editor.putString(PREF_OFFSETS, str);
            editor.commit();
        } catch (Exception e) {
            Log.e(TAG, "Error saving offsets", e);
        }
    }

    /** Try to read the kernel version string from the proc filesystem or system APIs */
    public String updateKernelVersion() {
        _kernelVersion = "";
        try {
            InputStream is = new FileInputStream(KERNEL_VERSION_PATH);
            InputStreamReader ir = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(ir);

            String line;
            while ((line = br.readLine()) != null) {
                _kernelVersion = line;
            }

            is.close();
        } catch (Exception e) {
            Log.e(TAG, "Problem reading kernel version file", e);
        }

        // On Android 8+ (API 26+), SELinux restricts access to /proc/version for untrusted apps.
        // Fallback to Os.uname() and system properties which work reliably across all Android versions.
        if (_kernelVersion == null || _kernelVersion.trim().isEmpty()) {
            try {
                android.system.StructUtsname uname = android.system.Os.uname();
                if (uname != null) {
                    StringBuilder sb = new StringBuilder();
                    if (uname.sysname != null) sb.append(uname.sysname).append(" ");
                    if (uname.release != null) sb.append(uname.release);
                    if (uname.machine != null) sb.append(" (").append(uname.machine).append(")");
                    if (uname.version != null && !uname.version.isEmpty()) {
                        sb.append("\n").append(uname.version);
                    }
                    _kernelVersion = sb.toString().trim();
                }
            } catch (Throwable t) {
                Log.e(TAG, "Problem getting uname", t);
            }
        }

        if (_kernelVersion == null || _kernelVersion.trim().isEmpty()) {
            try {
                String osVer = System.getProperty("os.version");
                if (osVer != null && !osVer.isEmpty()) {
                    _kernelVersion = "Linux " + osVer;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Problem reading os.version", t);
            }
        }

        if (_kernelVersion == null || _kernelVersion.trim().isEmpty()) {
            try {
                _kernelVersion = getString(R.string.kernel_info_unavailable);
            } catch (Exception e) {
                _kernelVersion = "Unavailable";
            }
        }

        return _kernelVersion;
    }
}
