import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Sign an Actions-built unsigned APK locally; never upload the release private key. */
class SignRelease {
    static String value(Properties p, String env, String key) {
        return System.getenv().getOrDefault(env, p.getProperty(key, ""));
    }
    static String hash(InputStream input) throws Exception {
        try (input) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    static Map<String, String> payload(Path apk) throws Exception {
        var result = new TreeMap<String, String>();
        try (var zip = new ZipFile(apk.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().matches("(?i)META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))")) continue;
                if (result.put(entry.getName(), hash(zip.getInputStream(entry))) != null)
                    throw new IllegalStateException("Duplicate APK payload entry");
            }
        }
        return result;
    }
    static byte[] run(List<String> command, Map<String, String> secrets) throws Exception {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(secrets);
        var process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            String detail = new String(output, java.nio.charset.StandardCharsets.ISO_8859_1);
            String reason = detail.contains("insufficient memory") || detail.contains("OutOfMemory")
                ? "memory allocation failed" : "signer rejected the input or configuration";
            throw new IllegalStateException("APK signing tool failed: " + reason + "; no credentials were logged");
        }
        return output;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: java tools/SignRelease.java <build-tools-dir> <unsigned.apk> <signed.apk> <private.properties>");
        Path unsigned = Path.of(args[1]), signed = Path.of(args[2]);
        if (Files.exists(signed)) throw new IllegalStateException("Output already exists; choose a new output path");
        Properties config = new Properties();
        try (var stream = Files.newInputStream(Path.of(args[3]))) { config.load(stream); }
        String path = value(config, "RELEASE_STORE_FILE", "storeFile");
        String type = value(config, "RELEASE_STORE_TYPE", "storeType");
        String alias = value(config, "RELEASE_KEY_ALIAS", "keyAlias");
        char[] storePassword = value(config, "RELEASE_STORE_PASSWORD", "storePassword").toCharArray();
        char[] keyPassword = value(config, "RELEASE_KEY_PASSWORD", "keyPassword").toCharArray();
        Files.createDirectories(signed.toAbsolutePath().getParent());
        Path staging = signed.resolveSibling(signed.getFileName() + ".staging-" + UUID.randomUUID() + ".apk");
        try {
            var store = KeyStore.getInstance(type.isEmpty() ? "PKCS12" : type);
            try (var stream = Files.newInputStream(Path.of(path))) { store.load(stream, storePassword); }
            if (!(store.getKey(alias, keyPassword) instanceof PrivateKey)) throw new IllegalStateException("Private key unavailable");
            String expected = hash(new ByteArrayInputStream(store.getCertificate(alias).getEncoded()));
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            List<String> base = List.of(javaExecutable, "-Xmx256m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2",
                "-jar", Path.of(args[0], "lib", "apksigner.jar").toString());
            var sign = new ArrayList<>(base);
            sign.addAll(List.of("sign", "--ks", path, "--ks-type", type.isEmpty() ? "PKCS12" : type,
                "--ks-key-alias", alias, "--ks-pass", "env:QINGYU_SIGN_STORE_PASS", "--key-pass", "env:QINGYU_SIGN_KEY_PASS",
                "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--out", staging.toString(), unsigned.toString()));
            run(sign, Map.of("QINGYU_SIGN_STORE_PASS", new String(storePassword), "QINGYU_SIGN_KEY_PASS", new String(keyPassword)));
            var verify = new ArrayList<>(base);
            verify.addAll(List.of("verify", "--verbose", "--print-certs", staging.toString()));
            String verification = new String(run(verify, Map.of()), java.nio.charset.StandardCharsets.ISO_8859_1);
            var match = Pattern.compile("certificate SHA-256 digest: ([0-9a-fA-F]+)").matcher(verification);
            if (!match.find() || !match.group(1).equalsIgnoreCase(expected)) throw new IllegalStateException("Signer certificate mismatch");
            if (!payload(unsigned).equals(payload(staging))) throw new IllegalStateException("Signing changed the Actions APK payload");
            Files.move(staging, signed, StandardCopyOption.ATOMIC_MOVE);
            String checksum = hash(Files.newInputStream(signed));
            Files.writeString(signed.resolveSibling(signed.getFileName() + ".sha256"), checksum + "  " + signed.getFileName() + "\n");
            System.out.println("Verified signed APK, configured certificate, and unchanged input payload: " + signed.getFileName());
            System.out.println("SHA256 " + checksum);
        } finally {
            Arrays.fill(storePassword, '\0'); Arrays.fill(keyPassword, '\0');
            Files.deleteIfExists(staging);
            Files.deleteIfExists(Path.of(staging + ".idsig"));
        }
    }
}
