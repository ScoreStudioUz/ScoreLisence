package com.score.license.guard;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LicenseGate extends JavaPlugin {
    private static final String REAL_MAIN = "com.score.license.lIIIll1O0OOIl0IOOl";

    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("com/score/license/LicenseDatabase.class",
                "1dc2e4241f9c80a94a4cc8d3e2caad89f2ec7e02b104ec389634c6485cc4c2c3");
        EXPECTED.put("com/score/license/LicenseStorage.class",
                "39af05e903c6617bc0f786eed454d12aa7daa03a1e5063c37674f4ae57dd1267");
        EXPECTED.put("com/score/license/LicenseHeartbeat.class",
                "ba69a074085d37052f9a4fd9aef3cc5c06f1f3a6a502889f2b9d5941936cf348");
        EXPECTED.put("com/score/license/LicenseAPI.class",
                "be80d46863305646ab92e7938bcf6909133664d339367822fda2fa5774a70b48");
        EXPECTED.put("com/score/license/lIIIll1O0OOIl0IOOl.class",
                "b7ba38ba15f7fda4b21288985e6ac1a3b39458244ab883dcd2013e04630b7441");
    }

    private JavaPlugin delegate;
    private boolean ok;

    private boolean selfCheck() {
        ClassLoader cl = getClass().getClassLoader();
        for (Map.Entry<String, String> e : EXPECTED.entrySet()) {
            byte[] got = IntegrityChecker.sha256OfResource(cl, e.getKey());
            byte[] exp = IntegrityChecker.hex(e.getValue());
            if (!IntegrityChecker.equals(got, exp)) return false;
        }
        return true;
    }

    private static void copyFields(JavaPlugin from, JavaPlugin to) throws Exception {
        Class<?> c = JavaPlugin.class;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(from);
                    if (v != null) f.set(to, v);
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private static void setEnabled(JavaPlugin p, boolean v) {
        try {
            Field f = JavaPlugin.class.getDeclaredField("isEnabled");
            f.setAccessible(true);
            f.setBoolean(p, v);
        } catch (Throwable ignored) {}
    }

    private void rebindCommands(JavaPlugin guard, JavaPlugin del) {
        try {
            org.bukkit.plugin.PluginDescriptionFile desc = guard.getDescription();
            java.util.Map<String, java.util.Map<String, Object>> cmds = desc.getCommands();
            if (cmds == null) return;
            for (String name : cmds.keySet()) {
                org.bukkit.command.PluginCommand pc = guard.getCommand(name);
                if (pc == null) continue;
                try {
                    Field pf = org.bukkit.command.PluginCommand.class.getDeclaredField("owningPlugin");
                    pf.setAccessible(true);
                    pf.set(pc, del);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onLoad() {
        try {
            ok = selfCheck();
            if (!ok) return;
            Class<?> rc = Class.forName(REAL_MAIN, true, getClass().getClassLoader());
            sun.misc.Unsafe unsafe;
            java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            unsafe = (sun.misc.Unsafe) uf.get(null);
            delegate = (JavaPlugin) unsafe.allocateInstance(rc);
            copyFields(this, delegate);
            delegate.onLoad();
        } catch (Throwable t) {
            ok = false;
        }
    }

    @Override
    public void onEnable() {
        if (!ok || delegate == null) {
            getLogger().severe("ScoreLicense plagini buzilgan, ishga tushirib bo'lmadi.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            setEnabled(delegate, true);
            rebindCommands(this, delegate);
            delegate.onEnable();
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) c = c.getCause();
            getLogger().severe("delegate onEnable failed: " + t);
            getLogger().severe("root cause: " + c);
            for (StackTraceElement el : c.getStackTrace()) getLogger().severe("  at " + el);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (delegate != null) {
                delegate.onDisable();
                setEnabled(delegate, false);
            }
        } catch (Throwable ignored) {}
    }
}
