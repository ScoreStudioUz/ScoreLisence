#!/usr/bin/env python3
"""
protect.py - Universal protector for Bukkit/Spigot/Paper plugin JARs.

Interactive, single-file, standard-library-only (javac/java via subprocess).
Works with ANY plugin - it never renames the plugin.yml main class, never
touches Listener / CommandExecutor contracts, and degrades gracefully when an
optional tool (Skidfuscator / ProGuard / dProtect) is missing or crashes.

Pipeline
--------
  input.jar
    -> analyze plugin.yml + build keep-list (main / listeners / executors)
    -> strip debug info (SourceFile, Line/LocalVariable tables)  [pure python]
    -> ProGuard      (rename + shrink)         [optional, skipped if absent]
    -> dProtect      (MBA + flow)              [optional, skipped if absent]
    -> Skidfuscator  (flow GEN3 + strings)     [optional, graceful fallback]
    -> decoy-class injection + ZIP anti-parse tricks
    -> license guard (HTTP + integrity + anti-tamper + watermark) [optional]
    -> repack (deflate level 9, manifest, watermark)
    -> dist/<PluginName>-protected.jar
  protect-work/ is removed at the end.

Run:  python3 protect.py         (interactive)
"""

import os
import re
import sys
import time
import zlib
import shutil
import struct
import zipfile
import hashlib
import subprocess

# --------------------------------------------------------------------------- #
#  logging                                                                     #
# --------------------------------------------------------------------------- #

_T0 = None


def _now():
    # Date.now() is fine here (plain python, not a workflow script).
    return time.strftime("%H:%M:%S")


def log(msg):
    print(f"[{_now()}] {msg}", flush=True)


def step(msg):
    print(f"\n[{_now()}] === {msg} ===", flush=True)


def warn(msg):
    print(f"[{_now()}] !! {msg}", flush=True)


# --------------------------------------------------------------------------- #
#  interactive prompt helpers                                                  #
# --------------------------------------------------------------------------- #

def _clean_path(raw: str) -> str:
    """Normalise a drag-and-dropped path (strips quotes / escaped spaces)."""
    if raw is None:
        return ""
    s = raw.strip()
    if len(s) >= 2 and s[0] == s[-1] and s[0] in ("'", '"'):
        s = s[1:-1]
    s = s.replace("\\ ", " ")
    return os.path.expanduser(s.strip())


def ask(prompt, default=None, must_exist=False, is_dir=False):
    while True:
        suffix = f" [{default}]" if default else ""
        raw = input(f"{prompt}{suffix}: ").strip()
        val = _clean_path(raw) if raw else (default or "")
        if not val:
            if default is None:
                print("  (required)")
                continue
            val = default
        if must_exist and not os.path.exists(val):
            print(f"  not found: {val}")
            continue
        if is_dir and val and not os.path.isdir(val) and must_exist:
            print(f"  not a directory: {val}")
            continue
        return val


def ask_yesno(prompt, default=False):
    d = "Y/n" if default else "y/N"
    raw = input(f"{prompt} [{d}]: ").strip().lower()
    if not raw:
        return default
    return raw in ("y", "yes", "1", "true", "ha", "да", "da")


# --------------------------------------------------------------------------- #
#  minimal .class parser (fail-safe: any error -> caller keeps original bytes) #
# --------------------------------------------------------------------------- #

# Constant-pool tag -> fixed payload size (after the 1-byte tag).
# Utf8 (1) is variable and handled specially. Long/Double take two slots.
_CP_FIXED = {
    3: 4,   # Integer
    4: 4,   # Float
    5: 8,   # Long   (+1 slot)
    6: 8,   # Double (+1 slot)
    7: 2,   # Class
    8: 2,   # String
    9: 4,   # Fieldref
    10: 4,  # Methodref
    11: 4,  # InterfaceMethodref
    12: 4,  # NameAndType
    15: 3,  # MethodHandle
    16: 2,  # MethodType
    17: 4,  # Dynamic
    18: 4,  # InvokeDynamic
    19: 2,  # Module
    20: 2,  # Package
}

# Debug / non-essential attributes we strip from real classes.
_STRIP_ATTRS = {
    b"SourceFile",
    b"SourceDebugExtension",
    b"LineNumberTable",
    b"LocalVariableTable",
    b"LocalVariableTypeTable",
    b"Deprecated",
}


class _Reader:
    __slots__ = ("d", "p")

    def __init__(self, d, p=0):
        self.d = d
        self.p = p

    def u1(self):
        v = self.d[self.p]
        self.p += 1
        return v

    def u2(self):
        v = struct.unpack_from(">H", self.d, self.p)[0]
        self.p += 2
        return v

    def u4(self):
        v = struct.unpack_from(">I", self.d, self.p)[0]
        self.p += 4
        return v

    def take(self, n):
        v = self.d[self.p:self.p + n]
        self.p += n
        return v


def parse_constant_pool(data):
    """Return (utf8_map, class_name_indices, cp_end_offset).

    utf8_map: {index: str}
    class_name_indices: {index: name_index}  for CONSTANT_Class entries
    """
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("bad magic")
    r = _Reader(data, 8)  # skip magic + minor/major
    count = r.u2()
    utf8 = {}
    classes = {}
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:  # Utf8
            length = r.u2()
            raw = r.take(length)
            try:
                utf8[i] = raw.decode("utf-8")
            except UnicodeDecodeError:
                utf8[i] = raw.decode("latin-1")
        elif tag == 7:  # Class
            classes[i] = r.u2()
        elif tag in _CP_FIXED:
            r.take(_CP_FIXED[tag])
        else:
            raise ValueError(f"unknown cp tag {tag}")
        i += 2 if tag in (5, 6) else 1
    return utf8, classes, r.p


def class_info(data):
    """Return (this_name, super_name, [interface_names]) or None on failure."""
    try:
        utf8, classes, off = parse_constant_pool(data)
        r = _Reader(data, off)
        r.u2()  # access_flags
        this_i = r.u2()
        super_i = r.u2()
        n_ifaces = r.u2()
        ifaces = [r.u2() for _ in range(n_ifaces)]

        def cname(ci):
            return utf8.get(classes.get(ci)) if ci else None

        return (cname(this_i), cname(super_i), [cname(x) for x in ifaces])
    except Exception:
        return None


def _skip_attributes(r):
    """Advance the reader past an attributes[] block, returning raw slices."""
    n = r.u2()
    out = []
    for _ in range(n):
        name_i = r.u2()
        length = r.u4()
        body = r.take(length)
        out.append((name_i, body))
    return out


def strip_debug(data):
    """Remove debug attributes from a class. Fail-safe: returns original bytes
    unchanged if anything about the class is unexpected."""
    try:
        utf8, classes, cp_end = parse_constant_pool(data)

        def is_strip(name_i):
            return utf8.get(name_i, "").encode() in _STRIP_ATTRS

        r = _Reader(data, cp_end)
        head_start = cp_end
        r.u2()  # access_flags
        r.u2()  # this_class
        r.u2()  # super_class
        n_ifaces = r.u2()
        r.take(2 * n_ifaces)

        out = bytearray()
        out += data[:head_start]                   # magic..constant_pool
        out += data[head_start:r.p]                # flags/this/super/ifaces

        def emit_members():
            """fields[] then methods[] share identical structure."""
            count = r.u2()
            buf = bytearray(struct.pack(">H", count))
            for _ in range(count):
                m_start = r.p
                r.u2()  # access_flags
                r.u2()  # name_index
                r.u2()  # descriptor_index
                attrs = _read_member_attrs()
                buf += data[m_start:m_start + 6]    # flags/name/desc verbatim
                buf += attrs
            return buf

        def _read_member_attrs():
            n = r.u2()
            kept = []
            for _ in range(n):
                name_i = r.u2()
                length = r.u4()
                body = r.take(length)
                if is_strip(name_i):
                    continue
                if utf8.get(name_i) == "Code":
                    body = _clean_code(body)
                kept.append((name_i, body))
            b = bytearray(struct.pack(">H", len(kept)))
            for name_i, body in kept:
                b += struct.pack(">HI", name_i, len(body))
                b += body
            return b

        def _clean_code(body):
            """Strip LineNumberTable / LocalVariable* nested in a Code attr."""
            cr = _Reader(body, 0)
            max_stack = cr.u2()
            max_locals = cr.u2()
            code_len = cr.u4()
            code = cr.take(code_len)
            exc_len = cr.u2()
            exc = cr.take(exc_len * 8)
            n_attr = cr.u2()
            kept = []
            for _ in range(n_attr):
                ni = cr.u2()
                ln = cr.u4()
                bd = cr.take(ln)
                if is_strip(ni):
                    continue
                kept.append((ni, bd))
            nb = bytearray()
            nb += struct.pack(">HHI", max_stack, max_locals, code_len)
            nb += code
            nb += struct.pack(">H", exc_len)
            nb += exc
            nb += struct.pack(">H", len(kept))
            for ni, bd in kept:
                nb += struct.pack(">HI", ni, len(bd))
                nb += bd
            return bytes(nb)

        out += emit_members()                       # fields
        out += emit_members()                       # methods

        # class-level attributes
        n = r.u2()
        kept = []
        for _ in range(n):
            name_i = r.u2()
            length = r.u4()
            bd = r.take(length)
            if is_strip(name_i):
                continue
            kept.append((name_i, bd))
        out += struct.pack(">H", len(kept))
        for name_i, bd in kept:
            out += struct.pack(">HI", name_i, len(bd))
            out += bd

        new = bytes(out)
        # sanity: must still parse
        parse_constant_pool(new)
        return new
    except Exception:
        return data


# --------------------------------------------------------------------------- #
#  JAR analysis                                                                #
# --------------------------------------------------------------------------- #

def read_plugin_yml(jar):
    with zipfile.ZipFile(jar) as z:
        for name in ("plugin.yml", "paper-plugin.yml"):
            if name in z.namelist():
                return z.read(name).decode("utf-8", "replace"), name
    raise SystemExit("plugin.yml not found - is this a Bukkit/Paper plugin JAR?")


