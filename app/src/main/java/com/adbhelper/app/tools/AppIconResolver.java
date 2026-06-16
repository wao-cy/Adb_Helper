package com.adbhelper.app.tools;

import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.IBinder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/**
 * 一次性 batch 模式。完全复刻 Bugjaeger:
 *   AssetManager.addAssetPath() → Resources.getDrawable(icon)
 *   → AdaptiveIconDrawable 则分别提取 getBackground()+getForeground() Canvas 合成
 *   → BitmapDrawable 直取 bitmap
 *   → 其他 Drawable Canvas 渲染
 *
 * 用法:
 *   CLASSPATH=/data/local/tmp/AppIconResolver.jar \
 *   app_process /data/local/tmp \
 *   com.adbhelper.app.tools.AppIconResolver /data/local/tmp/icons pkg1 pkg2 ...
 *
 * 输出: <outputDir>/<pkgName>.png
 */
public class AppIconResolver {

    private static Object sIPM;
    private static Method sGetAi;
    private static boolean sFlagsIsLong;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: AppIconResolver <outputDir> <pkgName>...");
            System.exit(1);
        }
        String outputDir = args[0];
        new File(outputDir).mkdirs();
        initServiceManager();

        for (int i = 1; i < args.length; i++) {
            String pkg = args[i];
            if (pkg == null || pkg.trim().isEmpty()) continue;
            byte[] png = getIcon(pkg);
            if (png != null) {
                try {
                    FileOutputStream fos = new FileOutputStream(new File(outputDir, pkg + ".png"));
                    fos.write(png); fos.close();
                    System.err.println("OK " + pkg);
                } catch (Exception ignored) {}
            } else {
                System.err.println("FAIL " + pkg);
            }
        }
    }

    /**
     * Bugjaeger 方案:
     *   1) AssetManager.addAssetPath(apkPath) 挂载 APK (本地, 不走 Binder)
     *   2) new Resources(am, null, null) → 避免 MIUI MiuiResourcesImpl 崩溃
     *   3) Resources.getDrawable(icon) → Drawable (本地 ID 不匹配时用 res.getIdentifier 重映射)
     *   4) Canvas 渲染, AdaptiveIconDrawable 分别合成 background + foreground
     *   5) PNG 压缩输出
     */
    private static byte[] getIcon(String pkg) {
        ApplicationInfo ai = getAi(pkg);
        if (ai == null || ai.icon == 0) return null;

        Resources res = getBinderRes(ai);
        if (res == null) return null;

        Drawable d = null;
        try {
            d = res.getDrawable(ai.icon);
        } catch (Exception e) {
            // Overlay 重映射: 系统分配的资源 ID 在本地 AssetManager 中可能不同。
            // 从异常信息提取资源名 "pkg:type/name" 后用 getIdentifier 查找本地 ID。
            String msg = e.getMessage();
            if (msg != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\S+)\\s+with\\s+resource\\s+ID").matcher(msg);
                if (m.find()) {
                    int localId = res.getIdentifier(m.group(1), null, null);
                    if (localId != 0) {
                        try { d = res.getDrawable(localId); } catch (Exception ignored) {}
                    }
                }
            }
            // VectorDrawable 回退
            if (d == null) {
                try { d = Drawable.createFromXml(res, res.getXml(ai.icon)); } catch (Exception ignored) {}
            }
        }
        if (d == null) return null;

        return drawableToPng(d);
    }

    /** Bugjaeger Canvas 渲染: 处理所有 Drawable 类型 */
    private static byte[] drawableToPng(Drawable d) {
        // BitmapDrawable → 直接取 bitmap
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) return toPng(b, 72);
        }

        // AdaptiveIconDrawable → Bugjaeger 分别提取 background + foreground 合成
        if ("android.graphics.drawable.AdaptiveIconDrawable".equals(d.getClass().getName())) {
            try {
                Drawable bg = (Drawable) d.getClass().getMethod("getBackground").invoke(d);
                Drawable fg = (Drawable) d.getClass().getMethod("getForeground").invoke(d);
                Bitmap bmp = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bmp);
                if (bg != null) { bg.setBounds(0, 0, 144, 144); bg.draw(cv); }
                if (fg != null) { fg.setBounds(0, 0, 144, 144); fg.draw(cv); }
                return toPng(bmp, 72);
            } catch (Exception e) {
                System.err.println("FAIL adaptive: " + e.getClass().getSimpleName());
            }
        }

        // 其他 (VectorDrawable 等) → Canvas 完整渲染
        int w = 144, h = 144;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(cv);
        return toPng(bmp, 72);
    }

    // ========== Binder ==========

    private static void initServiceManager() {
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "package");
            if (b == null) return;
            Object ipm = Class.forName("android.content.pm.IPackageManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, b);
            sIPM = ipm;
            Class<?> cls = ipm.getClass();

            // getApplicationInfo
            try {
                sGetAi = cls.getMethod("getApplicationInfo", String.class, int.class, int.class);
                sFlagsIsLong = false;
            } catch (NoSuchMethodException e) {
                try {
                    sGetAi = cls.getMethod("getApplicationInfo", String.class, long.class, int.class);
                    sFlagsIsLong = true;
                } catch (NoSuchMethodException e2) {
                    sGetAi = cls.getMethod("getApplicationInfo", String.class, int.class);
                }
            }


        } catch (Exception ignored) {}
    }

    private static ApplicationInfo getAi(String pkg) {
        if (sIPM == null || sGetAi == null) return null;
        try {
            if (sGetAi.getParameterCount() == 2)
                return (ApplicationInfo) sGetAi.invoke(sIPM, pkg, 0);
            if (sFlagsIsLong)
                return (ApplicationInfo) sGetAi.invoke(sIPM, pkg, 0L, 0);
            return (ApplicationInfo) sGetAi.invoke(sIPM, pkg, 0, 0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Bugjaeger 方案: 本地 AssetManager + Resources(am, null, null)
     * 不经过 Binder IPC，避免 MIUI 上 getResourcesForApplication() 失败的问题。
     */
    private static Resources getBinderRes(ApplicationInfo ai) {
        try {
            String apkPath = ai.publicSourceDir != null ? ai.publicSourceDir : ai.sourceDir;
            if (apkPath == null) return null;

            AssetManager am = AssetManager.class.newInstance();
            am.getClass().getMethod("addAssetPath", String.class).invoke(am, apkPath);

            // Bugjaeger 方式: null DisplayMetrics + null Configuration
            // 避免 MIUI MiuiResourcesImpl.<clinit> 崩溃
            return new Resources(am, null, null);
        } catch (Exception e) {
            System.err.println("FAIL localRes " + ai.packageName + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    // ========== 工具 ==========

    private static byte[] toPng(Bitmap bmp, int max) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        float s = Math.min((float) max / w, (float) max / h);
        if (s < 1) { w = (int)(w * s); h = (int)(h * s); bmp = Bitmap.createScaledBitmap(bmp, w, h, true); }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }
}
