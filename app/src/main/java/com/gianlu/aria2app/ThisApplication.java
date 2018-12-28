package com.gianlu.aria2app;

import android.preference.PreferenceManager;

import com.gianlu.aria2app.Aria2.Aria2ConfigProvider;
import com.gianlu.aria2app.NetIO.AbstractClient;
import com.gianlu.aria2app.NetIO.ErrorHandler;
import com.gianlu.aria2app.NetIO.Search.SearchApi;
import com.gianlu.aria2app.ProfilesManager.ProfilesManager;
import com.gianlu.aria2app.Services.NotificationService;
import com.gianlu.aria2lib.Aria2Ui;
import com.gianlu.commonutils.Analytics.AnalyticsApplication;
import com.gianlu.commonutils.CommonPK;
import com.gianlu.commonutils.Logging;
import com.gianlu.commonutils.Preferences.Prefs;
import com.gianlu.commonutils.Preferences.PrefsStorageModule;
import com.gianlu.commonutils.Toaster;
import com.llew.huawei.verifier.LoadedApkHuaWei;
import com.yarolegovich.mp.io.MaterialPreferences;

import java.util.HashSet;
import java.util.Set;

import androidx.annotation.NonNull;

public final class ThisApplication extends AnalyticsApplication implements ErrorHandler.Listener {
    public static final boolean DEBUG_UPDATER = false;
    public static final boolean DEBUG_NOTIFICATION = false;
    private final Set<String> checkedVersionFor = new HashSet<>();

    public boolean shouldCheckVersion() {
        try {
            return !checkedVersionFor.contains(ProfilesManager.get(this).getCurrent().id);
        } catch (ProfilesManager.NoCurrentProfileException ex) {
            Logging.log(ex);
            return true;
        }
    }

    public void checkedVersion() {
        try {
            checkedVersionFor.add(ProfilesManager.get(this).getCurrent().id);
        } catch (ProfilesManager.NoCurrentProfileException ex) {
            Logging.log(ex);
        }
    }

    @Override
    protected boolean isDebug() {
        return BuildConfig.DEBUG;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LoadedApkHuaWei.hookHuaWeiVerifier(this);
        SearchApi.get().cacheSearchEngines();
        MaterialPreferences.instance().setStorageModule(new PrefsStorageModule.Factory());
        Aria2Ui.provider(Aria2ConfigProvider.class);

        ErrorHandler.setup(Prefs.getInt(PK.A2_UPDATE_INTERVAL) * 1000, this);

        if (Prefs.getBoolean(PK.A2_ENABLE_NOTIFS, true))
            NotificationService.start(this);

        // Backward compatibility
        if (!Prefs.has(PK.A2_CUSTOM_INFO)) {
            Set<String> defaultValues = new HashSet<>();
            defaultValues.add(CustomDownloadInfo.Info.DOWNLOAD_SPEED.name());
            defaultValues.add(CustomDownloadInfo.Info.REMAINING_TIME.name());
            Prefs.putSet(PK.A2_CUSTOM_INFO, defaultValues);
        }

        deprecatedBackwardCompatibility();

        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener((prefs, key) -> {
                    if (key.equals(PK.A2_ENABLE_NOTIFS.key())) {
                        if (Prefs.getBoolean(PK.A2_ENABLE_NOTIFS, true))
                            NotificationService.start(ThisApplication.this);
                        else
                            NotificationService.stop(ThisApplication.this);
                    }
                });
    }

    @SuppressWarnings("deprecation")
    private void deprecatedBackwardCompatibility() {
        if (Prefs.has(PK.A2_QUICK_OPTIONS) || Prefs.has(PK.A2_GLOBAL_QUICK_OPTIONS)) {
            Set<String> set = new HashSet<>();
            set.addAll(Prefs.getSet(PK.A2_QUICK_OPTIONS, new HashSet<>()));
            set.addAll(Prefs.getSet(PK.A2_GLOBAL_QUICK_OPTIONS, new HashSet<>()));
            Prefs.putSet(PK.A2_QUICK_OPTIONS_MIXED, set);
            Prefs.remove(PK.A2_QUICK_OPTIONS);
            Prefs.remove(PK.A2_GLOBAL_QUICK_OPTIONS);
        }

        if (Prefs.has(PK.A2_TUTORIAL_DISCOVERIES)) {
            Set<String> set = Prefs.getSet(PK.A2_TUTORIAL_DISCOVERIES, null);
            if (set != null) Prefs.putSet(CommonPK.TUTORIAL_DISCOVERIES, set);
            Prefs.remove(PK.A2_TUTORIAL_DISCOVERIES);
        }
    }

    @Override
    public void onFatal(@NonNull Throwable ex) {
        AbstractClient.invalidate();
        Toaster.with(this).message(R.string.fatalExceptionMessage).ex(ex).show();
        LoadingActivity.startActivity(this, ex);

        Logging.log(ex);
    }

    @Override
    public void onSubsequentExceptions() {
        AbstractClient.invalidate();
        LoadingActivity.startActivity(this, null);
    }

    @Override
    public void onException(@NonNull Throwable ex) {
        Logging.log(ex);
    }
}
