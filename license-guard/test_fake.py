"""Build a FAKE ScoreLicense.jar that pretends to be the real one and serves a fake LicenseAPI.
Then load it + a guard in a JVM to confirm the guard rejects it.

We can't easily run a real Bukkit server here, but we CAN test the guard logic in isolation by:
1. Compiling a fake LicenseAPI that returns either:
   a) garbage HMAC (no secret)
   b) wrong identity()
2. Building a mini test harness that reproduces what the guard does:
   - generate nonce
   - call signChallenge
   - check identity
   - cross-jar hash
3. Confirming each attack is rejected.
"""
import os, subprocess, zipfile, shutil, sys, hashlib, secrets

ROOT = "C:/Users/shoxg/OneDrive/Desktop/plugins"
GUARD = os.path.join(ROOT, "license-guard")
WORK = os.path.join(GUARD, "test-fake")
PAPER = os.path.join(ROOT, "libs/paper-api-1.21.1-R0.1-SNAPSHOT.jar")
REAL_LIC = os.path.join(ROOT, "obfuscated-final8b/licensed-ScoreLicense-1.0.0-obf.jar")

if os.path.exists(WORK): shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK)

# --- Step 1: build a fake LicenseAPI with no secret (returns junk HMAC + wrong identity) ---
fake_src = os.path.join(WORK, "fake/com/score/license/LicenseAPI.java")
os.makedirs(os.path.dirname(fake_src), exist_ok=True)
with open(fake_src, "w", encoding="utf-8") as f:
    f.write("""package com.score.license;
public final class LicenseAPI {
    private LicenseAPI() {}
    public static byte[] signChallenge(byte[] nonce) {
        // Attacker has no secret -> returns deterministic garbage
        byte[] r = new byte[32];
        for (int i = 0; i < 32; i++) r[i] = (byte) (i ^ 0x55);
        return r;
    }
    public static String identity() { return "deadbeef"; }
}
""")

fake_out = os.path.join(WORK, "fake-out")
os.makedirs(fake_out)
r = subprocess.run(["javac","-d",fake_out,"-source","8","-target","8",fake_src],
                   capture_output=True, text=True)
if r.returncode: print(r.stderr); sys.exit(1)

