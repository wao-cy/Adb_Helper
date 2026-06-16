package com.adbhelper.app.tools;

import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 通过 app_process 在目标设备运行，单次 Binder 调用获取所有已安装应用信息。
 *
 * 完全参照 Bugjaeger 方案:
 *   1. ServiceManager → IPackageManager → getInstalledApplications() 一次性获取所有应用
 *   2. 每个 ApplicationInfo 提取 packageName/label/enabled/isSystemApp
 *   3. label 使用 Resources.getString(labelRes) 解析（本地 AssetManager，无 Binder IPC）
 *
 * 用法:
 *   CLASSPATH=/data/local/tmp/AppListResolver.jar \
 *   app_process /data/local/tmp com.adbhelper.app.tools.AppListResolver
 *
 * 输出格式（每行一个应用，Tab 分隔）:
 *   packageName\tsourceDir\tisSystemApp(0/1)\tisDisabled(0/1)\tlabel
 */
public class AppListResolver {

    private static Object sIPM;
    private static Method sGetInstalledApps;
    private static boolean sFlagsIsLong;

    public static void main(String[] args) {
        try {
            initServiceManager();
            if (sIPM == null) {
                System.err.println("ERROR: cannot get IPackageManager");
                System.exit(1);
                return;
            }

            // MATCH_DISABLED_COMPONENTS(0x200) | MATCH_ANY_USER(0x400000)
            int flags = 0x200 | 0x400000;
            List<?> appList = getInstalledApplications(flags);
            if (appList == null) {
                System.err.println("ERROR: getInstalledApplications returned null");
                System.exit(1);
                return;
            }

            for (Object obj : appList) {
                ApplicationInfo ai = (ApplicationInfo) obj;
                String pkg = ai.packageName != null ? ai.packageName : "";
                String sourceDir = ai.publicSourceDir != null ? ai.publicSourceDir :
                        (ai.sourceDir != null ? ai.sourceDir : "");
                String isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "1" : "0";
                String isDisabled = !ai.enabled ? "1" : "0";
                String label = resolveLabel(ai);

                System.out.println(pkg + "\t" + sourceDir + "\t" + isSystem + "\t" + isDisabled + "\t" + (label != null ? label : "?"));
            }

        } catch (Throwable t) {
            System.err.println("FATAL: " + t.getClass().getName() + ": " + t.getMessage());
            System.exit(1);
        }
    }

    // ========== Binder: 获取应用列表 ==========

    @SuppressWarnings("unchecked")
    private static List<?> getInstalledApplications(int flags) throws Exception {
        Object userId = 0;
        Object raw;
        if (sFlagsIsLong) {
            raw = sGetInstalledApps.invoke(sIPM, (long) flags, userId);
        } else {
            raw = sGetInstalledApps.invoke(sIPM, flags, userId);
        }
        if (raw instanceof List) {
            return (List<?>) raw;
        }
        return (List<?>) raw.getClass().getMethod("getList").invoke(raw);
    }

    private static void initServiceManager() {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "package");
            if (binder == null) return;
            sIPM = Class.forName("android.content.pm.IPackageManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> cls = sIPM.getClass();

            // getInstalledApplications: Android 14+ flags int → long
            try {
                sGetInstalledApps = cls.getMethod("getInstalledApplications", int.class, int.class);
                sFlagsIsLong = false;
            } catch (NoSuchMethodException e) {
                sGetInstalledApps = cls.getMethod("getInstalledApplications", long.class, int.class);
                sFlagsIsLong = true;
            }
        } catch (Exception ignored) {
        }
    }

    // ========== label 解析 ==========

    /**
     * 从 ApplicationInfo 解析应用名。
     * 策略：
     *   1. nonLocalizedLabel 字段直读
     *   2. 本地 AssetManager + Resources.getString(labelRes)（不经过 Binder IPC）
     */
    private static String resolveLabel(ApplicationInfo ai) {
        if (ai.nonLocalizedLabel != null) {
            String label = ai.nonLocalizedLabel.toString().trim();
            if (!label.isEmpty()) return label;
        }

        if (ai.labelRes != 0) {
            Resources res = createLocalResources(ai);
            if (res != null) {
                try {
                    String label = res.getString(ai.labelRes);
                    if (label != null && !label.trim().isEmpty()) return label.trim();
                } catch (Exception ignored) {
                }
            }
        }

        return null;
    }

    /** 本地 AssetManager + Resources，避免 Binder IPC 限制 */
    private static Resources createLocalResources(ApplicationInfo ai) {
        try {
            String apkPath = ai.publicSourceDir != null ? ai.publicSourceDir : ai.sourceDir;
            if (apkPath == null) return null;

            AssetManager am = (AssetManager) Class.forName("android.content.res.AssetManager")
                    .getDeclaredConstructor().newInstance();
            java.lang.reflect.Method addPath = am.getClass().getMethod("addAssetPath", String.class);
            addPath.invoke(am, apkPath);
            if (ai.splitPublicSourceDirs != null) {
                for (String split : ai.splitPublicSourceDirs) {
                    if (split != null) addPath.invoke(am, split);
                }
            }

            return new Resources(am, null, null);
        } catch (Throwable t) {
            // MIUI: MiuiResourcesImpl.<clinit> may throw Error; catch all
            return null;
        }
    }
}
