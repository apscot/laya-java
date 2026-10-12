package io.github.apscot.laya.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** Resolves a model id or directory to local files, downloading from the Hugging Face Hub when needed. */
public final class ModelStore {
    private static final String[] REQUIRED = {"model.safetensors", "rl_agent_config.json", "encoder/config.json",
        "tokenizer/tokenizer.json", "tokenizer/tokenizer_config.json"};

    private ModelStore() {}

    public static Path cacheRoot() {
        String env = System.getenv("LAYA_CACHE");
        return env != null && !env.isEmpty() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".cache", "laya");
    }

    /** @param idOrPath local directory, or a Hub repo id such as {@code aac6fef/laya-mlx}. */
    public static Path resolve(String idOrPath, String revision, String subfolder) throws IOException {
        Path local = Path.of(idOrPath.startsWith("~") ? System.getProperty("user.home") + idOrPath.substring(1) : idOrPath);
        Path dir;
        if (Files.isDirectory(local)) {
            dir = local;
        } else {
            if (idOrPath.startsWith("/") || idOrPath.startsWith("./") || idOrPath.startsWith("../") || idOrPath.startsWith("~"))
                throw new IOException("Local model directory does not exist: " + idOrPath);
            if (!idOrPath.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IOException("Not a Hub repo id or directory: " + idOrPath);
            String rev = revision == null ? "main" : revision;
            dir = cacheRoot().resolve(idOrPath.replace("/", "--") + "@" + rev);
            String prefix = subfolder == null || subfolder.isEmpty() ? "" : subfolder.replaceAll("/+$", "") + "/";
            for (String f : REQUIRED) {
                Path target = dir.resolve(prefix + f);
                if (!Files.isRegularFile(target)) download(idOrPath, rev, prefix + f, target);
            }
        }
        if (subfolder != null && !subfolder.isEmpty()) {
            if (subfolder.startsWith("/") || subfolder.contains("..")) throw new IOException("subfolder must be a relative path inside the model repository");
            dir = dir.resolve(subfolder);
        }
        for (String f : new String[] {"model.safetensors", "rl_agent_config.json", "encoder/config.json"})
            if (!Files.isRegularFile(dir.resolve(f))) throw new IOException("Not a complete Laya checkpoint: " + dir.resolve(f) + " is missing");
        return dir;
    }

    private static void download(String repo, String rev, String file, Path target) throws IOException {
        String base = System.getenv().getOrDefault("HF_ENDPOINT", "https://huggingface.co");
        String url = base + "/" + repo + "/resolve/" + rev + "/" + file;
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).GET();
        String token = System.getenv("HF_TOKEN");
        if (token != null && !token.isEmpty()) rb.header("Authorization", "Bearer " + token);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpResponse<InputStream> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("Download failed (" + r.statusCode() + "): " + url);
            try (InputStream in = r.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
