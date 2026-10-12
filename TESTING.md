# Testing laya-java by hand, from zero

This guide creates every kind of consumer project yourself and checks each module. Every file below was created exactly like this and run
successfully on macOS (Apple Silicon, JDK 21, Gradle 9, Maven 3.9). Copy the files, run the commands, compare with "Expected".

Contents: 0 Prerequisites · 1 Build and install laya-java locally · 2 Plain Java app (laya-core) · 3 Spring Boot app, Gradle · 4 Spring Boot app, Maven ·
5 Calling the endpoints with curl · 6 Java client app · 7 Spring AI TypeSafe app · 8 Gradle plugin app · 9 The repo's own tests (parity with Python) ·
10 Speed · 11 Release dry run · 12 Troubleshooting

## 0. Prerequisites

```bash
java -version      # 17 or newer (21 is fine)
gradle --version   # 8+ (9.x tested)
mvn -version       # only for section 4
curl --version
```
You also need about 3 GB of free RAM per running server, ~1 GB disk per model, and internet on first run (models download from Hugging Face).

Make a work folder for everything:
```bash
mkdir -p ~/laya-tutorial && cd ~/laya-tutorial
```

## 1. Build laya-java and install it to your local Maven repository

The artifacts are not on Maven Central yet, so the tutorial projects read them from `~/.m2` (that is what `mavenLocal()` means).
```bash
cd /Users/apscot/laya/laya-java        # the repo
gradle publishToMavenLocal
ls ~/.m2/repository/io/github/apscot/  # laya-accelerate laya-api laya-bom laya-client laya-core laya-gradle-plugin laya-spring-boot-starter ...
```
Version used below: `0.1.0-SNAPSHOT`. After a real release you will use `0.1.0` and can delete the `mavenLocal()` lines.

Models download automatically on first use into `~/.cache/laya` (set `LAYA_CACHE=/some/dir` to change it). English is ~800 MB, multilingual ~650 MB.

## 2. Plain Java app using `laya-core` (no server)

Tests: loading, tokenizer, model, answers, and the optional macOS speed-up.
```bash
cd ~/laya-tutorial && mkdir -p java-app/src/main/java/demo && cd java-app
```
Create these three files:


`java-app/settings.gradle.kts`
```kotlin
rootProject.name = "java-app"
```

`java-app/build.gradle.kts`
```kotlin
plugins {
    java
    application
}

repositories {
    mavenLocal()       // where `gradle publishToMavenLocal` put laya-*; after release, mavenCentral() alone is enough
    mavenCentral()
}

tasks.withType<JavaCompile> { options.release.set(17) }   // runs on any JDK 17+

dependencies {
    implementation("io.github.apscot:laya-core:0.1.0-SNAPSHOT")
    runtimeOnly("io.github.apscot:laya-accelerate:0.1.0-SNAPSHOT")   // optional, macOS speed-up
}

application {
    mainClass.set("demo.Main")
    applicationDefaultJvmArgs = listOf("--add-modules=jdk.incubator.vector", "-Xmx3g")
}
```

`java-app/src/main/java/demo/Main.java`
```java
package demo;

import io.github.apscot.laya.core.Agent;
import io.github.apscot.laya.core.Question;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        try (Agent agent = Agent.load("aac6fef/laya-mlx")) {      // downloads once into ~/.cache/laya
            Map<String, Question> qs = new LinkedHashMap<>();
            qs.put("dept", Question.choice("Which team should handle this?", List.of("billing", "technical", "sales")));
            qs.put("urgent", Question.noul("Does this convey urgency?", null));
            qs.put("frustration", Question.score("How frustrated is the customer?", List.of("Calm", "Frustrated", "Very angry")));

            long t = System.nanoTime();
            Map<String, Object> result = agent.predict("Help! My payouts have been failing for 3 days.", qs);
            System.out.println("took " + (System.nanoTime() - t) / 1_000_000 + " ms (first call includes warm-up)");

            Map<String, Object> answers = (Map<String, Object>) result.get("answers");
            System.out.println("dept        = " + ((Map<String, Object>) answers.get("dept")).get("choice"));
            System.out.println("urgent      = " + ((Map<String, Object>) answers.get("urgent")).get("noul"));
            System.out.println("frustration = " + ((Map<String, Object>) answers.get("frustration")).get("score"));
            System.out.println("full result: " + result);
        }
    }
}
```

