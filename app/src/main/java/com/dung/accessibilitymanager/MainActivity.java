package com.dung.accessibilitymanager;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        final boolean systemApp;

        Candidate(String component, String label, boolean systemApp) {
            this.component = component;
            this.label = label;
            this.systemApp = systemApp;
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

        TextView note = text("Danh sách lựa chọn được lưu lại. TẮT TẤT CẢ chỉ tắt các mục trong nhóm “Ứng dụng đã tải xuống”.", 13);
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

    private String normalizeLabel(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        return n.replace('đ', 'd');
    }

    /**
     * HyperOS places some Xiaomi/Microsoft system components inside the visible
     * “Ứng dụng đã tải xuống” section, while other built-in accessibility tools
     * (TalkBack, Switch Access, Accessibility Menu, Game Turbo...) are outside it.
     * For this Redmi/HyperOS layout we therefore include all third-party services,
     * plus the system services that HyperOS itself shows in that section.
     */
    private boolean shouldAppearInDownloadedGroup(ResolveInfo ri, String label) {
        if (ri == null || ri.serviceInfo == null || ri.serviceInfo.applicationInfo == null) {
            return false;
        }

        ApplicationInfo ai = ri.serviceInfo.applicationInfo;
        boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

        if (!isSystem) return true;

        String n = normalizeLabel(label);
        return n.equals("chup anh man hinh")
                || n.contains("xiaomi hyperai")
                || n.equals("lien ket voi windows")
                || n.equals("lien thong")
                || n.contains("link to windows");
    }

    private void addResolveInfo(Map<String, Candidate> out, ResolveInfo ri, PackageManager pm) {
        if (ri == null || ri.serviceInfo == null) return;

        String pkg = ri.serviceInfo.packageName;
        String cls = ri.serviceInfo.name;
        String flat = canonicalComponent(new ComponentName(pkg, cls).flattenToString());
        if (flat == null) return;

        CharSequence labelCs = ri.loadLabel(pm);
        String label = labelCs != null ? labelCs.toString().trim() : pkg;
        if (label.isEmpty()) label = pkg;

        if (!shouldAppearInDownloadedGroup(ri, label)) return;

        ApplicationInfo ai = ri.serviceInfo.applicationInfo;
        boolean system = ai != null && (((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                || ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0));

        out.put(flat, new Candidate(flat, label, system));
    }

    private void loadDownloadedServices() {
        rows.clear();
        serviceList.removeAllViews();

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> selected = sp.getStringSet(KEY_SELECTED, new LinkedHashSet<>());

        PackageManager pm = getPackageManager();
        Map<String, Candidate> unique = new LinkedHashMap<>();

        // Source 1: AccessibilityManager. This is the most accurate source when
        // the OS returns the complete list.
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> installed = am.getInstalledAccessibilityServiceList();
        if (installed != null) {
            for (AccessibilityServiceInfo info : installed) {
                addResolveInfo(unique, info.getResolveInfo(), pm);
            }
        }

        // Source 2: explicit package query. Android 11+ package visibility can
        // otherwise hide third-party services such as Alarmy/Key Mapper.
        try {
            Intent intent = new Intent(AccessibilityService.SERVICE_INTERFACE);
            int flags = PackageManager.MATCH_DISABLED_COMPONENTS
                    | PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
            List<ResolveInfo> resolved = pm.queryIntentServices(intent, flags);
            if (resolved != null) {
                for (ResolveInfo ri : resolved) addResolveInfo(unique, ri, pm);
            }
        } catch (Throwable ignored) {
        }

        List<Candidate> candidates = new ArrayList<>(unique.values());
        final Collator collator = Collator.getInstance(new Locale("vi", "VN"));
        Collections.sort(candidates, Comparator.comparing(c -> c.label, collator));

        if (candidates.isEmpty()) {
            TextView empty = text("Không tìm thấy ứng dụng trong mục “Ứng dụng đã tải xuống”.", 16);
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

    private void disableAllDownloaded() {
        if (!ensurePermission()) return;
        try {
            Set<String> enabled = currentlyEnabled();
            enabled.removeAll(downloadedComponents());
            writeEnabledServices(enabled);
            Toast.makeText(this, "Đã tắt tất cả", Toast.LENGTH_SHORT).show();
            refreshEnabledStateHints();
        } catch (Throwable t) {
            showError(t);
        }
    }

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
