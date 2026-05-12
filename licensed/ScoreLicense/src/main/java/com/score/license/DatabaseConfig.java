package com.score.license;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Obfuscated MySQL connection parameters.
 * Values are XOR-encrypted and decoded at runtime only.
 */
final class DatabaseConfig {

    private DatabaseConfig() {}

    private static final byte[] K = {
        0x53, 0x63, 0x4C, 0x31, 0x63, 0x33, 0x6E, 0x73,
        0x33, 0x4B, 0x23, 0x79, 0x32, 0x30, 0x32, 0x34
    };

    private static final String E_HOST = "Pho/QA8eHRtcM04WXFVLUiYNPFAaHQ8fRCpaClZRRlV9DSlF";
    private static final String E_PORT = "YFB8Bw==";
    private static final String E_USER = "Z1F7BFIE";
    private static final String E_PASS = "IAsjWQlSBhxdeg==";
    private static final String E_DB   = "IAsjSQ5cABZKLVYXQlFLayAAI0MGXwcQViVQHG1UUA==";

    static String host() { return d(E_HOST); }
    static int    port() { return Integer.parseInt(d(E_PORT)); }
    static String user() { return d(E_USER); }
    static String pass() { return d(E_PASS); }
    static String db()   { return d(E_DB); }

    private static String d(String e) {
        byte[] data = Base64.getDecoder().decode(e);
        byte[] r = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            r[i] = (byte) (data[i] ^ K[i % K.length]);
        }
        return new String(r, StandardCharsets.UTF_8);
    }
}
