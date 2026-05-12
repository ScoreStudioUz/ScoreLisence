package com.score.license;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;

public final class LicenseAPI {
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

    private static volatile boolean licensed = false;
    private static volatile int licenseLevel = 0;

    private LicenseAPI() {}

    public static boolean isLicensed() { return licensed; }
    public static int getLicenseLevel() { return licenseLevel; }

    public static void setLicensed(boolean v, int level) {
        licensed = v;
        licenseLevel = level;
    }

    public static void setLicensed(boolean v) {
        licensed = v;
    }

    private static byte[] secret() {
        ByteBuffer bb = ByteBuffer.allocate(32);
        for (int i = 0; i < 4; i++) bb.putLong(X[i] ^ M[i]);
        return bb.array();
    }

    public static byte[] signChallenge(byte[] nonce) {
        try {
            byte[] s = secret();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(s, "HmacSHA256"));
            byte[] r = mac.doFinal(nonce);
            for (int i = 0; i < s.length; i++) s[i] = 0;
            return r;
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    public static String identity() {
        return Long.toHexString((X[0] ^ M[0]) ^ (X[3] ^ M[3]));
    }
}