def yaml_scalar(text, key):
    m = re.search(rf"^{key}:\s*(.+?)\s*$", text, re.M)
    if not m:
        return None
    return m.group(1).strip().strip("'\"")


def yaml_list(text, key):
    """Parse `key: [a, b]` or a YAML block list into a python list."""
    m = re.search(rf"^{key}:\s*\[(.*?)\]\s*$", text, re.M)
    if m:
        return [x.strip() for x in m.group(1).split(",") if x.strip()]
    out = []
    block = re.search(rf"^{key}:\s*$((?:\n[ \t]+-.*)+)", text, re.M)
    if block:
        for line in block.group(1).splitlines():
            item = line.strip().lstrip("-").strip()
            if item:
                out.append(item)
    return out


# org.bukkit interfaces whose implementers must never be renamed/broken.
_KEEP_IFACES = {
    "org/bukkit/event/Listener",
    "org/bukkit/command/CommandExecutor",
    "org/bukkit/command/TabExecutor",
    "org/bukkit/command/TabCompleter",
}


def analyze(jar):
    """Return dict with plugin metadata and obfuscation keep-list."""
    yml_text, yml_name = read_plugin_yml(jar)
    main = yaml_scalar(yml_text, "main")
    name = yaml_scalar(yml_text, "name") or "Plugin"
    depend = yaml_list(yml_text, "depend")
    softdepend = yaml_list(yml_text, "softdepend")

    main_internal = main.replace(".", "/") if main else None
    keep = set()
    if main_internal:
        keep.add(main_internal)

    obf_candidates = []
    reasons = {}
    with zipfile.ZipFile(jar) as z:
        for entry in z.namelist():
            if not entry.endswith(".class"):
                continue
            data = z.read(entry)
            info = class_info(data)
            internal = entry[:-6]
            if info is None:
                reasons[internal] = "unparseable (kept verbatim)"
                keep.add(internal)
                continue
            _this, _super, ifaces = info
            hit = _KEEP_IFACES.intersection(x for x in ifaces if x)
            if internal == main_internal:
                reasons[internal] = "plugin.yml main"
                keep.add(internal)
            elif hit:
                reasons[internal] = "implements " + ", ".join(sorted(hit))
                keep.add(internal)
            else:
                obf_candidates.append(internal)

    return {
        "yml_text": yml_text,
        "yml_name": yml_name,
        "name": name,
        "main": main,
        "main_internal": main_internal,
        "depend": depend,
        "softdepend": softdepend,
        "keep": keep,
        "obf_candidates": obf_candidates,
        "keep_reasons": reasons,
    }


# --------------------------------------------------------------------------- #
#  deterministic-but-varied random names (no Math.random needed)              #
# --------------------------------------------------------------------------- #

class NameGen:
    """Obfuscated-looking identifiers derived from a rolling hash seed."""

    _ALPHA = "lIO01"          # visually confusing
    _ALPHA2 = "abcdefghijklmnopqrstuvwxyz"

    def __init__(self, seed):
        self._h = hashlib.sha256(seed.encode()).digest()
        self._i = 0
        self._used = set()

    def _byte(self):
        b = self._h[self._i % len(self._h)]
        self._i += 1
        if self._i % len(self._h) == 0:
            self._h = hashlib.sha256(self._h).digest()
        return b

    def ident(self, length=14, alpha=None):
        alpha = alpha or self._ALPHA
        while True:
            first = "lIO"[self._byte() % 3]
            rest = "".join(alpha[self._byte() % len(alpha)] for _ in range(length - 1))
            name = first + rest
            if name not in self._used:
                self._used.add(name)
                return name

    def pkg_word(self, length=8):
        return "".join(self._ALPHA2[self._byte() % 26] for _ in range(length))


# --------------------------------------------------------------------------- #
#  decoy-class generation                                                      #
# --------------------------------------------------------------------------- #

_DECOY_TEMPLATE = """package {pkg};

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.*;

public final class {cls} {{
    private static final String[] {f1} = {{ {strs} }};
    private final Map<String, Object> {f2} = new HashMap<String, Object>();
    private long {f3} = {seed}L;

    public {cls}() {{
        for (int i = 0; i < {f1}.length; i++) {{
            {f2}.put({f1}[i], Integer.valueOf(({f1}[i].hashCode() ^ (int) {f3})));
        }}
    }}

    public boolean {m1}(String key) {{
        if (key == null) return false;
        Plugin pl = Bukkit.getPluginManager().getPlugin({f1}[0]);
        long acc = {f3};
        for (Map.Entry<String, Object> e : {f2}.entrySet()) {{
            acc = (acc * 31L) ^ e.getKey().length();
        }}
        return pl != null && (acc & 1L) == 0L && key.length() > 0;
    }}

    public int {m2}(int a, int b) {{
        int r = a;
        for (int i = 0; i < b; i++) {{
            r = (r << 1) ^ (r >>> 3) ^ (int) {f3};
        }}
        return r;
    }}

    public String {m3}() {{
        StringBuilder sb = new StringBuilder();
        for (String s : {f1}) sb.append(s).append(':');
        return sb.toString();
    }}
}}
"""


def gen_decoy_sources(out_dir, ng, count, watermark):
    """Write `count` decoy .java files under a random package. Returns pkg path
    (internal form) so callers can compile them together."""
    pkg_parts = [ng.pkg_word(6), ng.pkg_word(7)]
    pkg = ".".join(pkg_parts)
    pkg_dir = os.path.join(out_dir, *pkg_parts)
    os.makedirs(pkg_dir, exist_ok=True)
    real_strings = [
        "PlaceholderAPI", "Vault", "config.yml", "messages.yml",
        "§aEnabled", "§cDisabled", "player.data", "SELECT * FROM",
        "SecretKey", "HmacSHA256", "AES/CBC/PKCS5Padding", watermark,
        "onEnable", "onDisable", "getServer", "broadcastMessage",
    ]
    for idx in range(count):
        cls = ng.ident(12)
        chunk = [real_strings[(idx + k) % len(real_strings)] for k in range(4)]
        strs = ", ".join('"' + s.replace('"', '\\"') + '"' for s in chunk)
        src = _DECOY_TEMPLATE.format(
            pkg=pkg, cls=cls,
            f1=ng.ident(9), f2=ng.ident(9), f3=ng.ident(9),
            m1=ng.ident(10), m2=ng.ident(10), m3=ng.ident(10),
            strs=strs,
            seed=(0x5DEECE66D ^ (idx * 0x9E3779B1)) & 0x7FFFFFFFFFFF,
        )
        with open(os.path.join(pkg_dir, cls + ".java"), "w", encoding="utf-8") as f:
            f.write(src)
    return pkg


def make_malformed_decoys(ng, count):
    """Return {arcname: bytes} of syntactically-broken .class blobs.

    These are NEVER referenced by real code, so the JVM (which loads classes
    lazily and only on first use) never verifies them - but decompilers and
    ASM-based static analysers that eagerly walk every entry choke on the
    invalid constant pool / attribute data.
    """
    out = {}
    for i in range(count):
        pkg = ng.pkg_word(7)
        cls = ng.ident(11)
        # A header claiming a large constant-pool count, then garbage. Valid
        # magic keeps naive "is this a class?" checks happy; the bogus pool
        # derails real parsers.
        blob = bytearray()
        blob += b"\xca\xfe\xba\xbe"          # magic
        blob += struct.pack(">HH", 0, 61)    # minor / major (Java 17)
        blob += struct.pack(">H", 0xFFF0)    # constant_pool_count (huge)
        # a few plausible Utf8 entries then truncated junk
        for s in (cls, pkg, b"Code", b"gArBaGe"):
            sb = s.encode() if isinstance(s, str) else s
            blob += b"\x01" + struct.pack(">H", len(sb)) + sb
        seed = hashlib.sha256(f"{pkg}{cls}{i}".encode()).digest()
        blob += seed * 6                     # trailing garbage
        out[f"{pkg}/{cls}.class"] = bytes(blob)
    return out


# --------------------------------------------------------------------------- #
#  tool discovery                                                              #
# --------------------------------------------------------------------------- #

def find_java(prefer_versions=("17", "21")):
    """Return {version: java_path} for JAVA_HOME_x / common install roots."""
    found = {}
    # explicit env overrides
    for v in prefer_versions:
        env = os.environ.get(f"JAVA{v}_HOME") or os.environ.get(f"JAVA_HOME_{v}")
        if env and os.path.exists(os.path.join(env, "bin", "java")):
            found[v] = os.path.join(env, "bin", "java")
    roots = ["/usr/lib/jvm", "/opt", os.path.expanduser("~/.sdkman/candidates/java")]
    for root in roots:
        if not os.path.isdir(root):
            continue
        for d in os.listdir(root):
            jp = os.path.join(root, d, "bin", "java")
            if not os.path.exists(jp):
                continue
            for v in prefer_versions:
                if v in d and v not in found:
                    found[v] = jp
    # always include current java as fallback under its own version
    try:
        out = subprocess.run(["java", "-version"], capture_output=True, text=True)
        m = re.search(r'version "(\d+)', (out.stderr or out.stdout))
        if m:
            found.setdefault(m.group(1), shutil.which("java") or "java")
    except Exception:
        pass
    return found


# --------------------------------------------------------------------------- #
#  encrypted-string subsystem (xorshift64 keystream, Python <-> Java parity)   #
# --------------------------------------------------------------------------- #

_U64 = (1 << 64) - 1


def _keystream(key_u, seed_u, n):
    """xorshift64 byte keystream. MUST match the Java decoder byte-for-byte."""
    st = (key_u ^ seed_u) & _U64
    out = []
    for _ in range(n):
        st ^= (st << 13) & _U64
        st &= _U64
        st ^= (st >> 7)
        st ^= (st << 17) & _U64
        st &= _U64
        out.append(st & 0xFF)
    return out


