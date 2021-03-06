package com.gianlu.aria2app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gianlu.aria2app.Activities.EditProfileActivity;
import com.gianlu.aria2app.Main.MainActivity;
import com.gianlu.aria2app.NetIO.AbstractClient;
import com.gianlu.aria2app.NetIO.HttpClient;
import com.gianlu.aria2app.NetIO.NetInstanceHolder;
import com.gianlu.aria2app.NetIO.OnConnect;
import com.gianlu.aria2app.NetIO.WebSocketClient;
import com.gianlu.aria2app.ProfilesManager.CustomProfilesAdapter;
import com.gianlu.aria2app.ProfilesManager.MultiProfile;
import com.gianlu.aria2app.ProfilesManager.ProfilesManager;
import com.gianlu.aria2app.WebView.WebViewActivity;
import com.gianlu.commonutils.Analytics.AnalyticsApplication;
import com.gianlu.commonutils.Dialogs.ActivityWithDialog;
import com.gianlu.commonutils.Drawer.DrawerManager;
import com.gianlu.commonutils.Logging;
import com.gianlu.commonutils.Toaster;

import org.json.JSONException;

import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class LoadingActivity extends ActivityWithDialog implements OnConnect, DrawerManager.ProfilesDrawerListener<MultiProfile> {
    public static final String SHORTCUT_ADD_URI = "com.gianlu.aria2app.ADD_URI";
    public static final String SHORTCUT_ADD_METALINK = "com.gianlu.aria2app.ADD_METALINK";
    public static final String SHORTCUT_ADD_TORRENT = "com.gianlu.aria2app.ADD_TORRENT";
    public static final String SHORTCUT_SEARCH = "com.gianlu.aria2app.SEARCH";
    private Intent goTo;
    private LinearLayout connecting;
    private LinearLayout picker;
    private TextView pickerHint;
    private RecyclerView pickerList;
    private boolean finished = false;
    private Uri shareData;
    private String launchGid;
    private Button seeError;
    private Button cancel;
    private ProfilesManager manager;
    private String shortcutAction;
    private Handler handler;
    private MultiProfile.UserProfile aria2AndroidProfile = null;
    private Closeable ongoingTest;

    public static void startActivity(Context context) {
        context.startActivity(new Intent(context, LoadingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    public static void startActivity(Context context, @Nullable Throwable ex) {
        context.startActivity(new Intent(context, LoadingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("showPicker", true)
                .putExtra("ex", ex));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loading);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.hide();

        connecting = findViewById(R.id.loading_connecting);
        picker = findViewById(R.id.loading_picker);
        pickerHint = findViewById(R.id.loading_pickerHint);
        pickerList = findViewById(R.id.loading_pickerList);
        seeError = findViewById(R.id.loading_seeError);
        cancel = findViewById(R.id.loading_cancel);
        pickerList.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        ImageButton pickerAdd = findViewById(R.id.loading_pickerAdd);
        pickerAdd.setOnClickListener(v -> EditProfileActivity.start(LoadingActivity.this, false));

        Button settings = findViewById(R.id.loading_settings);
        settings.setOnClickListener(v -> startActivity(new Intent(LoadingActivity.this, PreferenceActivity.class)));

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            finished = true;
            if (goTo != null) startActivity(goTo);
        }, 1000);

        NetInstanceHolder.close();

        manager = ProfilesManager.get(this);
        if (getIntent().getBooleanExtra("external", false)) {
            MultiProfile profile = ProfilesManager.createExternalProfile(getIntent());
            if (profile != null) {
                try {
                    manager.save(profile);
                    tryConnecting(profile);
                    return;
                } catch (IOException | JSONException ex) {
                    Toaster.with(this).message(R.string.cannotSaveProfile).ex(ex).show();
                    return;
                }
            }
        }

        if (!manager.hasProfiles()) {
            EditProfileActivity.start(this, true);
            return;
        }

        launchGid = getIntent().getStringExtra("gid");

        if (hasShortcutAction()) {
            AnalyticsApplication.sendAnalytics(Utils.ACTION_SHORTCUT);

            shortcutAction = getIntent().getAction();
            displayPicker(true);
            return;
        }

        if (hasShareData()) {
            AnalyticsApplication.sendAnalytics(Utils.ACTION_SHARE);

            shareData = getShareData();
            displayPicker(true);
            return;
        }

        String profileId = getIntent().getStringExtra("profileId");
        if (profileId != null && manager.profileExists(profileId)) {
            try {
                tryConnecting(manager.retrieveProfile(profileId));
                return;
            } catch (IOException | JSONException ex) {
                Logging.log(ex);
            }
        }

        final Throwable ex = (Throwable) getIntent().getSerializableExtra("ex");
        if (ex != null) {
            seeError.setVisibility(View.VISIBLE);
            seeError.setOnClickListener(v -> showErrorDialog(ex));
            getIntent().removeExtra("ex");
        } else {
            seeError.setVisibility(View.GONE);
        }

        if (getIntent().getBooleanExtra("showPicker", false))
            displayPicker(false);
        else
            tryConnecting(manager.getLastProfile());
    }

    private boolean hasShortcutAction() {
        String action = getIntent().getAction();
        return Objects.equals(action, SHORTCUT_ADD_METALINK)
                || Objects.equals(action, SHORTCUT_ADD_URI)
                || Objects.equals(action, SHORTCUT_ADD_TORRENT)
                || Objects.equals(action, SHORTCUT_SEARCH);
    }

    private boolean hasShareData() {
        String action = getIntent().getAction();
        return Objects.equals(action, Intent.ACTION_SEND) || Objects.equals(action, Intent.ACTION_VIEW);
    }

    @Nullable
    private Uri getShareData() {
        Uri stream = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream == null) {
            Uri data = getIntent().getData();
            if (data == null) {
                String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
                if (text != null) return Uri.parse(text);
            } else {
                return data;
            }
        } else {
            return stream;
        }

        return null;
    }

    @Override
    protected void onRestart() {
        super.onRestart();

        ProfilesManager manager = ProfilesManager.get(this);
        if (manager.hasProfiles()) displayPicker(hasShareData());
        else EditProfileActivity.start(this, true);
    }

    private void tryConnecting(@Nullable MultiProfile profile) {
        connecting.setVisibility(View.VISIBLE);
        picker.setVisibility(View.GONE);
        seeError.setVisibility(View.GONE);

        if (profile == null) {
            displayPicker(hasShareData());
        } else {
            manager.setCurrent(profile);
            MultiProfile.UserProfile single = profile.getProfile(this);
            if (single.connectionMethod == MultiProfile.ConnectionMethod.WEBSOCKET)
                ongoingTest = WebSocketClient.checkConnection(single, this, true);
            else
                ongoingTest = HttpClient.checkConnection(single, this, true);

            handler.postDelayed(() -> {
                cancel.setVisibility(View.VISIBLE);
                cancel.setOnClickListener(view -> cancelConnection());
            }, 2000);
        }
    }

    private void cancelConnection() {
        if (ongoingTest != null) {
            try {
                ongoingTest.close();
            } catch (IOException ignored) {
            }
        }

        displayPicker(hasShareData());
        seeError.setVisibility(View.GONE);
    }

    private void showErrorDialog(@NonNull final Throwable ex) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.failedConnecting)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.contactMe, (dialog, which) -> Logging.sendEmail(LoadingActivity.this, ex))
                .setMessage(ex.toString());

        showDialog(builder);
    }

    private void displayPicker(boolean share) {
        connecting.setVisibility(View.GONE);
        picker.setVisibility(View.VISIBLE);

        if (share) pickerHint.setText(R.string.pickProfile_someAction);
        else pickerHint.setText(R.string.pickProfile);

        List<MultiProfile> profiles = manager.getProfiles();
        if (share && profiles.size() == 1) {
            tryConnecting(profiles.get(0));
            return;
        }

        CustomProfilesAdapter adapter = new CustomProfilesAdapter(this, profiles, R.style.ForceWhite, this);
        pickerList.setAdapter(adapter);
        adapter.startProfilesTest();
    }

    @Override
    public void onDrawerProfileSelected(@NonNull MultiProfile profile) {
        tryConnecting(profile);
    }

    @Override
    public boolean onDrawerProfileLongClick(@NonNull MultiProfile profile) {
        EditProfileActivity.start(this, profile.id);
        return true;
    }

    private void launchMain() {
        Intent intent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (shortcutAction != null) intent.putExtra("shortcutAction", shortcutAction);
        else if (shareData != null) intent.putExtra("shareData", shareData);
        else if (launchGid != null && !launchGid.isEmpty()) intent.putExtra("gid", launchGid);
        if (finished) startActivity(intent);
        else this.goTo = intent;
    }

    private void launchWebView() {
        Intent intent = new Intent(this, WebViewActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("shareData", shareData)
                .putExtra("canGoBack", false);
        startActivity(intent);
    }

    @Override
    public boolean onConnected(@NonNull AbstractClient client) {
        ongoingTest = null;

        if (shareData != null) {
            try {
                new URL(shareData.toString());
            } catch (MalformedURLException ex) {
                launchMain();
                return false;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.useWebView)
                    .setMessage(R.string.useWebView_message)
                    .setPositiveButton(android.R.string.yes, (dialog, which) -> launchWebView())
                    .setNeutralButton(android.R.string.no, (dialog, which) -> launchMain());
            showDialog(builder);
        } else {
            launchMain();
        }

        return false;
    }

    @Override
    public void onPingTested(@NonNull AbstractClient client, long latency) {
    }

    private void mayStartAria2Android(@NonNull MultiProfile.UserProfile profile, @NonNull Throwable ex) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.startAria2Android)
                .setMessage(R.string.startAria2Android_message)
                .setPositiveButton(android.R.string.yes, (dialog, which) -> startAria2Android(profile))
                .setNegativeButton(android.R.string.no, (dialog, which) -> failedConnecting(ex));
        showDialog(builder);
    }

    private void startAria2Android(@NonNull MultiProfile.UserProfile profile) {
        AnalyticsApplication.sendAnalytics(Utils.ACTION_START_ARIA2ANDROID);

        aria2AndroidProfile = profile;

        try {
            Intent intent = new Intent("com.gianlu.aria2android.START_SERVICE");
            intent.setClassName("com.gianlu.aria2android", "com.gianlu.aria2android.MainActivity");
            intent.putExtra("goBack", true);
            startActivityForResult(intent, 1);
        } catch (ActivityNotFoundException ex) {
            Toaster.with(this).message(R.string.failedStartingAria2Android).ex(ex).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == 1 && aria2AndroidProfile != null) {
            if (resultCode == 1) {
                tryConnecting(aria2AndroidProfile.getParent());
            } else {
                aria2AndroidProfile = null;
                Toaster.with(this).message(R.string.failedStartingAria2Android).error(false).show();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void failedConnecting(@NonNull Throwable ex) {
        Toaster.with(this).message(R.string.failedConnecting).ex(ex).show();
        displayPicker(hasShareData());
        seeError.setVisibility(View.VISIBLE);
        seeError.setOnClickListener(v -> showErrorDialog(ex));
        Logging.log(ex);
    }

    @Override
    public void onFailedConnecting(@NonNull MultiProfile.UserProfile profile, @NonNull Throwable ex) {
        ongoingTest = null;

        if (profile.couldBeAria2Android(this)) mayStartAria2Android(profile, ex);
        else failedConnecting(ex);
    }
}
