package me.mycellium.skymyce.loader;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/** Reject altered cached payloads before Minecraft and the protected mod's entrypoints initialize. */
public final class PayloadVerifier implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        var loader = FabricLoader.getInstance();
        var payload = loader.getModContainer("skymyce");
        if (payload.isEmpty()) return;
        try {
            var file = loader.getGameDir().resolve(".sky-myce/manifest.json");
            if (Files.size(file) > 16384) throw new SecurityException();
            var envelope = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var bytes = Base64.getUrlDecoder().decode(envelope.get("payload").getAsString());
            var issued = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().get("issuedAt").getAsLong();
            // Acquisition expiry cannot brick a previously signed JAR. Fresh runtime authorization still requires a current release.
            var manifest = SignedFiles.manifest(envelope, SignedFiles.configuration(), issued);
            if (!manifest.get("version").getAsString().equals(payload.get().getMetadata().getVersion().getFriendlyString())) throw new SecurityException();
            var origins = payload.get().getOrigin().getPaths();
            if (origins.size() != 1) throw new SecurityException();
            SignedFiles.verifyArtifact(origins.getFirst(), manifest);
        } catch (Exception error) {
            throw new IllegalStateException("Sky-Myce protected artifact verification failed. Restore the signed release using the Sky-Myce Loader.");
        }
    }
}