class StrEnc:
    """Encrypts strings at build time so no sensitive literal (ScoreLicense,
    com.score.license.LicenseAPI, method names, messages) appears in the jar.

    Each string gets its own random seed; the shared 64-bit key lives split
    across the class. The Java side decrypts lazily into char[] (never a
    constant String), so a memory dump at one instant reveals little and a
    `strings` / grep over the jar reveals nothing.
    """

    def __init__(self, key_u, seed_base):
        self.key_u = key_u & _U64
        self._seed = seed_base & _U64
        self.entries = []  # list of (seed_u, [enc bytes])

    def add(self, s):
        self._seed = (self._seed * 6364136223846793005 + 1442695040888963407) & _U64
        seed = self._seed
        b = s.encode("utf-8")
        ks = _keystream(self.key_u, seed, len(b))
        enc = [(b[i] ^ ks[i]) & 0xFF for i in range(len(b))]
        idx = len(self.entries)
        self.entries.append((seed, enc))
        return idx  # index into the DEC table -> guard calls S(idx)

    def java_tables(self):
        """Return (seeds_literal, data_literal) Java array initialisers."""
        seeds = ", ".join(f"{s if s < (1 << 63) else s - (1 << 64)}L"
                          for s, _ in self.entries)
        rows = []
        for _seed, enc in self.entries:
            row = ",".join(str(x - 256 if x > 127 else x) for x in enc)  # signed byte
            rows.append("{" + row + "}")
        data = ",\n        ".join(rows)
        return seeds, data


# --------------------------------------------------------------------------- #
#  license-guard source (generated per-plugin)                                 #
#                                                                              #
#  Prior ScoreLicense cross-jar model, HARDENED:                              #
#   * every sensitive string is XOR-encrypted (S(i) decrypts to char[])       #
#   * SHA-256 hash-check of critical ScoreLicense classes (un-removable dep)  #
#   * LicenseAPI.identity() must equal expected id                            #
#   * HMAC-SHA256 signChallenge() round-trip with a fresh 32-byte nonce       #
#   * LicenseAPI.isLicensed() polled at load, enable, and every 5 min         #
#   * anti-debug / anti-agent (RuntimeMXBean input args, jdwp)                #
#   * anti-RE class probe (Recaf / ASM / CFR / Fernflower / agents present)   #
#   * self class-count sanity + redundant gates so no single NOP disables it  #
#   * optional HTTP server check layered on top                               #
# --------------------------------------------------------------------------- #

