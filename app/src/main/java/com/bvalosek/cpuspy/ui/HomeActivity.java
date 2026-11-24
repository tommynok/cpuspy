//-----------------------------------------------------------------------------
//
// (C) Brandon Valosek, 2011 <bvalosek@gmail.com>
//
//-----------------------------------------------------------------------------

package com.bvalosek.cpuspy.ui;

// imports
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bvalosek.cpuspy.*;
import com.bvalosek.cpuspy.CpuStateMonitor.CpuState;
import com.bvalosek.cpuspy.WakelockMonitor;
import com.bvalosek.cpuspy.WakelockMonitor.WakelockEntry;
import com.bvalosek.cpuspy.porter.PorterClient;
import rikka.shizuku.Shizuku;

import android.util.Log;

/** main activity class */
public class HomeActivity extends Activity
{
    private static final String TAG = "CpuSpy";

    public static final int SHIZUKU_REQ_CODE = 4001;
    public static final int PORTER_REQ_CODE  = 4002;

    private CpuSpyApp _app = null;

    // the views
    private CpuStatesChartView _uiStatesChart = null;
    private LinearLayout    _uiStatesView = null;
    private TextView        _uiAdditionalStates = null;
    private TextView        _uiTotalStateTime = null;
    private TextView        _uiHeaderAdditionalStates = null;
    private TextView        _uiHeaderTotalStateTime = null;
    private TextView        _uiStatesWarning = null;
    private TextView        _uiKernelString = null;

    // pages
    private View            _uiPageFreq = null;
    private View            _uiPageWakelocks = null;
    private WakelocksView   _uiWakelocksView = null;
    private TextView        _uiWakelocksWarning = null;
    private View            _uiWakelocksLoading = null;
    private View            _uiWakelocksFilterBar = null;
    private ImageView       _uiFilterIcon = null;
    private TextView        _uiFilterText = null;
    private boolean         _showingWakelocks = false;
    private boolean         _hasRootAccess = false;
    private boolean         _filterAppsOnly = true;
    private List<WakelockEntry> _allWakelockEntries = new ArrayList<>();

    /** whether or not we're updating the data in the background */
    private boolean     _updatingData = false;
    /** whether or not we're loading wakelocks in the background */
    private boolean     _updatingWakelocks = false;

    public static boolean DEMO_MODE = false;