# --- Step 2: build a test harness that mimics what guard does ---
harness_src = os.path.join(WORK, "Harness.java")
with open(harness_src, "w", encoding="utf-8") as f:
    f.write("""
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class Harness {
    static final long[] M = {
        8398573456194776669L, 8269354300280025007L,
        2206680772570605703L, -1104072232727625432L,
    };
    static final long[] X = {
        6130692733054075381L, -1934450547547622840L,
        -2764922105262081080L, 2317992633624059855L,
    };
    static final String EXPECTED_ID = "f11f84a33685f14f";
    static final String[][] HASHES = {
        {"com/score/license/LicenseDatabase.class", "06ab4797520ed847b9f60314ca05c777c386804be637dda9769f6e3581dae4ab"},
        {"com/score/license/LicenseStorage.class",  "9a86254a9bea67761ef6a551364bb75460077fe9789de07332084156748891cb"},
        {"com/score/license/LicenseHeartbeat.class","e2a5b0d7087293bc8850e8039865f208d3042162809790e3eee317a698320f32"},
        {"com/score/license/LicenseAPI.class",      "f3bf93f38ef07b7a7d3d610d7d0f3070319e82756a1eac94b69faf36f93816ec"},
        {"com/score/license/lIIIll1O0OOIl0IOOl.class", "9d1aed4739d457dc9fb247c4bbf5238b3190733f20cea466c3ac9a6742c82b43"},
    };
    static byte[] secret() {
        ByteBuffer bb = ByteBuffer.allocate(32);
        for (int i = 0; i < 4; i++) bb.putLong(X[i] ^ M[i]);
        return bb.array();
    }
    static byte[] hex(String s) {
        int n = s.length(); byte[] o = new byte[n/2];
        for (int i = 0; i < n; i += 2)
            o[i/2] = (byte)((Character.digit(s.charAt(i),16)<<4) | Character.digit(s.charAt(i+1),16));
        return o;
    }
    static byte[] sha(ClassLoader cl, String r) throws Exception {
        try (InputStream in = cl.getResourceAsStream(r)) {
            if (in == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf,0,n);
            return md.digest();
        }
    }

    public static void main(String[] args) throws Exception {
        String which = args[0];           // "real" | "fake"
        String jarPath = args[1];

        URLClassLoader cl = new URLClassLoader(
            new URL[]{new File(jarPath).toURI().toURL()},
            ClassLoader.getSystemClassLoader());

        // Step B: cross-jar hash
        boolean hashOk = true;
        for (String[] p : HASHES) {
            byte[] got = sha(cl, p[0]);
            byte[] exp = hex(p[1]);
            boolean ok = (got != null && got.length == exp.length && MessageDigest.isEqual(got, exp));
            System.out.println("  hash " + p[0] + " -> " + (ok ? "OK" : "MISMATCH (got="+(got==null?"null":bytesToHex(got).substring(0,16))+"...)"));
            if (!ok) hashOk = false;
        }
        if (!hashOk) {
            System.out.println("RESULT: REJECTED (hash mismatch)");
            return;
        }

        // Step identity + HMAC
        Class<?> api = Class.forName("com.score.license.LicenseAPI", true, cl);
        Method idM = api.getMethod("identity");
        String id = (String) idM.invoke(null);
        System.out.println("  identity = " + id + " (expected " + EXPECTED_ID + ")");
        if (!EXPECTED_ID.equals(id)) {
            System.out.println("RESULT: REJECTED (identity)");
            return;
        }

        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        Method sign = api.getMethod("signChallenge", byte[].class);
        byte[] got = (byte[]) sign.invoke(null, (Object) nonce);

        byte[] s = secret();
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(s,"HmacSHA256"));
        byte[] exp = m.doFinal(nonce);
        boolean hOk = MessageDigest.isEqual(exp, got);
        System.out.println("  HMAC " + (hOk ? "OK" : "MISMATCH"));
        System.out.println("RESULT: " + (hOk ? "ACCEPTED" : "REJECTED (HMAC)"));
    }

    static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
""")

r = subprocess.run(["javac","-d",WORK,"-source","8","-target","8",harness_src],
                   capture_output=True, text=True)
if r.returncode: print(r.stderr); sys.exit(1)

# --- Step 3: package fake ScoreLicense jar ---
fake_jar = os.path.join(WORK, "fake-ScoreLicense.jar")
with zipfile.ZipFile(fake_jar, "w", zipfile.ZIP_DEFLATED) as z:
    # only the fake LicenseAPI; missing LicenseDatabase, LicenseStorage etc.
    src = os.path.join(fake_out, "com/score/license/LicenseAPI.class")
    with open(src, "rb") as f: z.writestr("com/score/license/LicenseAPI.class", f.read())
    z.writestr("plugin.yml", "name: ScoreLicense\nversion: 1.0.0\nmain: com.score.license.LicenseAPI\napi-version: 1.20\n")
print(f"\nFake ScoreLicense built: {fake_jar} ({os.path.getsize(fake_jar)} bytes)")

# --- Step 4: run harness against REAL and FAKE jars ---
print("\n=== TEST 1: REAL ScoreLicense.jar ===")
r = subprocess.run(["java","-cp",WORK,"Harness","real",REAL_LIC],
                   capture_output=True, text=True)
print(r.stdout)
if r.returncode: print("STDERR:", r.stderr)

print("=== TEST 2: FAKE ScoreLicense.jar (no secret, missing classes) ===")
r = subprocess.run(["java","-cp",WORK,"Harness","fake",fake_jar],
                   capture_output=True, text=True)
print(r.stdout)
if r.returncode: print("STDERR:", r.stderr)