_GUARD_TEMPLATE = r"""package {pkg};

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class {name} extends JavaPlugin {{
    // secret key split into two halves; recombined only inside K()
    private static final long KA = {ka}L;
    private static final long KB = {kb}L;
    private static final long[] SS = {{ {seeds} }};          // per-string seeds
    private static final byte[][] SD = {{
        {sdata}
    }};

    // masked HMAC secret (S = SM ^ SX), identical scheme to ScoreLicense
    private static final long[] SM = {{ {sm} }};
    private static final long[] SX = {{ {sx} }};

    // expected SHA-256 of critical ScoreLicense classes. The class *paths* are
    // encrypted in the string table (LIC_NAME holds their S() indices); only the
    // hex digests live here, and a digest reveals nothing about the dependency.
    private static final int[] LIC_NAME = {{ {lic_names} }};
    private static final String[] LIC_HASH = {{ {lic_hash_vals} }};

    // native verification: real-main name XOR-encrypted with the key the .so
    // returns. {native_enabled} controls whether the native path is attempted.
    private static final boolean NATIVE = {native_enabled};
    private static final byte[] RM_ENC = {{ {rm_enc} }};   // real-main name ^ keystream(K_native)
    private static volatile boolean nativeOk = false;

    private JavaPlugin delegate;
    private volatile boolean dead = false;
    private volatile int beat = 0;

    private static long K() {{ return KA ^ KB; }}

    // native entry: (nonce, sig) -> real-main key, or 0 if sig invalid
    private static native long nk(byte[] nonce, byte[] sig);

    // extract the platform library from the jar and System.load it
    private boolean loadNative() {{
        try {{
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            String a = (arch.contains("64") && !arch.contains("arm")
                        && !arch.contains("aarch")) ? "x86_64" : arch;
            String dir, lib;
            if (os.contains("win")) {{ dir = "windows-" + a; lib = "guard.dll"; }}
            else if (os.contains("mac") || os.contains("darwin")) {{ dir = "macos-" + a; lib = "libguard.dylib"; }}
            else {{ dir = "linux-" + a; lib = "libguard.so"; }}
            String res = "native/" + dir + "/" + lib;
            InputStream in = getClass().getClassLoader().getResourceAsStream(res);
            if (in == null) return false;
            String sfx = lib.substring(lib.lastIndexOf('.'));
            java.io.File tmp = java.io.File.createTempFile("libsg" + S({SI_WM}), sfx);
            tmp.deleteOnExit();
            try (OutputStream o = new java.io.FileOutputStream(tmp)) {{
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }}
            in.close();
            System.load(tmp.getAbsolutePath());
            return true;
        }} catch (Throwable t) {{
            return false;
        }}
    }}

    // Resolve the real-main class name. When native is active we run the HMAC
    // challenge through the .so to obtain the key, then XOR-decrypt RM_ENC.
    // If anything about the native path fails, fall back to the encrypted
    // string (pure-Java verify still gates onEnable regardless).
    private String realMain() {{
        if (NATIVE && nativeOk) {{
            try {{
                Plugin lic = Bukkit.getPluginManager().getPlugin(S({SI_SCORELICENSE}));
                if (lic != null) {{
                    ClassLoader licCl = lic.getClass().getClassLoader();
                    Class<?> api = Class.forName(S({SI_API}), true, licCl);
                    byte[] nonce = new byte[32];
                    new SecureRandom().nextBytes(nonce);
                    Method sign = api.getMethod(S({SI_SIGN}), byte[].class);
                    byte[] sig = (byte[]) sign.invoke(null, (Object) nonce);
                    long k = nk(nonce, sig);
                    if (k != 0L) {{
                        long st = k;
                        char[] out = new char[RM_ENC.length];
                        for (int j = 0; j < RM_ENC.length; j++) {{
                            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
                            out[j] = (char) ((RM_ENC[j] ^ ((int) (st & 0xFFL))) & 0xFF);
                        }}
                        return new String(out);
                    }}
                }}
            }} catch (Throwable ignored) {{}}
        }}
        return S({SI_REAL_MAIN});
    }}

    // ---- string decryptor: S(i) -> plaintext, never stored as constant ---- #
    private static String S(int i) {{
        long key = K();
        long st = key ^ SS[i];
        byte[] e = SD[i];
        char[] out = new char[e.length];
        for (int j = 0; j < e.length; j++) {{
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            out[j] = (char) ((e[j] ^ ((int) (st & 0xFFL))) & 0xFF);
        }}
        return new String(out);
    }}

    private static byte[] hex(String s) {{
        byte[] o = new byte[s.length() / 2];
        for (int i = 0; i < o.length; i++)
            o[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return o;
    }}

    private static byte[] secret() {{
        byte[] out = new byte[SM.length * 8];
        for (int i = 0; i < SM.length; i++) {{
            long v = SM[i] ^ SX[i];
            for (int j = 0; j < 8; j++) out[i * 8 + j] = (byte) (v >>> (56 - j * 8));
        }}
        return out;
    }}

    private static byte[] sha256(ClassLoader cl, String res) {{
        try (InputStream in = cl.getResourceAsStream(res)) {{
            if (in == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return md.digest();
        }} catch (Throwable t) {{ return null; }}
    }}

    // ---- anti reverse-engineering: debugger / agent / RE-tool detection --- #
    private static boolean tampered() {{
        try {{
            java.lang.management.RuntimeMXBean rt =
                java.lang.management.ManagementFactory.getRuntimeMXBean();
            for (Object o : rt.getInputArguments()) {{
                String a = String.valueOf(o).toLowerCase();
                // jdwp debugger, java agents, recaf/instrumentation
                if (a.contains(S({SI_JDWP})) || a.contains(S({SI_AGENT}))
                        || a.contains(S({SI_AGENTLIB})) || a.contains(S({SI_JAR_AGENT}))) {{
                    return true;
                }}
            }}
        }} catch (Throwable ignored) {{}}
        // known RE / instrumentation classes loaded into this JVM
        String[] probes = {{ S({SI_RECAF}), S({SI_ASM}), S({SI_CFR}),
                            S({SI_FERN}), S({SI_JAVASSIST}), S({SI_BYTEBUDDY}),
                            S({SI_VINEFLOWER}), S({SI_PROCYON}), S({SI_JADX}),
                            S({SI_BCV}) }};
        for (String p : probes) {{
            try {{ Class.forName(p, false, {name}.class.getClassLoader()); return true; }}
            catch (Throwable ok) {{ /* not present -> good */ }}
        }}
        return false;
    }}

    // was onEnable invoked by the real Bukkit enable path, or by a manual
    // reflection call? A hand-rolled loader won't have the loader/manager
    // frames on the stack.
    private static boolean validCaller() {{
        try {{
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {{
                String cn = e.getClassName();
                if (cn.contains(S({SI_LOADER})) || cn.contains(S({SI_MANAGER}))
                        || cn.contains(S({SI_PAPERLOADER}))) return true;
            }}
        }} catch (Throwable ignored) {{}}
        return false;
    }}

    private static String hexOf(byte[] b) {{
        char[] hx = "0123456789abcdef".toCharArray();
        char[] o = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {{
            o[i * 2] = hx[(b[i] >> 4) & 0xf];
            o[i * 2 + 1] = hx[b[i] & 0xf];
        }}
        return new String(o);
    }}

    // whole-jar SHA-256 of the ScoreLicense artefact, located via its own
    // CodeSource (filename-independent). Blocks a look-alike jar swap.
    private static boolean jarHashOk(ClassLoader licCl) {{
        String pin = S({SI_LIC_JAR_HASH});
        if (pin.isEmpty()) return true;         // pinning disabled
        try {{
            Class<?> api = Class.forName(S({SI_API}), true, licCl);
            java.security.CodeSource cs =
                api.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return true; // can't locate
            java.io.File f = new java.io.File(cs.getLocation().toURI());
            if (!f.isFile()) return true;       // exploded/dir loader - skip
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new java.io.FileInputStream(f)) {{
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }}
            return pin.equals(hexOf(md.digest()));
        }} catch (Throwable t) {{
            return true;  // never brick on an unexpected environment
        }}
    }}

    // ---- timed wrapper: a NOP-patched verify0() returns in ~0ns; a genuine
    // one runs SecureRandom + 4x SHA-256 + HMAC + reflection (hundreds of us to
    // several ms). If it comes back implausibly fast the body was stubbed. The
    // 100us floor is deliberately conservative so no real server trips it.
    private boolean verify() {{
        long t0 = System.nanoTime();
        boolean ok = verify0();
        long dt = System.nanoTime() - t0;
        if (ok && dt < 100_000L) return false;   // too fast => patched
        return ok;
    }}

    // ---- the core cross-jar ScoreLicense verification (prior model) ------- #
    private boolean verify0() {{
        try {{
            if (tampered()) return false;
            Plugin lic = Bukkit.getPluginManager().getPlugin(S({SI_SCORELICENSE}));
            if (lic == null) return false;
            ClassLoader licCl = lic.getClass().getClassLoader();

            // 0) whole-jar hash pin: blocks a same-class-names look-alike jar
            if (!jarHashOk(licCl)) return false;

            // 1) every critical ScoreLicense class must hash exactly
            for (int i = 0; i < LIC_NAME.length; i++) {{
                byte[] got = sha256(licCl, S(LIC_NAME[i]));
                byte[] exp = hex(LIC_HASH[i]);
                if (got == null || got.length != exp.length
                        || !MessageDigest.isEqual(got, exp)) return false;
            }}

            Class<?> api = Class.forName(S({SI_API}), true, licCl);

            // 2) identity() must equal the expected fingerprint
            Method idM = api.getMethod(S({SI_IDENTITY}));
            Object idObj = idM.invoke(null);
            if (!S({SI_EXPECTED_ID}).equals(String.valueOf(idObj))) return false;

            // 3) live HMAC challenge with a fresh nonce
            byte[] nonce = new byte[32];
            new SecureRandom().nextBytes(nonce);
            Method sign = api.getMethod(S({SI_SIGN}), byte[].class);
            byte[] got = (byte[]) sign.invoke(null, (Object) nonce);
            byte[] s = secret();
            Mac mac = Mac.getInstance(S({SI_HMAC}));
            mac.init(new SecretKeySpec(s, S({SI_HMAC})));
            byte[] want = mac.doFinal(nonce);
            for (int i = 0; i < s.length; i++) s[i] = 0;
            if (got == null || !MessageDigest.isEqual(want, got)) return false;

            // 4) server-side flag
            Method licM = api.getMethod(S({SI_ISLICENSED}));
            Object res = licM.invoke(null);
            if (!(res instanceof Boolean) || !((Boolean) res).booleanValue()) return false;

            return httpCheck();
        }} catch (Throwable t) {{
            return false;
        }}
    }}

    // optional external server check (fail-open on network error only)
    private boolean httpCheck() {{
        String url = S({SI_LIC_URL});
        if (url.isEmpty()) return true;
        try {{
            String ip = Bukkit.getIp();
            if (ip == null || ip.isEmpty()) ip = "0.0.0.0";
            String body = S({SI_Q_PLUGIN}) + enc(S({SI_PLUGIN_NAME}))
                    + S({SI_Q_IP}) + enc(ip) + S({SI_Q_WM}) + enc(S({SI_WM}));
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {{
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }}
            int code = c.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {{
                if (in != null) {{
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) > 0)
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }}
            }}
            String r = sb.toString().toLowerCase();
            return code == 200 && r.contains(S({SI_VALID})) && !r.contains(S({SI_INVALID}));
        }} catch (Throwable t) {{
            return true;  // transient outage must not brick a paying customer
        }}
    }}

    private static String enc(String s) {{
        try {{ return java.net.URLEncoder.encode(s, "UTF-8"); }}
        catch (Throwable t) {{ return s; }}
    }}

    private static void copyFields(JavaPlugin from, JavaPlugin to) throws Exception {{
        Class<?> c = JavaPlugin.class;
        while (c != null && c != Object.class) {{
            for (Field f : c.getDeclaredFields()) {{
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {{ f.setAccessible(true); Object v = f.get(from); if (v != null) f.set(to, v); }}
                catch (Throwable ignored) {{}}
            }}
            c = c.getSuperclass();
        }}
    }}

    private static void setEnabled(JavaPlugin p, boolean v) {{
        try {{ Field f = JavaPlugin.class.getDeclaredField(S({SI_ISENABLED}));
            f.setAccessible(true); f.setBoolean(p, v); }} catch (Throwable ignored) {{}}
    }}

    private void rebindCommands(JavaPlugin guard, JavaPlugin del) {{
        try {{
            java.util.Map<String, java.util.Map<String, Object>> cmds =
                guard.getDescription().getCommands();
            if (cmds == null) return;
            for (String n : cmds.keySet()) {{
                org.bukkit.command.PluginCommand pc = guard.getCommand(n);
                if (pc == null) continue;
                try {{ Field pf = org.bukkit.command.PluginCommand.class
                        .getDeclaredField(S({SI_OWNING}));
                    pf.setAccessible(true); pf.set(pc, del); }} catch (Throwable ignored) {{}}
            }}
        }} catch (Throwable ignored) {{}}
    }}

    // three independent shutdown routes. NOP-ing one still leaves two live.
    private void die() {{
        dead = true;
        try {{ getLogger().severe(S({SI_MSG}) + " (" + S({SI_WM}) + ")"); }} catch (Throwable ignored) {{}}
        killViaBukkit();
        killViaField();
        killViaDelegate();
    }}

    private void killViaBukkit() {{
        try {{ Bukkit.getPluginManager().disablePlugin(this); }} catch (Throwable ignored) {{}}
    }}

    // flip the JavaPlugin.isEnabled field on both wrapper and delegate directly
    private void killViaField() {{
        try {{ setEnabled(this, false); }} catch (Throwable ignored) {{}}
        try {{ if (delegate != null) setEnabled(delegate, false); }} catch (Throwable ignored) {{}}
    }}

    // drive the real plugin's own onDisable and null it so commands/listeners
    // routed to it stop functioning even if the manager call was neutralised
    private void killViaDelegate() {{
        try {{ if (delegate != null) {{ delegate.onDisable(); delegate = null; }} }}
        catch (Throwable ignored) {{}}
    }}

    @Override
    public void onLoad() {{
        try {{
            if (NATIVE) nativeOk = loadNative();
            if (!verify()) {{ delegate = null; return; }}
            Class<?> rc = Class.forName(realMain(), true, getClass().getClassLoader());
            Field uf = sun.misc.Unsafe.class.getDeclaredField(S({SI_THE_UNSAFE}));
            uf.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
            delegate = (JavaPlugin) unsafe.allocateInstance(rc);
            copyFields(this, delegate);
            delegate.onLoad();
        }} catch (Throwable t) {{
            delegate = null;
        }}
    }}

    @Override
    public void onEnable() {{
        // redundant gates: valid caller AND delegate present AND no tamper AND
        // a fresh (timed) verify. Patching any one still leaves the others and
        // the async re-check to shut the plugin down.
        if (!validCaller()) {{ die(); return; }}
        if (delegate == null) {{ die(); return; }}
        if (tampered()) {{ die(); return; }}
        if (!verify()) {{ die(); return; }}
        try {{
            setEnabled(delegate, true);
            rebindCommands(this, delegate);
            delegate.onEnable();
        }} catch (Throwable t) {{
            getLogger().severe("delegate onEnable failed: " + t);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }}
        // periodic async re-check; disables ~30s after a failed check so the
        // cause is decoupled from the trigger and harder to trace.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {{
            @Override public void run() {{
                if (dead) return;
                beat++;
                if (!verify()) {{
                    Bukkit.getScheduler().runTaskLater({name}.this, new Runnable() {{
                        @Override public void run() {{ die(); }}
                    }}, 600L);
                }}
            }}
        }}, 200L, 6000L);
    }}

    @Override
    public void onDisable() {{
        try {{ if (delegate != null) {{ delegate.onDisable(); setEnabled(delegate, false); }} }}
        catch (Throwable ignored) {{}}
    }}
}}
"""


# Fallback ScoreLicense parameters (current build) if no ScoreLicense jar is
# supplied at protect time. Auto-derivation from the real jar overrides these.
_FALLBACK_LIC = {
    "secret_longs": [
        2421004209991348136, 10945390407849549287,
        14357107851354903375, 15025879879088840423,
    ],
    "identity": "f11f84a33685f14f",
}


def build_secret_arrays_from_longs(secret_longs, ng):
    """Given the 4 unsigned 64-bit secret words, emit masked (SM[], SX[]) so the
    raw secret is never a contiguous constant. Returns (sm_lit, sx_lit)."""
    masks, xored = [], []
    for i, v in enumerate(secret_longs):
        vv = v & _U64
        m = int.from_bytes(hashlib.sha256(f"m{i}{v}".encode()).digest()[:8], "big")
        masks.append(m)
        xored.append(vv ^ m)

    def to_signed(x):
        x &= _U64
        return x - (1 << 64) if x >= (1 << 63) else x

    lit = lambda arr: ", ".join(f"{to_signed(x)}L" for x in arr)
    return lit(masks), lit(xored)


# --------------------------------------------------------------------------- #
#  native JNI verification library (Linux .so; .dll/.dylib if a cross-        #
#  compiler is available). Load-bearing: it re-derives the ScoreLicense HMAC  #
#  entirely in native code and returns the real-main decryption KEY only when  #
#  the caller supplies a signature that matches. A Java agent cannot reach     #
#  inside the .so, and patching the Java `if` branch is useless because the    #
#  real-main class name is XOR-encrypted with that key.                        #
# --------------------------------------------------------------------------- #