Run:
```bash
gradle run
```
Expected (first run downloads the model, so wait a few minutes; numbers are identical for you):
```
took ~500 ms (first call includes warm-up)
dept        = billing
urgent      = 0.7419
frustration = 1.0889
full result: {model=laya-rl-agent, answers={dept={type=choice, ... probabilities={billing=0.8914, technical=0.069, sales=0.0396} ...
```
You may see `WARNING: ... jna ...` lines (harmless, from the macOS speed-up) and a one-time `temperatures outside [0.5, 5.0]` warning (expected: the checkpoint ships one miscalibrated bucket and laya clamps it, same as the Python port).

Things to try: remove the `laya-accelerate` line and run again (slower, still same numbers); change the state text; add a 4th option to `dept`.

## 3. Spring Boot app with Gradle

```bash
cd ~/laya-tutorial && mkdir -p boot-gradle/src/main/java/demo boot-gradle/src/main/resources && cd boot-gradle
```

`boot-gradle/settings.gradle.kts`
```kotlin
rootProject.name = "boot-gradle"
```

`boot-gradle/build.gradle.kts`
```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
}

repositories {
    mavenLocal()
    mavenCentral()
}

tasks.withType<JavaCompile> { options.release.set(17) }

dependencies {
    implementation(platform("io.github.apscot:laya-bom:0.1.0-SNAPSHOT"))
    implementation("io.github.apscot:laya-spring-boot-starter")
}

tasks.bootRun {
    jvmArgs("--add-modules=jdk.incubator.vector", "-Xmx3g")
}
```

`boot-gradle/src/main/java/demo/App.java`
```java
package demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
```

`boot-gradle/src/main/resources/application.properties`
```properties
server.port=8080
laya.preload=true
# laya.api-key=secret
# laya.models.english=/path/to/local/checkpoint     # optional: use a local directory instead of downloading
```

Run (leave it running):
```bash
gradle bootRun
```
Expected: Spring logs end with `Started App ...` after the model loads (`laya.preload=true`). Then go to section 5 for curl tests. Stop with Ctrl-C.

## 4. Spring Boot app with Maven

```bash
cd ~/laya-tutorial && mkdir -p boot-maven/src/main/java/demo boot-maven/src/main/resources && cd boot-maven
cp ../boot-gradle/src/main/java/demo/App.java src/main/java/demo/
cp ../boot-gradle/src/main/resources/application.properties src/main/resources/
```

`boot-maven/pom.xml`
```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.5</version>
  </parent>
  <groupId>demo</groupId>
  <artifactId>boot-maven</artifactId>
  <version>1</version>
  <properties>
    <java.version>17</java.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.github.apscot</groupId>
        <artifactId>laya-bom</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.apscot</groupId>
      <artifactId>laya-spring-boot-starter</artifactId>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <jvmArguments>--add-modules=jdk.incubator.vector -Xmx3g</jvmArguments>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

Run on another port so it can coexist with section 3:
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --laya.api-key=secret"
```
Now this server requires `Authorization: Bearer secret` (see 5.6).

## 5. Calling the endpoints with curl

Use port 8080 for the Gradle app (no key) or 8081 for the Maven app (key `secret`; add `-H 'Authorization: Bearer secret'`).

**5.1 Health and models**
```bash
curl -s localhost:8080/v1/models
```
Expected: `{"object":"list","data":[{"id":"english",...},{"id":"typed-decisions",...},{"id":"multilingual",...}]}`