    private final Shizuku.OnBinderReceivedListener mShizukuBinderListener = new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            Log.d(TAG, "Shizuku binder received");
            onPrivilegesChanged();
        }
    };

    private final Shizuku.OnBinderDeadListener mShizukuDeadListener = new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            Log.d(TAG, "Shizuku binder dead");
            onPrivilegesChanged();
        }
    };

    private final Shizuku.OnRequestPermissionResultListener mShizukuPermissionListener = new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            Log.d(TAG, "Shizuku permission result: req=" + requestCode + ", res=" + grantResult);
            onPrivilegesChanged();
        }
    };

    private final PorterClient.OnBinderReceivedListener mPorterBinderListener = new PorterClient.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            Log.d(TAG, "Porter binder received");
            onPrivilegesChanged();
        }
    };

    private final PorterClient.OnBinderLostListener mPorterLostListener = new PorterClient.OnBinderLostListener() {
        @Override
        public void onBinderLost() {
            Log.d(TAG, "Porter binder lost");
            onPrivilegesChanged();
        }
    };

    private final PorterClient.OnPermissionResultListener mPorterPermissionListener = new PorterClient.OnPermissionResultListener() {
        @Override
        public void onPermissionResult(int requestCode, boolean granted) {
            Log.d(TAG, "Porter permission result: req=" + requestCode + ", granted=" + granted);
            onPrivilegesChanged();
        }
    };

    private void onPrivilegesChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                _hasRootAccess = WakelockMonitor.isElevatedAvailable();
                invalidateOptionsMenu();
                if (_showingWakelocks && _hasRootAccess) {
                    refreshWakelocks();
                }
            }
        });
    }

    private boolean isDarkTheme() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String themeMode = prefs.getString("theme_mode", "system");

        if ("dark".equals(themeMode)) {
            return true;
        } else if ("light".equals(themeMode)) {
            return false;
        } else {
            int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return (nightModeFlags == Configuration.UI_MODE_NIGHT_YES);
        }
    }

    /** Apply chosen theme before views are created */
    private void applyTheme() {
        try {
            boolean isDark = isDarkTheme();
            if (isDark) {
                setTheme(R.style.Theme_CpuSpy_Dark);
            } else {
                setTheme(R.style.Theme_CpuSpy_Light);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying theme", e);
        }
    }

    /** Ensure status bar & navigation bar icons match the theme */
    private void updateSystemBars() {
        try {
            boolean isDark = isDarkTheme();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                View decorView = getWindow().getDecorView();
                int flags = decorView.getSystemUiVisibility();
                if (isDark) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (isDark) {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }
                decorView.setSystemUiVisibility(flags);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().setStatusBarColor(isDark ? 0xFF121212 : 0xFFF8F9FA);
                getWindow().setNavigationBarColor(isDark ? 0xFF121212 : 0xFFF8F9FA);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating system bars", e);
        }
    }

    /** Initialize the Activity */
    @Override public void onCreate(Bundle savedInstanceState)
    {
        applyTheme();
        super.onCreate(savedInstanceState);

        try {
            setContentView(R.layout.home_layout);
            updateSystemBars();
            _app = (CpuSpyApp)getApplicationContext();
            findViews();

            setTitle("CPU Spy NG");
            _filterAppsOnly = PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean("filter_apps_only", true);

            try {
                PorterClient.init(this);
                Shizuku.addBinderReceivedListenerSticky(mShizukuBinderListener);
                Shizuku.addBinderDeadListener(mShizukuDeadListener);
                Shizuku.addRequestPermissionResultListener(mShizukuPermissionListener);

                PorterClient.addBinderReceivedListener(mPorterBinderListener);
                PorterClient.addBinderLostListener(mPorterLostListener);
                PorterClient.addPermissionResultListener(mPorterPermissionListener);
            } catch (Throwable t) {
                Log.e(TAG, "Error initializing Porter/Shizuku", t);
            }

            checkElevatedAccess();

            // see if we're updating data during a config change (rotate screen)
            if (savedInstanceState != null) {
                _updatingData = savedInstanceState.getBoolean("updatingData");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
            finish(); // Close activity if we can't even initialize
        }

        try {
            DEMO_MODE = getIntent().getBooleanExtra("demo_mode", false);
        } catch (Exception e) {
            Log.e(TAG, "Error reading demo extra", e);
        }

        if (BuildConfig.DEBUG && DEMO_MODE)
        {
            _app.getCpuStateMonitor().setDemoMode();
        }
    }

    /** When the activity is about to change orientation */
    @Override public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            outState.putBoolean("updatingData", _updatingData);
        } catch (Exception e) {
            Log.e(TAG, "Error in onSaveInstanceState", e);
        }
    }

    /** Update the view when the application regains focus */
    @Override public void onResume () {
        super.onResume();
        updateSystemBars();
        try {
            PorterClient.fetchBinderFromManager(this);
            checkElevatedAccess();
            refreshData();
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume", e);
        }
        if (BuildConfig.DEBUG && DEMO_MODE)
        {
            _app.getCpuStateMonitor().setDemoMode();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(mShizukuBinderListener);
            Shizuku.removeBinderDeadListener(mShizukuDeadListener);
            Shizuku.removeRequestPermissionResultListener(mShizukuPermissionListener);

            PorterClient.removeBinderReceivedListener(mPorterBinderListener);
            PorterClient.removeBinderLostListener(mPorterLostListener);
            PorterClient.removePermissionResultListener(mPorterPermissionListener);
        } catch (Throwable t) {
            Log.e(TAG, "Error removing Porter/Shizuku listeners in onDestroy", t);
        }
        super.onDestroy();
    }

    /** Check whether device has elevated privileges (Root, Shizuku, or Porter) */
    private void checkElevatedAccess() {
        _hasRootAccess = WakelockMonitor.isElevatedAvailable();
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                if (WakelockMonitor.isShizukuGranted() || WakelockMonitor.isPorterGranted()) {
                    return true;
                }
                return WakelockMonitor.probeRootFast();
            }
            @Override
            protected void onPostExecute(Boolean hasElevated) {
                _hasRootAccess = hasElevated || WakelockMonitor.isElevatedAvailable();
                invalidateOptionsMenu();
            }
        }.execute();
    }

    /** Map all of the UI elements to member variables */
    private void findViews() {
        try {
            _uiStatesChart = (CpuStatesChartView)findViewById(R.id.ui_states_chart);
            _uiStatesView = (LinearLayout)findViewById(R.id.ui_states_view);
            _uiKernelString = (TextView)findViewById(R.id.ui_kernel_string);
            _uiAdditionalStates = (TextView)findViewById(
                    R.id.ui_additional_states);
            _uiHeaderAdditionalStates = (TextView)findViewById(
                    R.id.ui_header_additional_states);
            _uiHeaderTotalStateTime = (TextView)findViewById(
                    R.id.ui_header_total_state_time);
            _uiStatesWarning = (TextView)findViewById(R.id.ui_states_warning);
            _uiTotalStateTime = (TextView)findViewById(R.id.ui_total_state_time);

            _uiPageFreq         = findViewById(R.id.ui_page_freq);
            _uiPageWakelocks    = findViewById(R.id.ui_page_wakelocks);
            _uiWakelocksView    = (WakelocksView)findViewById(R.id.ui_wakelocks_view);
            _uiWakelocksWarning = (TextView)findViewById(R.id.ui_wakelocks_warning);
            _uiWakelocksLoading = findViewById(R.id.ui_wakelocks_loading);
            _uiWakelocksFilterBar = findViewById(R.id.ui_wakelocks_filter_bar);
            _uiFilterIcon       = (ImageView)findViewById(R.id.ui_filter_icon);
            _uiFilterText       = (TextView)findViewById(R.id.ui_filter_text);
            if (_uiWakelocksFilterBar != null) {
                _uiWakelocksFilterBar.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleFilter();
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding views", e);
        }
    }

    /** Show Frequencies page */
    private void showFrequenciesView() {
        _showingWakelocks = false;
        if (_uiPageFreq != null) _uiPageFreq.setVisibility(View.VISIBLE);
        if (_uiPageWakelocks != null) _uiPageWakelocks.setVisibility(View.GONE);
        setTitle("CPU Spy NG");
        invalidateOptionsMenu();
    }

    /** Show Wakelocks page */
    private void showWakelocksView() {
        _showingWakelocks = true;
        if (_uiPageFreq != null) _uiPageFreq.setVisibility(View.GONE);
        if (_uiPageWakelocks != null) _uiPageWakelocks.setVisibility(View.VISIBLE);
        setTitle("CPU Spy NG");
        invalidateOptionsMenu();

        // If elevated access is not yet granted, prompt Shizuku or Porter if running
        if (!WakelockMonitor.isElevatedAvailable()) {
            if (WakelockMonitor.isShizukuRunning() && !WakelockMonitor.isShizukuGranted()) {
                try {
                    Shizuku.requestPermission(SHIZUKU_REQ_CODE);
                } catch (Throwable ignored) {}
            } else if (PorterClient.isAlive() && !WakelockMonitor.isPorterGranted()) {
                try {
                    PorterClient.requestPermission(PORTER_REQ_CODE);
                } catch (Throwable ignored) {}
            }
        }

        refreshWakelocks();
    }

    /** Handle Android back button: if on Wakelocks, return to Frequencies */
    @Override public void onBackPressed() {
        if (_showingWakelocks) {
            showFrequenciesView();
            invalidateOptionsMenu();
            return;
        }
        super.onBackPressed();
    }

    /** Update menu icons and states (alarm / chart, tint for current theme, enabled if root) */
    private void updateMenuIcons(Menu menu) {
        if (menu == null) return;
        boolean dark = isDarkTheme();
        int iconColor = dark ? 0xFFFFFFFF : 0xFF202124;

        MenuItem wakelockItem = menu.findItem(R.id.menu_wakelocks);
        if (wakelockItem != null) {
            int iconRes = _showingWakelocks ? R.drawable.ic_menu_chart : R.drawable.ic_menu_alarm;
            Drawable icon = getDrawable(iconRes);
            if (icon != null) {
                icon = icon.mutate();
                icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
                icon.setAlpha(_hasRootAccess ? 255 : 100);
                wakelockItem.setIcon(icon);
            }
            wakelockItem.setTitle(_showingWakelocks ? R.string.menu_frequencies : R.string.menu_wakelocks);
            wakelockItem.setEnabled(true);
        }

        MenuItem filterItem = menu.findItem(R.id.menu_filter);
        if (filterItem != null) {
            filterItem.setVisible(_showingWakelocks);
            if (_showingWakelocks) {
                Drawable icon = getDrawable(R.drawable.ic_menu_filter);
                if (icon != null) {
                    icon = icon.mutate();
                    icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
                    icon.setAlpha(_filterAppsOnly ? 255 : 120);
                    filterItem.setIcon(icon);
                }
                filterItem.setTitle(_filterAppsOnly ? R.string.filter_apps_only : R.string.filter_all_wakelocks);
            }
        }

        MenuItem refreshItem = menu.findItem(R.id.menu_refresh);
        if (refreshItem != null && refreshItem.getIcon() != null) {
            Drawable icon = refreshItem.getIcon().mutate();
            icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
            refreshItem.setIcon(icon);
        }
    }

    /** called when we want to inflate the menu */
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        try {
            MenuInflater inflater = getMenuInflater();
            inflater.inflate(R.menu.home_menu, menu);
            updateMenuIcons(menu);
        } catch (Exception e) {
            Log.e(TAG, "Error creating menu", e);
        }
        return true;
    }

    /** Update menu icons and states (alarm / chart, enabled if root) */
    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        try {
            updateMenuIcons(menu);
        } catch (Exception e) {
            Log.e(TAG, "Error preparing menu", e);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    /** Show theme selector dialog */
    private void showThemeDialog() {
        try {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            final String currentTheme = prefs.getString("theme_mode", "system");

            int checkedItem = 0;
            if ("light".equals(currentTheme)) {
                checkedItem = 1;
            } else if ("dark".equals(currentTheme)) {
                checkedItem = 2;
            }

            final String[] themeValues = new String[] { "system", "light", "dark" };
            final String[] themeTitles = new String[] {
                getString(R.string.theme_system),
                getString(R.string.theme_light),
                getString(R.string.theme_dark)
            };

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.dialog_theme_title);
            builder.setSingleChoiceItems(themeTitles, checkedItem, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        String selected = themeValues[which];
                        if (!selected.equals(currentTheme)) {
                            prefs.edit().putString("theme_mode", selected).apply();
                            dialog.dismiss();
                            recreate();
                        } else {
                            dialog.dismiss();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error saving theme preference", e);
                        dialog.dismiss();
                    }
                }
            });
            builder.setNegativeButton(android.R.string.cancel, null);
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing theme dialog", e);
        }
    }

    /** Show About & Quick Help dialog */
    private void showAboutDialog() {
        try {
            String versionName = "2.1.0";
            try {
                versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {}

            String gitHash = BuildConfig.GitHash;
            String versionText = "CPU Spy NG v" + versionName + " (" + gitHash + ")";
            String message = getString(R.string.about_text, versionText);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.menu_about);
            builder.setMessage(message);
            builder.setPositiveButton(android.R.string.ok, null);
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing about dialog", e);
        }
    }

    /** called to handle a menu event */
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        try {
            int itemId = item.getItemId();

            if (itemId == R.id.menu_wakelocks) {
                if (_showingWakelocks) {
                    showFrequenciesView();
                } else {
                    showWakelocksView();
                }
                invalidateOptionsMenu();
                return true;
            }
            else if (itemId == R.id.menu_filter) {
                toggleFilter();
                return true;
            }
            else if (itemId == R.id.menu_refresh) {
                if (_showingWakelocks) {
                    refreshWakelocks();
                } else {
                    refreshData();
                }
                return true;
            }
            else if (itemId == R.id.menu_reset) {
                try {
                    _app.getCpuStateMonitor().setOffsets();
                } catch (Exception e) {
                    Log.e(TAG, "Error resetting timers", e);
                }
                _app.saveOffsets();
                updateView();
                return true;
            }
            else if (itemId == R.id.menu_restore) {
                _app.getCpuStateMonitor().removeOffsets();
                _app.saveOffsets();
                updateView();
                return true;
            }
            else if (itemId == R.id.menu_theme) {
                showThemeDialog();
                return true;
            }
            else if (itemId == R.id.menu_about) {
                showAboutDialog();
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling menu item", e);
        }
        return super.onOptionsItemSelected(item);
    }

    /** Generate and update all UI elements */
    public void updateView() {
        /** Get the CpuStateMonitor from the app, and iterate over all states,
         * creating a row if the duration is > 0 or otherwise marking it in
         * extraStates (missing) */
        try {
            CpuStateMonitor monitor = _app.getCpuStateMonitor();
            if (_uiStatesView != null) {
                _uiStatesView.removeAllViews();
            }
            List<CpuState> activeStates = new ArrayList<CpuState>();
            List<String> extraStates = new ArrayList<String>();

            for (CpuState state : monitor.getStates()) {
                try {
                    if (state.duration > 0) {
                        activeStates.add(state);
                    } else {
                        if (state.freq == 0) {
                            extraStates.add(getString(R.string.state_deep_sleep));
                        } else {
                            extraStates.add(state.freq/1000 + " " + getString(R.string.unit_mhz));
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing state row", e);
                }
            }

            if (monitor.getStates().size() == 0) {
                _uiStatesWarning.setVisibility(View.VISIBLE);
                _uiHeaderTotalStateTime.setVisibility(View.GONE);
                _uiTotalStateTime.setVisibility(View.GONE);
                if (_uiStatesView != null) _uiStatesView.setVisibility(View.GONE);
                if (_uiStatesChart != null) _uiStatesChart.setVisibility(View.GONE);
            } else {
                _uiStatesWarning.setVisibility(View.GONE);
                _uiHeaderTotalStateTime.setVisibility(View.VISIBLE);
                _uiTotalStateTime.setVisibility(View.VISIBLE);
                if (_uiStatesChart != null) {
                    _uiStatesChart.setVisibility(View.VISIBLE);
                    _uiStatesChart.setData(activeStates, monitor.getTotalStateTime(), this);
                }
            }

            long totTime = monitor.getTotalStateTime() / 100;
            _uiTotalStateTime.setText(sToString(totTime));

            if (extraStates.size() > 0) {
                int n = 0;
                String str = "";

                for (String s : extraStates) {
                    if (n++ > 0)
                        str += ", ";
                    str += s;
                }

                _uiAdditionalStates.setVisibility(View.VISIBLE);
                _uiHeaderAdditionalStates.setVisibility(View.VISIBLE);
                _uiAdditionalStates.setText(str);
            } else {
                _uiAdditionalStates.setVisibility(View.GONE);
                _uiHeaderAdditionalStates.setVisibility(View.GONE);
            }

            _uiKernelString.setText(_app.getKernelVersion());
        } catch (Exception e) {
            Log.e(TAG, "Error updating view", e);
        }
    }

    /** Attempt to update the time-in-state info */
    public void refreshData() {
        try {
            if (!_updatingData) {
                new RefreshStateDataTask().execute((Void)null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing data", e);
        }
    }

    /** Load wakelock data in background */
    private void refreshWakelocks() {
        try {
            if (!_updatingWakelocks) {
                new RefreshWakelocksTask().execute((Void)null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing wakelocks", e);
        }
    }

    /** @return A nicely formatted String representing tSec seconds */
    private static String sToString(long tSec) {
        try {
            long h = (long)Math.floor(tSec / (60*60));
            long m = (long)Math.floor((tSec - h*60*60) / 60);
            long s = tSec % 60;
            String sDur;
            sDur = h + ":";
            if (m < 10)
                sDur += "0";
            sDur += m + ":";
            if (s < 10)
                sDur += "0";
            sDur += s;

            return sDur;
        } catch (Exception e) {
            return "0:00:00";
        }
    }

    /**
     * @return a View that correpsonds to a CPU freq state row as specified
     * by the state parameter
     */
    private View generateStateRow(CpuState state, ViewGroup parent) {
        try {
            LayoutInflater inf = LayoutInflater.from(this);
            LinearLayout theRow = (LinearLayout)inf.inflate(
                    R.layout.state_row, parent, false);

            CpuStateMonitor monitor = _app.getCpuStateMonitor();
            float per = (float)state.duration * 100 /
                        monitor.getTotalStateTime();
            String sPer = (int)per + "%";

            String sFreq;
            if (state.freq == 0) {
                sFreq = getString(R.string.state_deep_sleep);
            } else {
                sFreq = state.freq / 1000 + " " + getString(R.string.unit_mhz);
            }

            long tSec = state.duration / 100;
            String sDur = sToString(tSec);

            TextView freqText = (TextView)theRow.findViewById(R.id.ui_freq_text);
            TextView durText = (TextView)theRow.findViewById(
                    R.id.ui_duration_text);
            TextView perText = (TextView)theRow.findViewById(R.id.ui_percentage_text);
            ProgressBar bar = (ProgressBar)theRow.findViewById(R.id.ui_bar);

            freqText.setText(sFreq);
            perText.setText(sPer);
            durText.setText(sDur);
            bar.setProgress((int)per);

            parent.addView(theRow);
            return theRow;
        } catch (Exception e) {
            Log.e(TAG, "Error generating state row", e);
            return null;
        }
    }

    /** Toggle filter between Apps only and All wakelocks */
    private void toggleFilter() {
        _filterAppsOnly = !_filterAppsOnly;
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("filter_apps_only", _filterAppsOnly).apply();
        Toast.makeText(this,
                _filterAppsOnly ? R.string.filter_apps_only : R.string.filter_all_wakelocks,
                Toast.LENGTH_SHORT).show();
        updateWakelocksDisplay();
        invalidateOptionsMenu();
    }

    /** Update WakelocksView applying current filter (Apps only vs All) */
    private void updateWakelocksDisplay() {
        if (_uiWakelocksView == null) return;
        List<WakelockEntry> displayList = new ArrayList<>();
        for (WakelockEntry e : _allWakelockEntries) {
            if (_filterAppsOnly) {
                if (e.isApp) {
                    displayList.add(e);
                }
            } else {
                displayList.add(e);
            }
        }
        _uiWakelocksView.setEntries(displayList, this);

        if (_uiFilterText != null) {
            _uiFilterText.setText(_filterAppsOnly ? R.string.filter_apps_only : R.string.filter_all_wakelocks);
        }
        if (_uiFilterIcon != null) {
            boolean dark = isDarkTheme();
            int iconColor = dark ? 0xFF90CAF9 : 0xFF1976D2;
            _uiFilterIcon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
            _uiFilterIcon.setAlpha(_filterAppsOnly ? 255 : 140);
        }
    }

    /** Load wakelock entries off the UI thread */
    protected class RefreshWakelocksTask extends AsyncTask<Void, Void, List<WakelockEntry>> {

        @Override protected void onPreExecute() {
            _updatingWakelocks = true;
            if (_uiWakelocksLoading != null) {
                _uiWakelocksLoading.setVisibility(View.VISIBLE);
            }
            if (_uiWakelocksWarning != null) {
                _uiWakelocksWarning.setVisibility(View.GONE);
            }
            if (_uiWakelocksFilterBar != null) {
                _uiWakelocksFilterBar.setVisibility(View.GONE);
            }
        }

        @Override protected List<WakelockEntry> doInBackground(Void... v) {
            WakelockMonitor monitor = new WakelockMonitor();
            monitor.updateEntries(HomeActivity.this);
            return monitor.getEntries();
        }

        @Override protected void onPostExecute(List<WakelockEntry> entries) {
            try {
                _updatingWakelocks = false;
                if (_uiWakelocksLoading != null) {
                    _uiWakelocksLoading.setVisibility(View.GONE);
                }
                if (entries == null || entries.isEmpty()) {
                    _allWakelockEntries.clear();
                    if (_uiWakelocksFilterBar != null) {
                        _uiWakelocksFilterBar.setVisibility(View.GONE);
                    }
                    if (_uiWakelocksWarning != null) {
                        _uiWakelocksWarning.setText(R.string.wakelocks_no_root);
                        _uiWakelocksWarning.setVisibility(View.VISIBLE);
                    }
                    if (_uiWakelocksView != null) {
                        _uiWakelocksView.setVisibility(View.GONE);
                    }
                } else {
                    _hasRootAccess = true;
                    _allWakelockEntries = entries;
                    invalidateOptionsMenu();
                    if (_uiWakelocksWarning != null)
                        _uiWakelocksWarning.setVisibility(View.GONE);
                    if (_uiWakelocksFilterBar != null) {
                        _uiWakelocksFilterBar.setVisibility(View.VISIBLE);
                    }
                    if (_uiWakelocksView != null) {
                        _uiWakelocksView.setVisibility(View.VISIBLE);
                        updateWakelocksDisplay();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating wakelocks view", e);
            }
        }
    }

    /** Keep updating the state data off the UI thread for slow devices */
    protected class RefreshStateDataTask extends AsyncTask<Void, Void, Void> {

        /** Stuff to do on a seperate thread */
        @Override protected Void doInBackground(Void... v) {
            try {
                CpuStateMonitor monitor = _app.getCpuStateMonitor();
                monitor.updateStates();
                _app.updateKernelVersion();
            } catch (Exception e) {
                Log.e(TAG, "Problem getting CPU states", e);
            }

            return null;
        }

        /** Executed on the UI thread right before starting the task */
        @Override protected void onPreExecute() {
            try {
                log("starting data update");
                _updatingData = true;
            } catch (Exception e) {
                Log.e(TAG, "Error in onPreExecute", e);
            }
        }

        /** Executed on UI thread after task */
        @Override protected void onPostExecute(Void v) {
            try {
                log("finished data update");
                _updatingData = false;
                updateView();
            } catch (Exception e) {
                Log.e(TAG, "Error in onPostExecute", e);
            }
        }
    }

    /** logging */
    private void log(String s) {
        try {
            Log.d(TAG, s);
        } catch (Exception e) {
            // Ignore logging errors
        }
    }
}