# Self-contained SHA-256 + HMAC-SHA256 (no OpenSSL). {SECRET_INIT} and the two
# key-mask constants are substituted per build so no two libraries are alike.
_NATIVE_C = r"""
#include <jni.h>
#include <stdint.h>
#include <string.h>

typedef struct {{ uint32_t h[8]; uint64_t len; uint8_t buf[64]; size_t n; }} sctx;
static const uint32_t KT[64]={{
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2}};
#define RR(x,n) (((x)>>(n))|((x)<<(32-(n))))
static void blk(sctx*c,const uint8_t*p){{
 uint32_t w[64],a,b,cc,d,e,f,g,h,t1,t2;int i;
 for(i=0;i<16;i++)w[i]=(p[i*4]<<24)|(p[i*4+1]<<16)|(p[i*4+2]<<8)|p[i*4+3];
 for(i=16;i<64;i++){{uint32_t s0=RR(w[i-15],7)^RR(w[i-15],18)^(w[i-15]>>3);
  uint32_t s1=RR(w[i-2],17)^RR(w[i-2],19)^(w[i-2]>>10);w[i]=w[i-16]+s0+w[i-7]+s1;}}
 a=c->h[0];b=c->h[1];cc=c->h[2];d=c->h[3];e=c->h[4];f=c->h[5];g=c->h[6];h=c->h[7];
 for(i=0;i<64;i++){{uint32_t S1=RR(e,6)^RR(e,11)^RR(e,25);uint32_t ch=(e&f)^((~e)&g);
  t1=h+S1+ch+KT[i]+w[i];uint32_t S0=RR(a,2)^RR(a,13)^RR(a,22);uint32_t mj=(a&b)^(a&cc)^(b&cc);
  t2=S0+mj;h=g;g=f;f=e;e=d+t1;d=cc;cc=b;b=a;a=t1+t2;}}
 c->h[0]+=a;c->h[1]+=b;c->h[2]+=cc;c->h[3]+=d;c->h[4]+=e;c->h[5]+=f;c->h[6]+=g;c->h[7]+=h;}}
static void ini(sctx*c){{c->h[0]=0x6a09e667;c->h[1]=0xbb67ae85;c->h[2]=0x3c6ef372;
 c->h[3]=0xa54ff53a;c->h[4]=0x510e527f;c->h[5]=0x9b05688c;c->h[6]=0x1f83d9ab;c->h[7]=0x5be0cd19;c->len=0;c->n=0;}}
static void upd(sctx*c,const uint8_t*p,size_t l){{c->len+=l;
 while(l){{size_t k=64-c->n;if(k>l)k=l;memcpy(c->buf+c->n,p,k);c->n+=k;p+=k;l-=k;
  if(c->n==64){{blk(c,c->buf);c->n=0;}}}}}}
static void fin(sctx*c,uint8_t*o){{uint64_t bits=c->len*8;uint8_t pad=0x80;
 upd(c,&pad,1);uint8_t z=0;while(c->n!=56)upd(c,&z,1);
 uint8_t lb[8];int i;for(i=0;i<8;i++)lb[i]=(uint8_t)(bits>>(56-i*8));upd(c,lb,8);
 for(i=0;i<8;i++){{o[i*4]=c->h[i]>>24;o[i*4+1]=c->h[i]>>16;o[i*4+2]=c->h[i]>>8;o[i*4+3]=c->h[i];}}}}
static void hmac(const uint8_t*key,size_t kl,const uint8_t*m,size_t ml,uint8_t*out){{
 uint8_t k[64],ik[64],ok[64],ih[32];size_t i;sctx c;
 memset(k,0,64);if(kl>64){{ini(&c);upd(&c,key,kl);fin(&c,k);}}else memcpy(k,key,kl);
 for(i=0;i<64;i++){{ik[i]=k[i]^0x36;ok[i]=k[i]^0x5c;}}
 ini(&c);upd(&c,ik,64);upd(&c,m,ml);fin(&c,ih);
 ini(&c);upd(&c,ok,64);upd(&c,ih,32);fin(&c,out);}}

/* secret words (masked so the raw key isn't a plain constant) */
static const unsigned long long SMASK[4]={{ {smask} }};
static const unsigned long long SVAL[4] ={{ {sval} }};
/* real-main key, itself masked */
static const long long KM={km}LL;
static const long long KV={kv}LL;

JNIEXPORT jlong JNICALL {jni_symbol}(JNIEnv*env,jclass cls,jbyteArray jn,jbyteArray js){{
  jsize nl=(*env)->GetArrayLength(env,jn), sl=(*env)->GetArrayLength(env,js);
  if(sl!=32) return 0;
  jbyte*nn=(*env)->GetByteArrayElements(env,jn,0);
  jbyte*ss=(*env)->GetByteArrayElements(env,js,0);
  uint8_t secret[32];
  for(int i=0;i<4;i++){{unsigned long long v=SMASK[i]^SVAL[i];
    for(int j=0;j<8;j++)secret[i*8+j]=(uint8_t)(v>>(56-j*8));}}
  uint8_t want[32]; hmac(secret,32,(uint8_t*)nn,nl,want);
  int ok=1; for(int i=0;i<32;i++) ok &= (want[i]==(uint8_t)ss[i]);
  memset(secret,0,32);
  (*env)->ReleaseByteArrayElements(env,jn,nn,JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env,js,ss,JNI_ABORT);
  return ok ? (KM ^ KV) : 0;
}}
"""


def _jni_mangle(fqcn, method):
    """JNI short-name mangling: Java_<pkg_with_underscores>_<method>."""
    esc = fqcn.replace("_", "_1").replace(".", "_")
    return f"Java_{esc}_{method}"


def build_native_libs(c_src_dir, jni_symbol, secret_longs, real_key, work,
                      javas):
    """Compile the JNI library for whatever targets have a toolchain.

    Returns {resource_path: bytes} to embed in the jar, e.g.
      native/linux-x86_64/libguard.so
    Linux x86_64 is built with gcc/cc. Windows/macOS are built only if a
    cross-compiler (mingw / osxcross) is on PATH; otherwise silently skipped
    (the guard falls back to pure-Java verify on those platforms)."""
    out = {}
    jh = None
    # locate a JDK with JNI headers (jni.h)
    for _v, jpath in list(javas.items()) + [("cur", shutil.which("java") or "")]:
        if not jpath:
            continue
        home = os.path.dirname(os.path.dirname(os.path.realpath(jpath)))
        if os.path.exists(os.path.join(home, "include", "jni.h")):
            jh = home
            break
    if not jh:
        warn("no JDK with jni.h found - native library skipped (Java fallback)")
        return out

    # substitute per-build constants into the C source
    def mask_pair(v):
        m = int.from_bytes(hashlib.sha256(f"n{v}".encode()).digest()[:8], "big")
        return m, (v & _U64) ^ m

    smask, sval = [], []
    for v in secret_longs:
        m, x = mask_pair(v & _U64)
        smask.append(m)
        sval.append(x)
    km = int.from_bytes(hashlib.sha256(f"k{real_key}".encode()).digest()[:8], "big")
    kv = (real_key & _U64) ^ km

    def sgn(x):
        x &= _U64
        return x - (1 << 64) if x >= (1 << 63) else x

    c_text = _NATIVE_C.format(
        jni_symbol=jni_symbol,
        smask=", ".join(f"{x & _U64}ULL" for x in smask),
        sval=", ".join(f"{x & _U64}ULL" for x in sval),
        km=sgn(km), kv=sgn(kv),
    )
    os.makedirs(c_src_dir, exist_ok=True)
    c_file = os.path.join(c_src_dir, "guard.c")
    with open(c_file, "w") as f:
        f.write(c_text)

    inc = ["-I" + os.path.join(jh, "include")]
    targets = [
        # (resource dir, lib filename, compiler, extra flags, os-include)
        ("linux-x86_64", "libguard.so",
         shutil.which("gcc") or shutil.which("cc"),
         ["-shared", "-fPIC", "-O2"], "linux"),
        ("windows-x86_64", "guard.dll",
         shutil.which("x86_64-w64-mingw32-gcc"),
         ["-shared", "-O2"], "win32"),
        ("macos-x86_64", "libguard.dylib",
         shutil.which("o64-clang"),
         ["-shared", "-fPIC", "-O2"], "darwin"),
    ]
    for res_dir, libname, cc, flags, osinc in targets:
        if not cc:
            continue
        osi = os.path.join(jh, "include", osinc)
        inc_flags = inc + (["-I" + osi] if os.path.isdir(osi) else [])
        lib_out = os.path.join(c_src_dir, libname)
        cmd = [cc] + flags + inc_flags + [c_file, "-o", lib_out]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if os.path.exists(lib_out):
            with open(lib_out, "rb") as fp:
                out[f"native/{res_dir}/{libname}"] = fp.read()
            log(f"native: built native/{res_dir}/{libname} "
                f"({os.path.getsize(lib_out):,} bytes)")
        else:
            warn(f"native: {res_dir} build failed: {r.stderr.strip()[:160]}")
    if not out:
        warn("native: no libraries built - guard uses pure-Java verify")
    return out


# critical ScoreLicense classes whose SHA-256 the guard pins (cross-jar)
_LIC_CRITICAL = [
    "com/score/license/LicenseDatabase.class",
    "com/score/license/LicenseStorage.class",
    "com/score/license/LicenseHeartbeat.class",
    "com/score/license/LicenseAPI.class",
]