**5.2 Jev / System One** (three question types in one call)
```bash
curl -s localhost:8080/v1/systemone -H 'content-type: application/json' -d '{
  "state": "Help! My payouts have been failing for 3 days.",
  "model": "laya",
  "questions": {
    "urgent": {"type":"noul","instructions":"Does this convey urgency?"},
    "dept": {"type":"choice","instructions":"Which team?","criteria":{"billing":"payments","technical":"bugs","sales":"pricing"}},
    "frustration": {"type":"score","instructions":"How frustrated is the customer?","criteria":["Calm","Frustrated","Very angry"]}
  }}'
```
Expected: `urgent.noul` ≈ 0.74, `dept.choice` = `billing`, `frustration.score` ≈ 1.09, and `usage.output_tokens` = 0.

**5.3 OpenAI Decisions**
```bash
curl -s localhost:8080/v1/decisions -H 'content-type: application/json' -d '{
  "model": "laya",
  "input": "I was billed twice, please refund.",
  "questions": [
    {"type":"predicate","name":"refund","instructions":"Does the customer want money back?"},
    {"type":"choice","name":"dept","instructions":"Which team?",
     "choices":[{"value":"billing","description":"payments"},{"value":"technical","description":"bugs"}]},
    {"type":"score","name":"severity","instructions":"How severe?","levels":[{"label":"low"},{"label":"high"}]}
  ]}'
```
Expected: `refund.probability` ≈ 0.84, `dept.choice` = `billing`, `severity.score` between 0 and 1, `usage.total_tokens` > 0.
`model` is only required by the format: Laya is the model. Use `english`, `multilingual` or `typed-decisions` to pick a checkpoint; any other name auto-routes by language.

**5.4 Validation errors**
```bash
curl -s -w ' [%{http_code}]\n' localhost:8080/v1/systemone -d '{"state":"x","questions":{"q":{"type":"noul"}}}'
curl -s -w ' [%{http_code}]\n' localhost:8080/v1/decisions -d '{"input":"x","questions":[]}'
```
Expected: `{"detail": "questions.q: ..."} [422]` and `{"error": {... "type": "invalid_request_error" ...}} [400]`.

**5.5 Language routing** (needs a server that has not preloaded multilingual)
```bash
curl -s localhost:8080/v1/systemone -H 'content-type: application/json' -d '{"state":"发票被重复扣款，请退款。","model":"laya","questions":{"refund":{"type":"noul","instructions":"客户是否要求退款？"}}}'
ls ~/.cache/laya        # a second folder aac6fef--laya-multilingual-mlx@main appears (downloaded on this first Chinese request)
```
Expected: `noul` ≈ 0.9956. English text never loads the multilingual model.

**5.6 API key** (Maven server on 8081)
```bash
curl -s -w ' [%{http_code}]\n' localhost:8081/v1/models                                   # {"error": ... authentication_error} [401]
curl -s -w ' [%{http_code}]\n' localhost:8081/v1/models -H 'Authorization: Bearer secret' # [200]
```
For `/v1/systemone` a missing key returns `{"detail": "Invalid or missing API key"} [401]`.

**5.7 Typed-decisions model**
```bash
curl -s localhost:8080/v1/systemone -H 'content-type: application/json' -d '{"state":"hello","model":"typed-decisions","questions":{"greet":{"type":"noul","instructions":"Is this a greeting?"}}}'
```
(Downloads the third checkpoint on first use.)

## 6. Java client app (`laya-client`)

Start a server first (section 3). Then:
```bash
cd ~/laya-tutorial && mkdir -p client-app/src/main/java/demo && cd client-app
```

`client-app/settings.gradle.kts`
```kotlin
rootProject.name = "client-app"
```

`client-app/build.gradle.kts`
```kotlin
plugins {
    java
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

tasks.withType<JavaCompile> { options.release.set(17) }

dependencies {
    implementation("io.github.apscot:laya-client:0.1.0-SNAPSHOT")   // brings laya-core for the Question class
}

application { mainClass.set("demo.Main") }
```

