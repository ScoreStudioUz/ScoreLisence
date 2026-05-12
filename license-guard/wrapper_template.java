package __PKG__;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

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

    private JavaPlugin delegate;

    private static byte[] secret() {
        ByteBuffer bb = ByteBuffer.allocate(32);
        for (int i = 0; i < 4; i++) bb.putLong(X[i] ^ M[i]);
        return bb.array();
    }

    private boolean verifyLicense() {
        try {
            Plugin lic = Bukkit.getPluginManager().getPlugin("ScoreLicense");
            if (lic == null) {
                getLogger().severe("ScoreLicense plugin not found.");
                return false;
            }
            Class<?> api = Class.forName("com.score.license.LicenseAPI", true, lic.getClass().getClassLoader());

            Method idM = api.getMethod("identity");
            Object idObj = idM.invoke(null);
            if (!EXPECTED_ID.equals(String.valueOf(idObj))) {
                getLogger().severe("ScoreLicense identity mismatch.");
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
                getLogger().severe("ScoreLicense HMAC mismatch.");
                return false;
            }
            return true;
        } catch (Throwable t) {
            getLogger().severe("License verification error: " + t);
            return false;
        }
    }

    private static void copy(JavaPlugin from, JavaPlugin to) throws Exception {
        Class<?> jp = JavaPlugin.class;
        String[] fields = {"loader","server","file","description","dataFolder","classLoader","configFile","logger","pluginFile"};
        for (String fn : fields) {
            try {
                Field f = jp.getDeclaredField(fn);
                f.setAccessible(true);
                Object v = f.get(from);
                if (v != null) f.set(to, v);
            } catch (NoSuchFieldException ignored) {}
        }
    }

    @Override
    public void onLoad() {
        try {
            Class<?> rc = Class.forName(REAL_MAIN, true, getClass().getClassLoader());
            Constructor<?> ctor = rc.getDeclaredConstructor();
            ctor.setAccessible(true);
            delegate = (JavaPlugin) ctor.newInstance();
            copy(this, delegate);
            delegate.onLoad();
        } catch (Throwable t) {
            getLogger().severe("delegate onLoad failed: " + t);
        }
    }

    @Override
    public void onEnable() {
        if (!verifyLicense()) {
            getLogger().severe("=== License check FAILED. Disabling. ===");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (delegate == null) {
            getLogger().severe("delegate not initialized.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            delegate.onEnable();
        } catch (Throwable t) {
            getLogger().severe("delegate onEnable failed: " + t);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (delegate != null) delegate.onDisable();
        } catch (Throwable ignored) {}
    }
}
