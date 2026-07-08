package io.liftandshift.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User-initiated, one-time download of the on-device AI assets (transformers.js runtime +
 * ONE Apache-2.0 model) into MODELS_DIR. The app itself DISTRIBUTES NO MODEL BYTES — this
 * runs only when the user clicks "Enable on-device AI", and every file is verified against
 * a pinned SHA-256 before it is moved into place (a moved upstream file fails loudly, it is
 * never served). Licenses: transformers.js Apache-2.0, ONNX Runtime MIT,
 * cross-encoder/nli-deberta-v3-xsmall Apache-2.0 (explicit upstream tag).
 */
public final class AssistInstaller {

    private static final Logger log = LoggerFactory.getLogger(AssistInstaller.class);

    record Asset(String relPath, String url, String sha256, long approxBytes) {}

    private static final String JSD = "https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.7.6/dist/";
    private static final String HF = "https://huggingface.co/Xenova/nli-deberta-v3-xsmall/resolve/main/";

    static final List<Asset> ASSETS = List.of(
            new Asset("runtime/transformers.min.js", JSD + "transformers.min.js",
                    "13746ae88695b62e431fc5ebe3beb10a080d2081406047670639ce8c10a9ba25", 876_754),
            new Asset("runtime/ort-wasm-simd-threaded.jsep.mjs", JSD + "ort-wasm-simd-threaded.jsep.mjs",
                    "08fb86ec433c78bfb032c5d84a68b8e8e5a8d81268fa39e24314179a5767a5b9", 44_484),
            new Asset("runtime/ort-wasm-simd-threaded.jsep.wasm", JSD + "ort-wasm-simd-threaded.jsep.wasm",
                    "c46655e8a94afc45338d4cb2b840475f88e5012d524509916e505079c00bfa39", 21_596_019),
            new Asset("nli-deberta-v3-xsmall/config.json", HF + "config.json",
                    "ec0bd14cc28640326474399cd61d38ccd52b64900228799d0f81debda8c4bc53", 1_038),
            new Asset("nli-deberta-v3-xsmall/tokenizer.json", HF + "tokenizer.json",
                    "a86f883318afa11c8c10466f1bf4efaeb6ded28a52cbe57217a8fa0d0a2a87df", 8_656_551),
            new Asset("nli-deberta-v3-xsmall/tokenizer_config.json", HF + "tokenizer_config.json",
                    "d8d3bb123b99317634d5ee3d1d2d8b2ddb01510a0654687fc2639a5347a7291f", 384),
            new Asset("nli-deberta-v3-xsmall/special_tokens_map.json", HF + "special_tokens_map.json",
                    "311de3f4eed9d76a43bf0d71f10e62e086ca65ccce9f15d5da0d2098bf519ecc", 173),
            new Asset("nli-deberta-v3-xsmall/added_tokens.json", HF + "added_tokens.json",
                    "dc046d04c9b0ada7ae6f1dc89c465801799acdf0c9a6aab8c15a1b2d5ca4e91f", 23),
            new Asset("nli-deberta-v3-xsmall/quantize_config.json", HF + "quantize_config.json",
                    "97ed567b73503b8d9d373d5a4124243fe6c11aef0fac5f26c2cf9ab661a116cc", 1_193),
            new Asset("nli-deberta-v3-xsmall/spm.model", HF + "spm.model",
                    "c679fbf93643d19aab7ee10c0b99e460bdbc02fedf34b92b05af343b4af586fd", 2_464_616),
            new Asset("nli-deberta-v3-xsmall/onnx/model_quantized.onnx", HF + "onnx/model_quantized.onnx",
                    "3fac2500c45c75af42c7711de0d1b93d59577456100208be0dc1f9e8811946b6", 87_246_587)
    );

    static final long TOTAL_BYTES = ASSETS.stream().mapToLong(Asset::approxBytes).sum();

    public record State(String phase, String currentFile, int filesDone, int filesTotal,
                        long bytesDone, long bytesTotal, String error) {}