`client-app/src/main/java/demo/Main.java`
```java
package demo;

import io.github.apscot.laya.client.LayaClient;
import io.github.apscot.laya.core.Question;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://localhost:8080";
        String key = args.length > 1 ? args[1] : null;          // null = no Authorization header
        LayaClient client = new LayaClient(url, key);

        Map<String, Question> qs = new LinkedHashMap<>();
        qs.put("refund", Question.noul("Does the customer want money back?", null));
        qs.put("dept", Question.choice("Which team?", List.of("billing", "technical")));
        Map<String, Object> r = client.systemOne("I was billed twice, please refund.", qs);

        System.out.println("refund = " + LayaClient.noul(r, "refund"));
        System.out.println("dept   = " + LayaClient.choice(r, "dept"));
        System.out.println("models = " + client.models());
    }
}
```

```bash
gradle run --args="http://localhost:8080"
gradle run --args="http://localhost:8081 secret"      # the keyed Maven server
```
Expected: `refund = 0.8436`, `dept   = billing`, and the models map. A wrong key throws `LayaClient.ApiException` with `status = 401`.

## 7. Spring AI TypeSafe app (proves Jev compatibility)

Start the section 3 server, then:
```bash
cd ~/laya-tutorial && mkdir -p typesafe-app/src/main/java/demo && cd typesafe-app
```

`typesafe-app/settings.gradle.kts`
```kotlin
rootProject.name = "typesafe-app"
```

`typesafe-app/build.gradle.kts`
```kotlin
plugins {
    java
    application
}

repositories { mavenCentral() }

tasks.withType<JavaCompile> { options.release.set(17) }

dependencies {
    implementation("org.springaicommunity:typesafe-java-sdk:0.1.0")
}

application { mainClass.set("demo.Main") }
```

`typesafe-app/src/main/java/demo/Main.java`
```java
package demo;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.SystemOneResponse;

public class Main {
    public static void main(String[] args) {
        TypeSafeClient client = TypeSafeClient.builder()
                .baseUrl("http://localhost:8080")      // laya-java instead of the hosted Jev API
                .apiKey("anything")                    // this client refuses an empty key; laya ignores it unless laya.api-key is set
                .build();
        Map<String, Question> qs = new LinkedHashMap<>();
        qs.put("urgent", Noul.of("Does this convey urgency?"));
        qs.put("dept", Choice.of("Which team should handle this?", "billing", "technical", "sales"));
        qs.put("frustration", Score.of("How frustrated is the customer?", "Calm", "Frustrated", "Very angry"));
        SystemOneResponse r = client.systemOne("Help! My payouts have been failing for 3 days.", qs);
        System.out.println("urgent      = " + r.noulValue("urgent"));
        System.out.println("dept        = " + r.choiceValue("dept"));
        System.out.println("frustration = " + r.scoreValue("frustration"));
        System.out.println("usage       = " + r.usage());
    }
}
```

```bash
gradle run
```
Expected: `urgent = 0.7419`, `dept = billing`, `frustration = 1.0889`, `usage = Usage[inputTokens=118, outputTokens=0]`.
This is the real `typesafe-java-sdk` from Spring AI Community, unmodified, talking to your local server. (`SLF4J` warnings are harmless.)

## 8. Gradle plugin app (`io.github.apscot.laya`)

```bash
cd ~/laya-tutorial && mkdir -p plugin-app/src/test/java/demo && cd plugin-app
```

`plugin-app/settings.gradle.kts`
```kotlin
pluginManagement {
    repositories {
        mavenLocal()           // after release: remove this line
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "plugin-app"
```

`plugin-app/build.gradle.kts`
```kotlin
plugins {
    java
    id("io.github.apscot.laya") version "0.1.0-SNAPSHOT"
}

repositories {
    mavenLocal()
    mavenCentral()
}

laya {
    model.set("aac6fef/laya-mlx")
    port.set(8003)
    testIntegration.set(true)      // start the server around `gradle test` and pass -Dlaya.base-url
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
```

`plugin-app/src/test/java/demo/LayaIT.java`
```java
package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class LayaIT {
    @Test
    void serverAnswers() throws Exception {
        String base = System.getProperty("laya.base-url");   // set by the plugin
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/v1/systemone"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"state\":\"I was billed twice, refund me\",\"model\":\"laya\","
                        + "\"questions\":{\"refund\":{\"type\":\"noul\",\"instructions\":\"Does the customer want money back?\"}}}"))
                .build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"noul\""));
    }
}
```

