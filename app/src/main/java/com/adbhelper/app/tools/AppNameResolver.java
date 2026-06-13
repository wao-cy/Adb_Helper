package com.adbhelper.app.tools;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.IBinder;

/**
 * 通过 app_process 在目标设备运行，直接调用 PackageManager API
 * 获取已安装应用的中文名。
 *
 * 用法（设备端）:
 *   export CLASSPATH=/data/local/tmp/AppNameResolver.jar
 *   app_process /data/local/tmp com.adbhelper.app.tools.AppNameResolver pkg1 pkg2 ...
 *
 * 输出格式: pkg=应用名（无名称时 pkg= 空值）
 */
public class AppNameResolver {
    // 缓存 PackageManager 实例（所有包共享）
    private static PackageManager sPm;

    public static void main(String[] args) {
        try {
            // 1. 通过 ServiceManager 获取 IPackageManager 做基础查询
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "package");
            if (binder == null) {
                System.err.println("ERROR: cannot get package service");
                System.exit(1);
                return;
            }
            Object iPM = Class.forName("android.content.pm.IPackageManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            Class<?> iPMClass = iPM.getClass();
            java.lang.reflect.Method getAi = iPMClass.getMethod(
                    "getApplicationInfo", String.class, int.class, int.class);

            // 2. 尝试构造 PackageManager（需要 ActivityThread 提供 Context）
            //    后续 labelRes 解析需要它
            sPm = createPackageManager();

            // 3. 处理每个包
            for (String pkg : args) {
                if (pkg == null || pkg.trim().isEmpty()) continue;
                String label = null;
                try {
                    ApplicationInfo ai = (ApplicationInfo) getAi.invoke(
                            iPM, pkg.trim(), 0, 0);
                    label = resolveLabel(iPM, iPMClass, ai);
                } catch (Exception ignored) {
                    // 包不存在或无权访问
                }
                System.out.println(pkg + "=" + (label != null ? label : ""));
            }
        } catch (Throwable t) {
            System.err.println("FATAL: " + t.getClass().getName() + ": " + t.getMessage());
            System.exit(1);
        }
    }

    /**
     * 从 ApplicationInfo 解析应用名，优先级:
     *   1. nonLocalizedLabel（无需 Context）
     *   2. PackageManager.getApplicationLabel()（完整解析，含 labelRes）
     *   3. labelRes → Resources.getString()（直连 IPackageManager）
     *   4. 返回 null
     */
    private static String resolveLabel(Object iPM, Class<?> iPMClass, ApplicationInfo ai) {
        // 优先: nonLocalizedLabel
        if (ai.nonLocalizedLabel != null) {
            String label = ai.nonLocalizedLabel.toString().trim();
            if (!label.isEmpty()) return label;
        }

        // 其次: 通过 PackageManager.getApplicationLabel() 完整解析
        if (sPm != null) {
            try {
                CharSequence cs = sPm.getApplicationLabel(ai);
                if (cs != null) {
                    String label = cs.toString().trim();
                    if (!label.isEmpty()) return label;
                }
            } catch (Exception e) {
                System.out.println("D:labelFail(" + ai.packageName + ")=" + e.getClass().getSimpleName());
            }
        }

        // 3. IPackageManager.getResourcesForApplication（3 种签名）
        if (ai.labelRes != 0) {
            Resources res = getResourcesForApp(iPM, iPMClass, ai);
            if (res != null) {
                try {
                    String label = res.getString(ai.labelRes);
                    if (label != null) { label = label.trim(); if (!label.isEmpty()) return label; }
                } catch (Exception ignored) {}
            }
        }

        // 4. 直接从 APK 路径创建 AssetManager + Resources（不依赖 Context）
        if (ai.labelRes != 0 && ai.packageName != null) {
            try {
                String apkPath = ai.publicSourceDir != null ? ai.publicSourceDir : ai.sourceDir;
                if (apkPath != null) {
                    Object am = Class.forName("android.content.res.AssetManager")
                            .getDeclaredConstructor().newInstance();
                    am.getClass().getMethod("addAssetPath", String.class).invoke(am, apkPath);
                    Resources res = new Resources(
                            (android.content.res.AssetManager) am, null, null);
                    String label = res.getString(ai.labelRes);
                    if (label != null) { label = label.trim(); if (!label.isEmpty()) return label; }
                }
            } catch (Exception e) {
                System.out.println("D:AM(" + ai.packageName + ")=" + e.getClass().getSimpleName() + ":" + e.getMessage());
            }
        }

        return null;
    }

    /**
     * 尝试所有已知的 IPackageManager.getResourcesForApplication 签名
     */
    private static Resources getResourcesForApp(
            Object iPM, Class<?> iPMClass, ApplicationInfo ai) {
        // 按 Android 版本演进顺序尝试
        Object[][] candidates = {
            { ApplicationInfo.class },
            { String.class },
            { String.class, Integer.TYPE },
        };
        for (Object[] params : candidates) {
            try {
                Class<?>[] types = new Class<?>[params.length];
                Object[] args = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    if (params[i] == ApplicationInfo.class) {
                        types[i] = ApplicationInfo.class;
                        args[i] = ai;
                    } else if (params[i] == String.class) {
                        types[i] = String.class;
                        args[i] = ai.packageName;
                    } else if (params[i] == Integer.TYPE) {
                        types[i] = Integer.TYPE;
                        args[i] = 0;
                    }
                }
                java.lang.reflect.Method m = iPMClass.getMethod(
                        "getResourcesForApplication", types);
                return (Resources) m.invoke(iPM, args);
            } catch (NoSuchMethodException ignored) {
                // 签名不匹配，试下一个
            } catch (Exception ignored) {
                // 调用失败（参数错误、权限等）
            }
        }
        return null;
    }

    /**
     * 通过 ActivityThread 构建 PackageManager 实例
     */
    private static PackageManager createPackageManager() {
        // app_process 中 ActivityThread 不可用，此方法始终返回 null。
        // 应用名解析改由 AssetManager+Resources 直接从 APK 获取。
        return null;
    }
}
