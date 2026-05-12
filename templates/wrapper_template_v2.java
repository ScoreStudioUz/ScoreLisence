package __PKG__;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class __NAME__ extends JavaPlugin {
    private static final long[] M = {
        8398573456194776669L,
        8269354300280025007L,
        2206680772570605703L,
        -1104072232727625432L,
    };
    private static final long[] X = {
        6130692733054075381L,
        -1934450547547622840L,
        -2764922105262081080L,
        2317992633624059855L,
    };
    private static final String REAL_MAIN = "__REAL_MAIN__";
    private static final String EXPECTED_ID = "__EXPECTED_ID__";

    // Cross-jar SHA-256 of critical ScoreLicense classes
    private static final String[][] LIC_HASHES = {
        {"com/score/license/LicenseDatabase.class", "1dc2e4241f9c80a94a4cc8d3e2caad89f2ec7e02b104ec389634c6485cc4c2c3"},
        {"com/score/license/LicenseStorage.class",  "39af05e903c6617bc0f786eed454d12aa7daa03a1e5063c37674f4ae57dd1267"},
        {"com/score/license/LicenseHeartbeat.class","ba69a074085d37052f9a4fd9aef3cc5c06f1f3a6a502889f2b9d5941936cf348"},
        {"com/score/license/LicenseAPI.class",      "be80d46863305646ab92e7938bcf6909133664d339367822fda2fa5774a70b48"},
        {"com/score/license/lIIIll1O0OOIl0IOOl.class", "b7ba38ba15f7fda4b21288985e6ac1a3b39458244ab883dcd2013e04630b7441"},
    };

    private JavaPlugin delegate;

    private static byte[] secret() {
        ByteBuffer bb = ByteBuffer.allocate(32);
        for (int i = 0; i < 4; i++) bb.putLong(X[i] ^ M[i]);
        return bb.array();
    }

    private static byte[] hex(String s) {
        int n = s.length();
        byte[] o = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            o[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    | Character.digit(s.charAt(i + 1), 16));
        }
        return o;
    }

    private static byte[] sha256OfResource(ClassLoader cl, String r) {
        try (InputStream in = cl.getResourceAsStream(r)) {
            if (in == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return md.digest();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean verifyLicense() {
        try {
            Plugin lic = Bukkit.getPluginManager().getPlugin("ScoreLicense");
            if (lic == null) {
                getLogger().severe("Iltimos haqiqiy ScoreLicense plaginini ornating");
                return false;
            }
            ClassLoader licCl = lic.getClass().getClassLoader();

            for (String[] pair : LIC_HASHES) {
                byte[] got = sha256OfResource(licCl, pair[0]);
                byte[] exp = hex(pair[1]);
                if (got == null || got.length != exp.length || !MessageDigest.isEqual(got, exp)) {
                    getLogger().severe("Iltimos haqiqiy ScoreLicense plaginini ornating");
                    return false;
                }
            }

            Class<?> api = Class.forName("com.score.license.LicenseAPI", true, licCl);

            Method idM = api.getMethod("identity");
            Object idObj = idM.invoke(null);
            if (!EXPECTED_ID.equals(String.valueOf(idObj))) {
                getLogger().severe("Iltimos haqiqiy ScoreLicense plaginini ornating");
                return false;
            }

            byte[] nonce = new byte[32];
            new SecureRandom().nextBytes(nonce);

            Method sign = api.getMethod("signChallenge", byte[].class);
            byte[] got = (byte[]) sign.invoke(null, (Object) nonce);

            byte[] s = secret();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(s, "HmacSHA256"));
            byte[] expected = mac.doFinal(nonce);
            for (int i = 0; i < s.length; i++) s[i] = 0;

            if (!MessageDigest.isEqual(expected, got)) {
                getLogger().severe("Iltimos haqiqiy ScoreLicense plaginini ornating");
                return false;
            }
            return true;
        } catch (Throwable t) {
            getLogger().severe("Iltimos haqiqiy ScoreLicense plaginini ornating");
            return false;
        }
    }

    private static void copy(JavaPlugin from, JavaPlugin to) throws Exception {
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
            Class<?> rc = Class.forName(REAL_MAIN, true, getClass().getClassLoader());
            java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
            delegate = (JavaPlugin) unsafe.allocateInstance(rc);
            copy(this, delegate);
            delegate.onLoad();
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) c = c.getCause();
            getLogger().severe("delegate onLoad failed: " + t);
            getLogger().severe("root cause: " + c);
            for (StackTraceElement el : c.getStackTrace()) getLogger().severe("  at " + el);
        }
    }

    @Override
    public void onEnable() {
        if (!verifyLicense()) {
            getLogger().severe("Iltimos haqiqiy ScoreLicense plaginini ornating");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (delegate == null) {
            getLogger().severe("delegate not initialized.");
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
