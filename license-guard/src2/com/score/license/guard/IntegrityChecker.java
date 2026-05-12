package com.score.license.guard;

import java.io.InputStream;
import java.security.MessageDigest;

public final class IntegrityChecker {
    private IntegrityChecker() {}

    public static byte[] sha256OfResource(ClassLoader cl, String resource) {
        try (InputStream in = cl.getResourceAsStream(resource)) {
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

    public static boolean equals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        return MessageDigest.isEqual(a, b);
    }

    public static byte[] hex(String s) {
        int n = s.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    | Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }
}