    private final Path dir;
    private final AtomicReference<State> state =
            new AtomicReference<>(new State("idle", null, 0, ASSETS.size(), 0, TOTAL_BYTES, null));
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public AssistInstaller(Path modelsDir) {
        this.dir = modelsDir;
    }

    public boolean installed() {
        return Files.exists(dir.resolve("manifest.json"));
    }

    public Map<String, Object> status() {
        State s = state.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("installed", installed());
        out.put("phase", s.phase());
        out.put("currentFile", s.currentFile());
        out.put("filesDone", s.filesDone());
        out.put("filesTotal", s.filesTotal());
        out.put("bytesDone", s.bytesDone());
        out.put("bytesTotal", s.bytesTotal());
        if (s.error() != null) out.put("error", s.error());
        return out;
    }

    /** Starts the download unless installed or already running. Returns the current status. */
    public synchronized Map<String, Object> install() {
        if (installed() || "running".equals(state.get().phase())) return status();
        state.set(new State("running", null, 0, ASSETS.size(), 0, TOTAL_BYTES, null));
        Thread.startVirtualThread(this::run);
        return status();
    }

    private void run() {
        long bytes = 0;
        int done = 0;
        try {
            for (Asset a : ASSETS) {
                state.set(new State("running", a.relPath(), done, ASSETS.size(), bytes, TOTAL_BYTES, null));
                Path target = dir.resolve(a.relPath());
                if (!verifyExisting(target, a.sha256())) {
                    download(a, target);
                }
                bytes += a.approxBytes();
                done++;
                state.set(new State("running", a.relPath(), done, ASSETS.size(), bytes, TOTAL_BYTES, null));
            }
            writeManifest();
            state.set(new State("done", null, done, ASSETS.size(), bytes, TOTAL_BYTES, null));
            log.info("On-device AI assets installed to {} ({} files, {} bytes)", dir, done, bytes);
        } catch (Exception e) {
            log.warn("Assist install failed: {}", e.getMessage());
            state.set(new State("error", null, done, ASSETS.size(), bytes, TOTAL_BYTES, e.getMessage()));
        }
    }

    private boolean verifyExisting(Path target, String sha) {
        try {
            return Files.exists(target) && sha256(target).equals(sha);
        } catch (IOException e) {
            return false;
        }
    }

    private void download(Asset a, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("." + target.getFileName() + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(a.url()))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "StrikeBench/1.0 (on-device AI installer)")
                .GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " for " + a.url());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream in = new DigestInputStream(res.body(), md)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        String got = HexFormat.of().formatHex(md.digest());
        if (!got.equals(a.sha256())) {
            Files.deleteIfExists(tmp);
            throw new IOException("Checksum mismatch for " + a.relPath()
                    + " (upstream file changed — refusing to install it). expected=" + a.sha256() + " got=" + got);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeManifest() throws IOException {
        String manifest = """
                {
                  "version": 2,
                  "runtime": "transformers.js 3.7.6 (Apache-2.0) + onnxruntime-web jsep (MIT; WASM + WebGPU)",
                  "models": {
                    "zeroshot": {
                      "id": "nli-deberta-v3-xsmall",
                      "task": "zero-shot-classification",
                      "source": "Xenova/nli-deberta-v3-xsmall (conversion of cross-encoder/nli-deberta-v3-xsmall, Apache-2.0)",
                      "dtype": "q8"
                    }
                  },
                  "note": "On-device assist layer only. Engine numbers are computed server-side and never touched by these models. Installed by the user via /api/assist/install; the app distributes no model bytes."
                }
                """;
        Files.writeString(dir.resolve("manifest.json"), manifest);
    }

    private static String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p); DigestInputStream din = new DigestInputStream(in, md)) {
                din.transferTo(OutputStreamDiscard.INSTANCE);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** /dev/null OutputStream (avoids pulling in extra deps for a digest pass). */
    private static final class OutputStreamDiscard extends java.io.OutputStream {
        static final OutputStreamDiscard INSTANCE = new OutputStreamDiscard();
        @Override public void write(int b) { }
        @Override public void write(byte[] b, int off, int len) { }
    }
}