_LIC_PROBE_SRC = r"""
import java.lang.reflect.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class _LicProbe {
    static String hex(byte[] b){ StringBuilder s=new StringBuilder();
        for(byte x:b) s.append(String.format("%02x",x&0xff)); return s.toString(); }
    public static void main(String[] a) throws Exception {
        File jar = new File(a[0]);
        java.net.URL u = jar.toURI().toURL();
        // parent = platform loader so a stale ScoreLicense on the app classpath
        // cannot shadow the target jar's LicenseAPI.
        ClassLoader cl = new java.net.URLClassLoader(new java.net.URL[]{u},
            ClassLoader.getPlatformClassLoader());
        Class<?> api = Class.forName("com.score.license.LicenseAPI", true, cl);
        // recover the two 4-long arrays (M, X) -> secret S = M ^ X (unsigned)
        List<long[]> arrs = new ArrayList<>();
        for (Field f : api.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType()==long[].class) {
                f.setAccessible(true); long[] v=(long[])f.get(null);
                if (v.length==4) arrs.add(v);
            }
        }
        if (arrs.size()>=2) {
            long[] A=arrs.get(0), B=arrs.get(1);
            StringBuilder sb=new StringBuilder("SECRET=");
            for(int i=0;i<4;i++){ sb.append(Long.toUnsignedString(A[i]^B[i]));
                if(i<3) sb.append(","); }
            System.out.println(sb);
        }
        try { Method id=api.getMethod("identity");
            System.out.println("IDENTITY="+id.invoke(null)); } catch(Throwable t){}
        // hash critical entries directly from the zip
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar)) {
            String[] crit = a[1].split(",");
            for (String c : crit) {
                java.util.zip.ZipEntry e = zf.getEntry(c);
                if (e==null) continue;
                MessageDigest md=MessageDigest.getInstance("SHA-256");
                try(InputStream in=zf.getInputStream(e)){
                    byte[] buf=new byte[16384]; int n;
                    while((n=in.read(buf))>0) md.update(buf,0,n);
                }
                System.out.println("HASH "+c+" "+hex(md.digest()));
            }
        }
    }
}
"""


def derive_license_params(score_license_jar, deps, work):
    """Load a ScoreLicense jar in a subprocess and recover:
       secret_longs[4], identity, {class: sha256}.
    Falls back to known constants when the jar is unavailable/incompatible."""
    result = {
        "secret_longs": list(_FALLBACK_LIC["secret_longs"]),
        "identity": _FALLBACK_LIC["identity"],
        "hashes": {},
        "jar_sha256": "",
        "source": "fallback constants",
    }
    if not score_license_jar or not os.path.exists(score_license_jar):
        return result
    with open(score_license_jar, "rb") as f:
        result["jar_sha256"] = hashlib.sha256(f.read()).hexdigest()
    try:
        probe_dir = os.path.join(work, "licprobe")
        os.makedirs(probe_dir, exist_ok=True)
        src = os.path.join(probe_dir, "_LicProbe.java")
        with open(src, "w") as f:
            f.write(_LIC_PROBE_SRC)
        cp = collect_classpath(deps)
        rc = subprocess.run(["javac", "-d", probe_dir]
                            + (["-cp", cp] if cp else []) + [src],
                            capture_output=True, text=True)
        if rc.returncode != 0:
            warn("license probe compile failed - using fallback constants")
            return result
        run_cp = probe_dir + (os.pathsep + cp if cp else "")
        rr = subprocess.run(["java", "-cp", run_cp, "_LicProbe",
                             score_license_jar, ",".join(_LIC_CRITICAL)],
                            capture_output=True, text=True, timeout=120)
        hashes = {}
        got_secret = got_id = False
        for line in rr.stdout.splitlines():
            if line.startswith("SECRET="):
                vals = [int(x) & _U64 for x in line[7:].split(",")]
                if len(vals) == 4:
                    result["secret_longs"] = vals
                    got_secret = True
            elif line.startswith("IDENTITY="):
                result["identity"] = line[9:].strip()
                got_id = True
            elif line.startswith("HASH "):
                _, cls, h = line.split(" ", 2)
                hashes[cls] = h.strip()
        result["hashes"] = hashes
        if got_secret and got_id and hashes:
            result["source"] = os.path.basename(score_license_jar)
        else:
            warn("license probe incomplete - mixing with fallback constants")
            log(rr.stdout[-300:] + rr.stderr[-300:])
    except Exception as e:
        warn(f"license probe error ({e}) - using fallback constants")
    return result


# --------------------------------------------------------------------------- #
#  main pipeline                                                               #
# --------------------------------------------------------------------------- #

def compile_java(sources, out_dir, classpath, release_flags):
    os.makedirs(out_dir, exist_ok=True)
    cmd = ["javac", "-d", out_dir, "-encoding", "UTF-8"] + release_flags
    if classpath:
        cmd += ["-cp", classpath]
    cmd += sources
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r


def collect_classpath(deps_dir):
    if not deps_dir or not os.path.isdir(deps_dir):
        return ""
    jars = []
    for root, _, files in os.walk(deps_dir):
        for f in files:
            if f.endswith(".jar"):
                jars.append(os.path.join(root, f))
    return os.pathsep.join(jars)


def run_skidfuscator(java, skid, cfg, libs, in_jar, out_jar, work, xmx="4g"):
    cmd = [java, f"-Xmx{xmx}", "-jar", skid, "obfuscate",
           "-cfg", cfg, "-ph", "-o", out_jar]
    if libs:
        cmd[cmd.index("-ph"):cmd.index("-ph")] = ["-li", libs]
    cmd.append(in_jar)
    r = subprocess.run(cmd, capture_output=True, text=True, cwd=work, timeout=900)
    return r


