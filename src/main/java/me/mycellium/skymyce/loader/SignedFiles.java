package me.mycellium.skymyce.loader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Public-key trust is compiled into the loader; HTTP responses can never add trust roots. */
public final class SignedFiles {
    private SignedFiles() {}
    public static Properties configuration() throws Exception {
        var properties = new Properties();
        try (var stream = SignedFiles.class.getResourceAsStream("/sky-myce-loader.properties")) {
            if (stream == null) throw new SecurityException("Loader configuration missing");
            properties.load(stream);
        }
        return properties;
    }
    public static PublicKey key(Properties config, String kid) throws Exception {
        if (!kid.matches("[A-Za-z0-9_-]{1,64}")) throw new SecurityException("Invalid signing key");
        var encoded = config.getProperty("key." + kid);
        if (encoded == null || encoded.isBlank()) throw new SecurityException("Untrusted signing key");
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }
    public static void verify(PublicKey key, byte[] bytes, String signature) throws Exception {
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key); verifier.update(bytes);
        if (!verifier.verify(Base64.getUrlDecoder().decode(signature))) throw new SecurityException("Invalid signature");
    }
    public static JsonObject manifest(JsonObject envelope, Properties config, long now) throws Exception {
        byte[] payload = Base64.getUrlDecoder().decode(envelope.get("payload").getAsString());
        if (payload.length > 8192) throw new SecurityException("Manifest too large");
        verify(key(config, envelope.get("kid").getAsString()), payload, envelope.get("signature").getAsString());
        var manifest = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!manifest.get("product").getAsString().equals("sky-myce") || manifest.get("protocol").getAsInt() != 3
                || !manifest.get("minecraft").getAsString().equals(config.getProperty("minecraft"))
                || compareVersion(config.getProperty("loaderVersion"), manifest.get("minimumLoaderVersion").getAsString()) < 0
                || manifest.get("issuedAt").getAsLong() > now + 60000 || manifest.get("expiresAt").getAsLong() <= now
                || !manifest.get("sha256").getAsString().matches("[a-f0-9]{64}")
                || !manifest.get("version").getAsString().matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new SecurityException("Unsupported or expired manifest");
        }
        return manifest;
    }
    public static int compareVersion(String left, String right) {
        if (!left.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || !right.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new SecurityException("Invalid version");
        var a = left.split("\\."); var b = right.split("\\.");
        for (int i = 0; i < 3; i++) { int result = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i])); if (result != 0) return result; }
        return 0;
    }
    public static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static void verifyArtifact(Path file, JsonObject manifest) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int n; while ((n = stream.read(buffer)) != -1) digest.update(buffer, 0, n); }
        if (!HexFormat.of().formatHex(digest.digest()).equals(manifest.get("sha256").getAsString())) throw new SecurityException("Artifact integrity check failed");
    }
    public static void atomicWrite(Path target, byte[] data) throws Exception {
        Files.createDirectories(target.toAbsolutePath().getParent());
        var temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".sky-myce-", ".tmp");
        try { Files.write(temporary, data); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
}
