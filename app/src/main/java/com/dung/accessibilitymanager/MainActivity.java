package com.dung.accessibilitymanager;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String PREFS = "prefs";
    private static final String KEY_SELECTED = "selected_services";

    private LinearLayout serviceList;
    private TextView permissionText;
    private final List<ServiceRow> rows = new ArrayList<>();

    static class ServiceRow {
        final String component;
        final String label;
        final CheckBox checkBox;

        ServiceRow(String component, String label, CheckBox checkBox) {
            this.component = component;
            this.label = label;
            this.checkBox = checkBox;
        }
    }

    static class Candidate {
        final String component;
        final String label;

        Candidate(String component, String label) {
            this.component = component;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadDownloadedServices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
        refreshEnabledStateHints();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, float sp) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(0xFF202124);
        return v;
    }

    private Button button(String title) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(17);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);

        TextView title = text("Quản lý Trợ năng", 26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        permissionText = text("", 16);
        permissionText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        permissionText.setPadding(0, dp(10), 0, dp(8));
        root.addView(permissionText);

        Button offAll = button("TẮT TẤT CẢ");
        offAll.setOnClickListener(v -> disableAllDownloaded());
        root.addView(offAll);

        Button enableSelected = button("BẬT ỨNG DỤNG ĐÃ CHỌN");
        enableSelected.setOnClickListener(v -> enableSelectedDownloaded());
        root.addView(enableSelected);

        TextView desc = text("Chọn các ứng dụng trong mục “Ứng dụng đã tải xuống” cần bật:", 15);
        desc.setPadding(0, dp(10), 0, dp(4));
        root.addView(desc);

        serviceList = new LinearLayout(this);
        serviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(serviceList);

        TextView note = text("Danh sách chọn được lưu lại. Nút TẮT TẤT CẢ chỉ tắt các dịch vụ trong nhóm Ứng dụng đã tải xuống và không xóa lựa chọn của bạn.", 13);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        setContentView(scroll);
        updatePermissionStatus();
    }

    private boolean hasSecureSettingsPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updatePermissionStatus() {
        if (permissionText == null) return;
        if (hasSecureSettingsPermission()) {
            permissionText.setText("Quyền ADB: ✅ Đã cấp");
            permissionText.setTextColor(0xFF137333);
        } else {
            permissionText.setText("Quyền ADB: ❌ Chưa cấp");
            permissionText.setTextColor(0xFFB3261E);
        }
    }

    /**
     * HyperOS/Android Settings' "Downloaded apps" group is intended for accessibility
     * services supplied by non-system apps. Built-in accessibility features are excluded.
     * We mirror that behavior by excluding system and updated-system packages.
     */
    private boolean isDownloadedAccessibilityService(ResolveInfo ri) {
        if (ri == null || ri.serviceInfo == null || ri.serviceInfo.applicationInfo == null) {
            return false;
        }
        ApplicationInfo ai = ri.serviceInfo.applicationInfo;
        boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean isUpdatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        return !isSystem && !isUpdatedSystem;
    }

    private void loadDownloadedServices() {
        rows.clear();
        serviceList.removeAllViews();

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> selected = sp.getStringSet(KEY_SELECTED, new LinkedHashSet<>());

        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> installed = am.getInstalledAccessibilityServiceList();
        PackageManager pm = getPackageManager();
        List<Candidate> candidates = new ArrayList<>();

        if (installed != null) {
            for (AccessibilityServiceInfo info : installed) {
                ResolveInfo ri = info.getResolveInfo();
                if (!isDownloadedAccessibilityService(ri)) continue;

                String pkg = ri.serviceInfo.packageName;
                String cls = ri.serviceInfo.name;
                String flat = canonicalComponent(new ComponentName(pkg, cls).flattenToString());
                if (flat == null) continue;

                CharSequence labelCs = ri.loadLabel(pm);
                String label = labelCs != null ? labelCs.toString().trim() : pkg;
                if (label.isEmpty()) label = pkg;
                candidates.add(new Candidate(flat, label));
            }
        }

        final Collator collator = Collator.getInstance(new Locale("vi", "VN"));
        Collections.sort(candidates, Comparator.comparing(c -> c.label, collator));

        if (candidates.isEmpty()) {
            TextView empty = text("Không tìm thấy ứng dụng Trợ năng đã tải xuống.", 16);
            empty.setPadding(0, dp(8), 0, dp(8));
            serviceList.addView(empty);
            return;
        }

        for (Candidate c : candidates) {
            CheckBox cb = new CheckBox(this);
            cb.setTextSize(16);
            cb.setPadding(0, dp(7), 0, dp(7));
            cb.setChecked(selected.contains(c.component));
            ServiceRow row = new ServiceRow(c.component, c.label, cb);
            rows.add(row);
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> saveSelection());
            serviceList.addView(cb);
        }
        refreshEnabledStateHints();
    }

    private void saveSelection() {
        Set<String> s = new LinkedHashSet<>();
        for (ServiceRow row : rows) {
            if (row.checkBox.isChecked()) s.add(row.component);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_SELECTED, s).apply();
    }

    private boolean ensurePermission() {
        updatePermissionStatus();
        if (!hasSecureSettingsPermission()) {
            Toast.makeText(this,
                    "Chưa có WRITE_SECURE_SETTINGS. Hãy cấp quyền bằng ADB USB.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private String canonicalComponent(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        ComponentName cn = ComponentName.unflattenFromString(value.trim());
        return cn != null ? cn.flattenToString() : null;
    }

    private Set<String> currentlyEnabled() {
        Set<String> out = new LinkedHashSet<>();
        String raw = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (raw == null || raw.isEmpty()) return out;

        for (String part : raw.split(":")) {
            String canonical = canonicalComponent(part);
            if (canonical != null) out.add(canonical);
        }
        return out;
    }

    private Set<String> downloadedComponents() {
        Set<String> out = new LinkedHashSet<>();
        for (ServiceRow row : rows) out.add(row.component);
        return out;
    }

    private void writeEnabledServices(Set<String> services) {
        String value = TextUtils.join(":", services);
        Settings.Secure.putString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
        Settings.Secure.putInt(
                getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED,
                services.isEmpty() ? 0 : 1);
    }

    /** Tắt chỉ nhóm "Ứng dụng đã tải xuống", giữ nguyên các dịch vụ hệ thống khác. */
    private void disableAllDownloaded() {
        if (!ensurePermission()) return;
        try {
            Set<String> enabled = currentlyEnabled();
            enabled.removeAll(downloadedComponents());
            writeEnabledServices(enabled);
            Toast.makeText(this, "Đã tắt tất cả ứng dụng Trợ năng đã tải xuống", Toast.LENGTH_SHORT).show();
            refreshEnabledStateHints();
        } catch (Throwable t) {
            showError(t);
        }
    }

    /** Bật thêm những app được tích chọn; không tắt các dịch vụ khác đang bật. */
    private void enableSelectedDownloaded() {
        if (!ensurePermission()) return;
        try {
            Set<String> enabled = currentlyEnabled();
            int count = 0;
            for (ServiceRow row : rows) {
                if (row.checkBox.isChecked()) {
                    enabled.add(row.component);
                    count++;
                }
            }
            if (count == 0) {
                Toast.makeText(this, "Bạn chưa chọn ứng dụng nào", Toast.LENGTH_SHORT).show();
                return;
            }
            writeEnabledServices(enabled);
            Toast.makeText(this, "Đã bật " + count + " ứng dụng đã chọn", Toast.LENGTH_SHORT).show();
            refreshEnabledStateHints();
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void refreshEnabledStateHints() {
        if (rows.isEmpty()) return;
        Set<String> enabled = currentlyEnabled();
        for (ServiceRow row : rows) {
            row.checkBox.setText(row.label + "\n" +
                    (enabled.contains(row.component) ? "Đang bật" : "Đang tắt"));
        }
    }

    private void showError(Throwable t) {
        Toast.makeText(this,
                "Lỗi: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
    }
}