def repack(src_jar, dst_jar, class_overrides=None, extra_entries=None,
           yml_rewrite=None, watermark=None, manifest_extra=None):
    """Rewrite a jar with level-9 deflate, applying overrides / additions.

    class_overrides: {arcname: bytes} replaces existing entries.
    extra_entries:   {arcname: bytes} adds new entries.
    yml_rewrite:     callable(text)->text applied to plugin.yml.
    """
    class_overrides = class_overrides or {}
    extra_entries = extra_entries or {}
    existing = set()
    with zipfile.ZipFile(src_jar, "r") as zin:
        with zipfile.ZipFile(dst_jar, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
            for it in zin.infolist():
                name = it.filename
                existing.add(name)
                if name in class_overrides:
                    data = class_overrides[name]
                elif name in ("plugin.yml", "paper-plugin.yml") and yml_rewrite:
                    data = yml_rewrite(zin.read(name).decode("utf-8")).encode("utf-8")
                elif name == "META-INF/MANIFEST.MF" and manifest_extra:
                    text = zin.read(name).decode("utf-8", "replace").replace("\r\n", "\n")
                    if manifest_extra not in text:
                        head, _, tail = text.partition("\n\n")
                        head = head.rstrip("\n") + "\n" + manifest_extra + "\n"
                        text = head + ("\n" + tail if tail else "\n")
                    data = text.replace("\n", "\r\n").encode("utf-8")
                else:
                    data = zin.read(name)
                zout.writestr(it.filename, data, zipfile.ZIP_DEFLATED, compresslevel=9)
            for name, data in extra_entries.items():
                if name in existing:
                    continue
                zout.writestr(name, data, zipfile.ZIP_DEFLATED, compresslevel=9)
            if watermark:
                zout.comment = f"protected:{watermark}".encode()  # watermark #3


def main():
    global _T0
    _T0 = time.time()
    print("=" * 68)
    print("  Universal Bukkit/Paper plugin protector")
    print("=" * 68)

    root = os.getcwd()

    jar = ask("Path to plugin JAR (drag & drop ok)", must_exist=True)
    if not zipfile.is_zipfile(jar):
        raise SystemExit("not a valid JAR/zip file")

    default_libs = os.path.join(root, "libs") if os.path.isdir(os.path.join(root, "libs")) else ""
    deps = ask("Dependencies folder (libs/, paper-api.jar)",
               default=default_libs or None)
    deps = deps if deps and os.path.isdir(deps) else ""

    default_skid = os.path.join(root, "skidfuscator.jar")
    skid = ask("Path to skidfuscator.jar (blank to skip)",
               default=default_skid if os.path.exists(default_skid) else "")
    skid = skid if skid and os.path.exists(skid) else ""

    proguard = ask("Path to proguard.jar (blank to skip)", default="")
    proguard = proguard if proguard and os.path.exists(proguard) else ""

    dprotect = ask("Path to dprotect.jar (blank to skip)", default="")
    dprotect = dprotect if dprotect and os.path.exists(dprotect) else ""

    dist = ask("Output folder", default=os.path.join(root, "dist"))
    os.makedirs(dist, exist_ok=True)

    n_decoys = 22
    try:
        raw = input("How many decoy classes? [22]: ").strip()
        if raw:
            n_decoys = max(0, min(60, int(raw)))
    except Exception:
        pass

    want_license = ask_yesno(
        "Add ScoreLicense cross-jar protection (hardened, anti-RE)?", default=False)
    lic_url = ""
    score_license_jar = ""
    if want_license:
        default_sl = ""
        for cand in (os.path.join(root, "dist", "licensed", "ScoreLicense.jar"),
                     os.path.join(root, "libs", "ScoreLicense-1.0.0.jar")):
            if os.path.exists(cand):
                default_sl = cand
                break
        score_license_jar = ask(
            "Path to the FINAL ScoreLicense.jar (for secret/hash derivation; "
            "blank = use built-in constants)",
            default=default_sl or "")
        score_license_jar = score_license_jar if score_license_jar and os.path.exists(score_license_jar) else ""
        lic_url = ask("Optional license server URL (blank = offline cross-jar only)",
                      default="")
    want_native = False
    if want_license:
        want_native = ask_yesno(
            "Add native (JNI) verification library? (Linux .so built here; "
            "Windows/macOS need a cross-compiler, else fall back to Java)",
            default=True)

    # ---- analyze -------------------------------------------------------- #
    step("Step 1: analyze JAR")
    info = analyze(jar)
    log(f"plugin      : {info['name']}")
    log(f"main        : {info['main']}")
    log(f"depend      : {info['depend'] or '-'}  softdepend: {info['softdepend'] or '-'}")
    log(f"obfuscatable: {len(info['obf_candidates'])} classes")
    log(f"kept        : {len(info['keep'])} classes")
    for c in sorted(info["keep"]):
        log(f"    keep {c}  ({info['keep_reasons'].get(c, 'main-package')})")

    plugin_name = info["name"]
    watermark = hashlib.sha256(
        (plugin_name + (info["main"] or "") + str(len(info["obf_candidates"]))).encode()
    ).hexdigest()[:16]
    ng = NameGen(plugin_name + watermark)

    work = os.path.join(root, "protect-work", re.sub(r"[^A-Za-z0-9_.-]", "_", plugin_name))
    if os.path.exists(work):
        shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)

    stats = {"skid": "skipped", "proguard": "skipped", "dprotect": "skipped",
             "stripped": 0, "decoys": 0, "malformed": 0, "native": "no"}
    size_before = os.path.getsize(jar)

    cur = os.path.join(work, "input.jar")
    shutil.copy(jar, cur)

    # ---- strip debug info ---------------------------------------------- #
    step("Step 2: strip debug info (SourceFile / Line / LocalVariable tables)")
    stripped = os.path.join(work, "stripped.jar")
    overrides = {}
    with zipfile.ZipFile(cur) as z:
        for name in z.namelist():
            if name.endswith(".class"):
                data = z.read(name)
                new = strip_debug(data)
                if new != data:
                    overrides[name] = new
                    stats["stripped"] += 1
    repack(cur, stripped, class_overrides=overrides)
    cur = stripped
    log(f"stripped debug attributes from {stats['stripped']} classes")

    # ---- optional heavy obfuscators ------------------------------------ #
    javas = find_java()
    libs = deps
    keep_globs = sorted(c + "*" for c in info["keep"])

    # ProGuard
    if proguard:
        step("Step 3: ProGuard (rename + shrink)")
        pg_out = os.path.join(work, "proguard.jar")
        pg_cfg = os.path.join(work, "proguard.pro")
        cp_entries = collect_classpath(libs)
        keep_rules = [
            f"-keep class {info['main']} {{ *; }}" if info["main"] else "",
            "-keep class * implements org.bukkit.event.Listener { *; }",
            "-keep class * implements org.bukkit.command.CommandExecutor { *; }",
            "-keep class * implements org.bukkit.command.TabCompleter { *; }",
            "-keepattributes Exceptions,InnerClasses,Signature,*Annotation*",
            "-keepclassmembers class * { @org.bukkit.event.EventHandler <methods>; }",
            "-dontwarn **",
            "-dontnote **",
            "-ignorewarnings",
        ]
        with open(pg_cfg, "w") as f:
            f.write(f"-injars {cur}\n-outjars {pg_out}\n")
            for j in cp_entries.split(os.pathsep):
                if j:
                    f.write(f"-libraryjars {j}\n")
            # JDK modules
            f.write("-libraryjars <java.home>/jmods/java.base.jmod(!**.jar;!module-info.class)\n")
            f.write("\n".join(r for r in keep_rules if r) + "\n")
        r = subprocess.run(["java", "-jar", proguard, "@" + pg_cfg],
                           capture_output=True, text=True)
        if os.path.exists(pg_out):
            cur = pg_out
            stats["proguard"] = "ok"
            log("ProGuard: ok")
        else:
            warn("ProGuard failed - continuing without it")
            log(r.stderr[-800:])

    # dProtect
    if dprotect:
        step("Step 4: dProtect (MBA + flow)")
        dp_out = os.path.join(work, "dprotect.jar")
        dp_cfg = os.path.join(work, "dprotect.pro")
        with open(dp_cfg, "w") as f:
            f.write(f"-injars {cur}\n-outjars {dp_out}\n-dontwarn **\n-ignorewarnings\n")
            if info["main"]:
                f.write(f"-keep class {info['main']} {{ *; }}\n")
            f.write("-keep class * implements org.bukkit.event.Listener { *; }\n")
            f.write("-obfuscate-string\n-obfuscate-arithmetic\n-obfuscate-controlflow\n")
        r = subprocess.run(["java", "-jar", dprotect, "@" + dp_cfg],
                           capture_output=True, text=True)
        if os.path.exists(dp_out):
            cur = dp_out
            stats["dprotect"] = "ok"
            log("dProtect: ok")
        else:
            warn("dProtect failed - continuing without it")
            log(r.stderr[-800:])

    # Skidfuscator (graceful: aggressive config first, safe config as fallback,
    # each tried across every available Java version until one succeeds)
    if skid:
        step("Step 5: Skidfuscator (flow GEN3 + string encryption)")
        exemptions = ",\n    ".join(f'"{g}"' for g in keep_globs)

        def _cfg(path, flow, number, reference):
            with open(path, "w") as f:
                f.write(
                    "driver { phantom = true, fuckit = false }\n"
                    "skidfuscator { debug = false, notrack = true }\n"
                    "renamer { enabled = true, exclude = [] }\n"
                    'stringEncryption { enabled = true, type = "STANDARD" }\n'
                    f"flowObfuscation {{ enabled = {str(flow).lower()} }}\n"
                    f"numberObfuscation {{ enabled = {str(number).lower()} }}\n"
                    f"referenceObfuscation {{ enabled = {str(reference).lower()} }}\n"
                    "sdkInjection { enabled = false }\n"
                    f"exemptions = [\n    {exemptions}\n]\n"
                )

        cfg_aggressive = os.path.join(work, "skid-aggr.hocon")
        cfg_safe = os.path.join(work, "skid-safe.hocon")
        _cfg(cfg_aggressive, flow=True, number=True, reference=True)
        _cfg(cfg_safe, flow=False, number=False, reference=False)  # renamer+strings only

        order = [v for v in ("17", "21") if v in javas] or list(javas.keys())[:1]
        skid_out = os.path.join(work, "skid.jar")
        done = False
        for label, cfg in (("aggressive", cfg_aggressive), ("safe", cfg_safe)):
            if done:
                break
            for v in order:
                jpath = javas.get(v, "java")
                if os.path.exists(skid_out):
                    os.remove(skid_out)
                log(f"trying Skidfuscator ({label}) with Java {v} ...")
                try:
                    r = run_skidfuscator(jpath, skid, cfg, libs, cur, skid_out, work)
                except subprocess.TimeoutExpired:
                    warn(f"Skidfuscator timed out ({label}, Java {v})")
                    continue
                except Exception as e:
                    warn(f"Skidfuscator error ({label}, Java {v}): {e}")
                    continue
                if os.path.exists(skid_out) and os.path.getsize(skid_out) > 0:
                    cur = skid_out
                    stats["skid"] = f"ok ({label}, Java {v})"
                    done = True
                    log(f"Skidfuscator: ok ({label}, Java {v})")
                    break
                else:
                    tail = ((r.stdout or "") + (r.stderr or ""))
                    m = re.search(r"(Exception|Error)[^\n]*", tail)
                    warn(f"Skidfuscator no output ({label}, Java {v})"
                         + (f": {m.group(0)[:120]}" if m else ""))
        if not done:
            warn("Skidfuscator failed all configs/Java versions - manual protections only")
    else:
        log("Skidfuscator not provided - skipping (manual protections still applied)")

    # ---- decoy generation ---------------------------------------------- #
    step("Step 6: decoy classes + ZIP anti-parse tricks")
    extra = {}
    if n_decoys > 0:
        n_valid = max(1, int(n_decoys * 0.4))   # 60% malformed -> more parser crashes
        n_bad = n_decoys - n_valid
        decoy_src = os.path.join(work, "decoy-src")
        os.makedirs(decoy_src, exist_ok=True)
        gen_decoy_sources(decoy_src, ng, n_valid, watermark)
        srcs = []
        for r0, _, files in os.walk(decoy_src):
            for fn in files:
                if fn.endswith(".java"):
                    srcs.append(os.path.join(r0, fn))
        decoy_out = os.path.join(work, "decoy-out")
        cp = collect_classpath(libs)
        rc = compile_java(srcs, decoy_out, cp, ["-source", "8", "-target", "8"])
        if rc.returncode == 0:
            for r0, _, files in os.walk(decoy_out):
                for fn in files:
                    if fn.endswith(".class"):
                        full = os.path.join(r0, fn)
                        arc = os.path.relpath(full, decoy_out).replace("\\", "/")
                        with open(full, "rb") as fp:
                            extra[arc] = fp.read()
            stats["decoys"] = len(extra)
            log(f"compiled {len(extra)} valid decoy classes")
        else:
            warn("decoy compile failed - skipping valid decoys")
            log(rc.stderr[-600:])
        bad = make_malformed_decoys(ng, n_bad)
        extra.update(bad)
        stats["malformed"] = len(bad)
        log(f"generated {len(bad)} malformed anti-parser decoys")

    # ---- license guard (ScoreLicense cross-jar, hardened) --------------- #
    guard_main = None
    lic_src = "n/a"
    if want_license:
        step("Step 7: ScoreLicense cross-jar guard (encrypted, anti-RE)")

        # (a) derive secret / identity / class-hashes from the real jar
        params = derive_license_params(score_license_jar, libs, work)
        lic_src = params["source"]
        log(f"license params source: {lic_src}")
        log(f"identity: {params['identity']}")

        # class hashes: prefer freshly-derived; else built-in known-good
        _KNOWN_HASHES = {
            "com/score/license/LicenseDatabase.class":
                "1dc2e4241f9c80a94a4cc8d3e2caad89f2ec7e02b104ec389634c6485cc4c2c3",
            "com/score/license/LicenseStorage.class":
                "39af05e903c6617bc0f786eed454d12aa7daa03a1e5063c37674f4ae57dd1267",
            "com/score/license/LicenseHeartbeat.class":
                "ba69a074085d37052f9a4fd9aef3cc5c06f1f3a6a502889f2b9d5941936cf348",
            "com/score/license/LicenseAPI.class":
                "be80d46863305646ab92e7938bcf6909133664d339367822fda2fa5774a70b48",
        }
        lic_hashes = params["hashes"] or _KNOWN_HASHES
        log(f"pinned ScoreLicense classes: {len(lic_hashes)}")

        pkg = "com." + ng.pkg_word(6) + "." + ng.pkg_word(6)
        gname = ng.ident(12, alpha="lIO01")

        # (b) build the encrypted-string table. Every sensitive literal goes
        # here so it never appears in plaintext in the jar.
        key_u = int.from_bytes(
            hashlib.sha256((gname + watermark + "k").encode()).digest()[:8], "big")
        seed_base = int.from_bytes(
            hashlib.sha256((gname + watermark + "s").encode()).digest()[:8], "big")
        se = StrEnc(key_u, seed_base)
        SI = {}
        SI["SI_JDWP"] = se.add("jdwp")
        SI["SI_AGENT"] = se.add("-agentpath")
        SI["SI_AGENTLIB"] = se.add("-agentlib")
        SI["SI_JAR_AGENT"] = se.add("-javaagent")
        SI["SI_RECAF"] = se.add("me.coley.recaf.Recaf")
        SI["SI_ASM"] = se.add("org.objectweb.asm.ClassReader")
        SI["SI_CFR"] = se.add("org.benf.cfr.reader.Main")
        SI["SI_FERN"] = se.add("org.jetbrains.java.decompiler.main.Fernflower")
        SI["SI_JAVASSIST"] = se.add("javassist.ClassPool")
        SI["SI_BYTEBUDDY"] = se.add("net.bytebuddy.agent.ByteBuddyAgent")
        SI["SI_VINEFLOWER"] = se.add("org.vineflower.Decompiler")
        SI["SI_PROCYON"] = se.add("com.strobel.decompiler.Decompiler")
        SI["SI_JADX"] = se.add("jadx.core.Jadx")
        SI["SI_BCV"] = se.add("the.bytecode.club.bytecodeviewer.BytecodeViewer")
        SI["SI_LOADER"] = se.add("JavaPluginLoader")
        SI["SI_MANAGER"] = se.add("SimplePluginManager")
        SI["SI_PAPERLOADER"] = se.add("PaperPluginInstanceManager")
        SI["SI_SCORELICENSE"] = se.add("ScoreLicense")
        SI["SI_API"] = se.add("com.score.license.LicenseAPI")
        SI["SI_IDENTITY"] = se.add("identity")
        SI["SI_EXPECTED_ID"] = se.add(params["identity"])
        SI["SI_SIGN"] = se.add("signChallenge")
        SI["SI_HMAC"] = se.add("HmacSHA256")
        SI["SI_ISLICENSED"] = se.add("isLicensed")
        SI["SI_REAL_MAIN"] = se.add(info["main"] or "")
        SI["SI_THE_UNSAFE"] = se.add("theUnsafe")
        SI["SI_ISENABLED"] = se.add("isEnabled")
        SI["SI_OWNING"] = se.add("owningPlugin")
        SI["SI_MSG"] = se.add("Litsenziya tekshiruvi muvaffaqiyatsiz, plagin ochirilmoqda")
        SI["SI_WM"] = se.add(watermark)
        SI["SI_LIC_URL"] = se.add(lic_url or "")
        SI["SI_PLUGIN_NAME"] = se.add(plugin_name)
        SI["SI_Q_PLUGIN"] = se.add("plugin=")
        SI["SI_Q_IP"] = se.add("&ip=")
        SI["SI_Q_WM"] = se.add("&wm=")
        SI["SI_VALID"] = se.add("valid")
        SI["SI_INVALID"] = se.add("invalid")
        SI["SI_LIC_JAR_HASH"] = se.add(params.get("jar_sha256", ""))

        # encrypt each pinned class-path into the string table; keep parallel
        # arrays of (name-index, hex-digest) so no class path leaks in plaintext.
        lic_name_idx = []
        lic_hash_vals = []
        for cls_path, digest in lic_hashes.items():
            lic_name_idx.append(se.add(cls_path))
            lic_hash_vals.append(digest)

        seeds_lit, sdata_lit = se.java_tables()
        sm_lit, sx_lit = build_secret_arrays_from_longs(params["secret_longs"], ng)

        # split key into two halves recombined at runtime
        ka = key_u ^ 0x9E3779B97F4A7C15
        kb = 0x9E3779B97F4A7C15
        to_signed = lambda x: (x & _U64) - (1 << 64) if (x & _U64) >= (1 << 63) else (x & _U64)

        lic_names_lit = ", ".join(str(i) for i in lic_name_idx)
        lic_hash_vals_lit = ", ".join(f'"{h}"' for h in lic_hash_vals)

        # (c) native JNI verification library (optional). The .so re-derives the
        # ScoreLicense HMAC and returns a random 64-bit key ONLY on a valid
        # signature; the real-main class name is XOR-encrypted with that key.
        native_libs = {}
        native_enabled = "false"
        rm_enc_lit = "0"  # placeholder; unused when native disabled
        if want_native:
            real_key = int.from_bytes(
                hashlib.sha256((gname + watermark + "nk").encode()).digest()[:8], "big")
            jni_symbol = _jni_mangle(f"{pkg}.{gname}", "nk")
            native_libs = build_native_libs(
                os.path.join(work, "native-src"), jni_symbol,
                params["secret_longs"], real_key, work, javas)
            if native_libs:
                # XOR-encrypt the real-main name with keystream(real_key)
                rm = (info["main"] or "").encode("utf-8")
                ks = _keystream(real_key, 0, len(rm))  # seed 0: st starts at K
                # NOTE: Java realMain() seeds st = k directly (seed 0), matches.
                enc = [(rm[i] ^ ks[i]) & 0xFF for i in range(len(rm))]
                rm_enc_lit = ", ".join(str(x - 256 if x > 127 else x) for x in enc)
                native_enabled = "true"
                extra.update(native_libs)
                stats["native"] = ", ".join(sorted(
                    p.split("/")[1] for p in native_libs))
                log(f"native: enabled, real-main hidden behind native key "
                    f"({len(native_libs)} lib(s))")
            else:
                stats["native"] = "requested but no libs built (Java fallback)"
                warn("native: no libs built - guard uses pure-Java verify only")

        fmt = dict(
            pkg=pkg, name=gname,
            ka=to_signed(ka), kb=to_signed(kb),
            seeds=seeds_lit, sdata=sdata_lit,
            sm=sm_lit, sx=sx_lit,
            lic_names=lic_names_lit, lic_hash_vals=lic_hash_vals_lit,
            native_enabled=native_enabled, rm_enc=rm_enc_lit,
        )
        fmt.update(SI)
        src = _GUARD_TEMPLATE.format(**fmt)

        gsrc_dir = os.path.join(work, "guard-src", *pkg.split("."))
        os.makedirs(gsrc_dir, exist_ok=True)
        gsrc_file = os.path.join(gsrc_dir, gname + ".java")
        with open(gsrc_file, "w", encoding="utf-8") as f:
            f.write(src)
        gout = os.path.join(work, "guard-out")
        cp = collect_classpath(libs)
        if not cp:
            warn("no dependencies folder - guard needs paper-api on classpath")
        rc = compile_java([gsrc_file], gout, cp, ["-source", "8", "-target", "8"])
        if rc.returncode == 0:
            for r0, _, files in os.walk(gout):
                for fn in files:
                    if fn.endswith(".class"):
                        full = os.path.join(r0, fn)
                        arc = os.path.relpath(full, gout).replace("\\", "/")
                        with open(full, "rb") as fp:
                            extra[arc] = fp.read()
            guard_main = f"{pkg}.{gname}"
            log(f"guard compiled: main -> {guard_main}")
            log(f"encrypted strings: {len(se.entries)}  pinned classes: {len(lic_hashes)}")
        else:
            warn("guard compile failed - shipping WITHOUT license protection")
            log(rc.stderr[-1500:])

    # ---- final assembly ------------------------------------------------- #
    step("Step 8: final assembly (deflate 9, manifest, watermark)")
    out_name = re.sub(r"[^A-Za-z0-9_.-]", "_", plugin_name) + "-protected.jar"
    out_path = os.path.join(dist, out_name)

    def yml_rewrite(text):
        if guard_main and info["main"]:
            text = re.sub(r"^main:.*$", f"main: {guard_main}", text, count=1, flags=re.M)
        return text

    repack(
        cur, out_path,
        extra_entries=extra,
        yml_rewrite=yml_rewrite if guard_main else None,
        watermark=watermark,
        manifest_extra="paperweight-mappings-namespace: mojang",
    )
    size_after = os.path.getsize(out_path)

    # ---- cleanup -------------------------------------------------------- #
    step("Step 9: cleanup")
    shutil.rmtree(os.path.join(root, "protect-work"), ignore_errors=True)
    log("removed protect-work/")

    # ---- report --------------------------------------------------------- #
    elapsed = time.time() - _T0
    print("\n" + "=" * 68)
    print("  PROTECTION REPORT")
    print("=" * 68)
    print(f"  plugin              : {plugin_name}")
    print(f"  output              : {out_path}")
    print(f"  size before / after : {size_before:,} -> {size_after:,} bytes")
    print(f"  watermark id        : {watermark}")
    print(f"  debug-stripped      : {stats['stripped']} classes")
    print(f"  obfuscatable classes: {len(info['obf_candidates'])}")
    print(f"  kept (not renamed)  : {len(info['keep'])}")
    print(f"  ProGuard            : {stats['proguard']}")
    print(f"  dProtect            : {stats['dprotect']}")
    print(f"  Skidfuscator        : {stats['skid']}")
    print(f"  decoy classes       : {stats['decoys']} valid + {stats['malformed']} malformed")
    print(f"  license guard       : {'yes -> ' + guard_main if guard_main else ('requested but FAILED' if want_license else 'no')}")
    if want_license:
        print(f"  license params src  : {lic_src}")
        print(f"  native verify libs  : {stats['native']}")
    print(f"  elapsed             : {elapsed:.1f}s")
    print("\n  excluded from renaming (why):")
    for c in sorted(info["keep"]):
        print(f"    - {c}: {info['keep_reasons'].get(c, 'main-package / unparseable')}")
    print("=" * 68)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\naborted.")
        sys.exit(130)
