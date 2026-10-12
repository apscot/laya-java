package io.github.apscot.laya.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.api.tasks.testing.Test;

/** Applies {@code layaServe} / {@code layaStop} tasks and optional {@code test} integration. */
public class LayaPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        LayaExtension ext = project.getExtensions().create("laya", LayaExtension.class);
        ext.getModel().convention("aac6fef/laya-mlx");
        ext.getPort().convention(8002);
        ext.getTestIntegration().convention(false);
        ext.getMaxHeap().convention("3g");
        String own = LayaPlugin.class.getPackage().getImplementationVersion();
        ext.getRuntimeVersion().convention(own != null ? own : "0.1.0-SNAPSHOT");

        Configuration runtime = project.getConfigurations().create("layaRuntime");
        runtime.setCanBeConsumed(false);
        runtime.getDependencies().addLater(ext.getRuntimeVersion().map(v -> project.getDependencies().create("io.github.apscot:laya-api:" + v)));
        runtime.getDependencies().addLater(ext.getRuntimeVersion().map(v -> project.getDependencies().create("io.github.apscot:laya-accelerate:" + v)));

        File pidFile = new File(project.getLayout().getBuildDirectory().get().getAsFile(), "laya/server.properties");

        project.getTasks().register("layaServe", ServeTask.class, t -> {
            t.setGroup("laya");
            t.setDescription("Start a local Laya server in the background and wait until it is healthy");
            t.setExt(ext);
            t.setRuntime(runtime);
            t.setStateFile(pidFile);
        });
        project.getTasks().register("layaStop", StopTask.class, t -> {
            t.setGroup("laya");
            t.setDescription("Stop the Laya server started by layaServe");
            t.setStateFile(pidFile);
        });

        project.afterEvaluate(p -> {
            if (!ext.getTestIntegration().get()) return;
            p.getTasks().withType(Test.class).configureEach(test -> {
                test.dependsOn("layaServe");
                test.finalizedBy("layaStop");
                test.doFirst(t -> {
                    test.systemProperty("laya.base-url", "http://127.0.0.1:" + ext.getPort().get());
                    if (ext.getApiKey().isPresent()) test.systemProperty("laya.api-key", ext.getApiKey().get());
                });
            });
        });
    }

    @DisableCachingByDefault(because = "Starts a background process")
    public abstract static class ServeTask extends DefaultTask {
        private LayaExtension ext;
        private Configuration runtime;
        private File stateFile;

        void setExt(LayaExtension e) { ext = e; }
        void setRuntime(Configuration c) { runtime = c; }
        void setStateFile(File f) { stateFile = f; }

        @TaskAction
        public void serve() throws Exception {
            Files.createDirectories(stateFile.getParentFile().toPath());
            int port = ext.getPort().get();
            String javaHome = System.getProperty("java.home");
            List<String> cmd = new ArrayList<>();
            cmd.add(javaHome + "/bin/java");
            cmd.add("--add-modules");
            cmd.add("jdk.incubator.vector");
            cmd.add("-Xmx" + ext.getMaxHeap().get());
            cmd.add("-cp");
            StringBuilder cp = new StringBuilder();
            for (File f : runtime.resolve()) cp.append(f.getAbsolutePath()).append(File.pathSeparator);
            cmd.add(cp.toString());
            cmd.add("io.github.apscot.laya.api.LayaHttpServer");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LAYA_MODELS", ext.getModel().get());
            pb.environment().put("LAYA_PORT", String.valueOf(port));
            pb.environment().put("LAYA_PRELOAD", "1");
            if (ext.getApiKey().isPresent()) pb.environment().put("LAYA_API_KEY", ext.getApiKey().get());
            File log = new File(stateFile.getParentFile(), "server.log");
            pb.redirectErrorStream(true).redirectOutput(log);
            Process p = pb.start();
            Files.writeString(stateFile.toPath(), "pid=" + p.pid() + "\nport=" + port + "\n");
            HttpClient http = HttpClient.newHttpClient();
            long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
            while (System.nanoTime() < deadline) {
                if (!p.isAlive()) throw new GradleException("Laya server exited early; see " + log);
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() == 200 && r.body().contains("\"models\": [\"")) {
                        getLogger().lifecycle("Laya server ready on http://127.0.0.1:" + port + " (pid " + p.pid() + ")");
                        return;
                    }
                } catch (IOException ignored) {
                    // not up yet
                }
                Thread.sleep(500);
            }
            p.destroy();
            throw new GradleException("Timed out waiting for the Laya server; see " + log);
        }
    }

    @DisableCachingByDefault(because = "Stops a background process")
    public abstract static class StopTask extends DefaultTask {
        private File stateFile;

        void setStateFile(File f) { stateFile = f; }

        @TaskAction
        public void stop() throws IOException {
            if (!stateFile.isFile()) return;
            for (String line : Files.readAllLines(stateFile.toPath())) {
                if (line.startsWith("pid=")) {
                    ProcessHandle.of(Long.parseLong(line.substring(4))).ifPresent(h -> {
                        h.destroy();
                        getLogger().lifecycle("Stopped Laya server (pid " + h.pid() + ")");
                    });
                }
            }
            Files.deleteIfExists(stateFile.toPath());
        }
    }
}