Run the integration test. The plugin starts a server on port 8003, runs your test, and stops the server:
```bash
gradle test -i | grep -E "Laya server|PASSED|FAILED"
```
Expected: `Laya server ready on http://127.0.0.1:8003`, the test passes, then `Stopped Laya server`.

Manual control:
```bash
gradle layaServe        # starts it in the background and waits until healthy
curl -s localhost:8003/health
gradle layaStop
curl -s -m 2 localhost:8003/health || echo stopped
```
Logs of the background server: `build/laya/server.log`.

## 9. The repo's own tests (parity with the Python reference)

```bash
cd /Users/apscot/laya/laya-java
gradle build                    # works with no models; checkpoint tests are skipped
```
To run the real parity tests, point at downloaded checkpoints (they exist after sections 3 and 5.5/5.7):
```bash
export LAYA_MODEL_DIR=~/.cache/laya/aac6fef--laya-mlx@main
export LAYA_MODEL_DIR_MULTILINGUAL=~/.cache/laya/aac6fef--laya-multilingual-mlx@main
export LAYA_MODEL_DIR_TYPED=~/.cache/laya/aac6fef--laya-typed-decisions-mlx@main
gradle :laya-core:test :laya-api:test :laya-spring-boot-starter:test :laya-client:test --rerun-tasks
python3 - <<'PY'
import glob,re
for f in sorted(glob.glob('*/build/test-results/test/*.xml')):
    print(f.split('/')[0], re.search(r'tests="\d+" skipped="\d+" failures="\d+"',open(f).read()).group(0))
PY
```
Expected: `ParityTest` shows `tests="9" skipped="0" failures="0"` (3 checks x 3 checkpoints). If `skipped="9"` the env vars were not set in this shell.
Force the portable (non-Accelerate) code path: `LAYA_BACKEND=portable gradle :laya-core:test --tests '*ParityTest*' --rerun-tasks`.

Regenerating the Python fixtures (optional, dev-only, needs Python 3.11+): `python3 -m venv v && v/bin/pip install laya-mlx` then
`v/bin/python tools/gen_fixtures.py <model-dir> laya-core/src/test/resources/fixtures [multilingual|typed]`.

## 10. Speed
```bash
gradle :laya-core:test --tests '*LatencyBench*' -i | grep BENCH
LAYA_BACKEND=portable gradle :laya-core:test --tests '*LatencyBench*' -i --rerun-tasks | grep BENCH
```
Expected on an M-series Mac: ~60 ms with Accelerate, ~190 ms portable.

## 11. Release dry run (uploads nothing)
```bash
gradle -PlayaVersion=0.1.0 nmcpZipAggregation
unzip -l build/nmcp/zip/aggregation.zip | grep -E "\.pom$"      # 8 poms: core, accelerate, api, client, starter, plugin, plugin marker, bom
```
Signing check with a throwaway key: see `RELEASING.md`.

## 12. Troubleshooting

| Symptom | Fix |
|---|---|
| `Cannot find a Java installation ... matching languageVersion=17` | Don't use a Gradle `toolchain` block unless JDK 17 is installed; use `options.release.set(17)` as in the files above. |
| `Unrecognized option: --add-modules` from `JAVA_TOOL_OPTIONS` | Use the `=` form: `--add-modules=jdk.incubator.vector`. |
| `OutOfMemoryError: Java heap space` | The English model needs ~2.5 GB; add `-Xmx3g`. |
| `Address already in use` | Another server is on that port; change `server.port` / `LAYA_PORT` or stop it (`lsof -i :8080`). |
| First request takes seconds | Model loading/JIT warm-up; use `laya.preload=true`, later calls are ~60-100 ms. |
| `HTTP 401` | Server has `laya.api-key`; send `Authorization: Bearer <key>`. |
| Parity tests show `skipped` | `LAYA_MODEL_DIR*` not exported in the same shell. |
| `Download failed (401/403)` | Set `HF_TOKEN` (the three `aac6fef/*` repos are public, so this only affects private mirrors). |
