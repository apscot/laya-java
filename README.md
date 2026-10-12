# laya-java

Use [Laya](https://github.com/NandhaKishorM/laya) from Java. No Python, no PyTorch, no MLX.

Laya is a small model that **answers questions about a piece of text with probabilities** instead of writing text.
You give it some text and a question, and it answers in a fraction of a second:

| Question type | Example | Answer |
|---|---|---|
| yes/no (`noul` / `predicate`) | "Does the customer want money back?" | `0.84` |
| choice | "Which team: billing or technical?" | `billing` (92 %), `technical` (8 %) |
| score | "How frustrated is the customer? Calm / Frustrated / Very angry" | `1.09` (between "Frustrated" and "Very angry") |

This project runs the model inside your JVM and, optionally, exposes it as an HTTP endpoint that speaks
**OpenAI's Decisions API** (`/v1/decisions`) and the **Jev / System One** protocol (`/v1/systemone`).

> Independent Java port. Not an official release of Convai Innovations or Laya.
> Model weights are not in the jars; they are downloaded from Hugging Face on first use (Apache-2.0).

## Choose how to use it

1. **Inside your Java code (no server, no HTTP)** – add `laya-core`.
2. **As an HTTP endpoint in a Spring Boot app** – add `laya-spring-boot-starter`.
3. **As a standalone server** – run `LayaHttpServer` (any language can call it).
4. **From a Gradle build/test** – use the `io.github.apscot.laya` plugin.

### 1. In your code

```kotlin
implementation("io.github.apscot:laya-core:0.1.0")
implementation("io.github.apscot:laya-accelerate:0.1.0")   // optional: much faster on macOS
```

```java
try (Agent agent = Agent.load("aac6fef/laya-mlx")) {          // downloaded once to ~/.cache/laya
    Map<String, Question> qs = new LinkedHashMap<>();
    qs.put("dept", Question.choice("Which team should handle this?", List.of("billing", "technical", "sales")));
    qs.put("urgent", Question.noul("Does this convey urgency?", null));
    Map<String, Object> result = agent.predict("Help! My payouts have been failing for 3 days.", qs);
    // result.answers.dept.choice, result.answers.urgent.noul, ... (same shape as the Python library)
}
```

### 2. Spring Boot endpoint

```kotlin
implementation(platform("io.github.apscot:laya-bom:0.1.0"))
implementation("io.github.apscot:laya-spring-boot-starter")
```

```xml
<dependencyManagement><dependencies><dependency>
  <groupId>io.github.apscot</groupId><artifactId>laya-bom</artifactId><version>0.1.0</version><type>pom</type><scope>import</scope>
</dependency></dependencies></dependencyManagement>
<dependency><groupId>io.github.apscot</groupId><artifactId>laya-spring-boot-starter</artifactId></dependency>
```

Start the app and call it:

```bash
curl -s localhost:8080/v1/decisions -H 'content-type: application/json' -d '{
  "model": "laya",
  "input": "I was billed twice, please refund.",
  "questions": [
    {"type":"predicate","name":"refund","instructions":"Does the customer want money back?"},
    {"type":"choice","name":"dept","instructions":"Which team?",
     "choices":[{"value":"billing","description":"payments"},{"value":"technical","description":"bugs"}]}
  ]}'
```

Endpoints: `POST /v1/decisions` (OpenAI), `POST /v1/systemone` (Jev), `GET /v1/models`.

**About `model`:** Laya is the model. The field exists only because OpenAI's and Jev's request formats have it. Use `english`,
`multilingual` or `typed-decisions` to pick a checkpoint. Any other value (such as `laya` or `gpt-6-luna`) lets the server choose
English or multilingual from the language of the text.

**About the API key:** none is needed. Set `laya.api-key` only if you want to require `Authorization: Bearer <key>`.

Properties: `laya.models.<name>` (Hugging Face repo id or local directory), `laya.default-model`, `laya.api-key`,
`laya.preload` (load at startup), `laya.endpoint.enabled`, `laya.endpoint.base-path`.
Defaults: `english` = `aac6fef/laya-mlx`, `multilingual` = `aac6fef/laya-multilingual-mlx`, `typed-decisions` = `aac6fef/laya-typed-decisions-mlx`.

### 3. Standalone server

```bash
gradle :laya-api:serve        # env: LAYA_PORT (default 8000), LAYA_API_KEY, LAYA_MODELS, LAYA_PRELOAD
```

### 4. Gradle plugin

```kotlin
plugins { id("io.github.apscot.laya") version "0.1.0" }
laya { model.set("aac6fef/laya-mlx"); port.set(8002); testIntegration.set(true) }
```

`layaServe` / `layaStop` start and stop a local server. With `testIntegration`, it runs around your `test` task and passes
`laya.base-url` to it.

## Spring AI TypeSafe

[`spring-ai-community/spring-ai-typesafe`](https://github.com/spring-ai-community/spring-ai-typesafe) talks to Jev's `/v1/systemone`.
laya-java can stand in for the hosted API:

```properties
spring.ai.typesafe.base-url=http://localhost:8080
spring.ai.typesafe.api-key=anything     # that client refuses an empty key; laya-java ignores it unless you set laya.api-key
```

Tested with `typesafe-java-sdk:0.1.0` (yes/no, choice, score, rejected key).

## Speed and memory

Run with `--add-modules=jdk.incubator.vector` (with `JAVA_TOOL_OPTIONS` use the `=` form). Without it the library falls back to a slower scalar
kernel. Allow about 2.5 GB of memory for the English model.

Measured on an Apple M5 (10 cores), JDK 21, English checkpoint, warm, 31-token question:

| Backend | median |
|---|---|
| `laya-accelerate` (macOS Accelerate via JNA) | ~60 ms |
| Portable Java (Vector API) | ~186 ms |
| Python/MLX on a GPU (published, M3 Max) | ~13 ms |

A 512-token input takes about 1 s with Accelerate. This port is slower than MLX on a GPU; its advantage is running anywhere a JVM runs.

## How it was checked

Tokenizer ids, scores and final answers match the Python `laya-mlx` (CPU, float32) for the English, multilingual and typed-decisions checkpoints
on the fixtures in `laya-core/src/test/resources/fixtures` (tolerance 2e-3 on scores). The fixtures come from `tools/gen_fixtures.py`, a
dev-time-only Python script that is not part of any published artifact. This shows agreement on those cases, not accuracy on every question.

## Not included yet

Checkpoints using the "parallel" option layout (none are published), image input, `predict_shortlist` / `predict_tournament`,
`min_confidence` gating, email presets, streaming. Language routing is a simple heuristic, not upstream's full language detector.
The `/v1/decisions` response follows OpenAI's public docs and third-party examples; I could not read OpenAI's full reference, so
check field names against the OpenAI SDK if you depend on them.

## Modules

| Artifact | Purpose |
|---|---|
| `laya-core` | The engine (`Agent`, `Question`) |
| `laya-accelerate` | Optional macOS speed-up (needs JNA) |
| `laya-api` | Request handlers for both protocols and a dependency-free HTTP server |
| `laya-client` | JDK-only client for Laya/Jev servers |
| `laya-spring-boot-starter` | Auto-configured endpoints |
| `laya-gradle-plugin` | `layaServe` / `layaStop` |
| `laya-bom` | Aligned versions |

## Build

```bash
gradle build     # tests needing checkpoints are skipped unless LAYA_MODEL_DIR, LAYA_MODEL_DIR_MULTILINGUAL, LAYA_MODEL_DIR_TYPED are set
```

Requires JDK 17+. License: Apache-2.0 (see `LICENSE` and `NOTICE`). Releasing: see `RELEASING.md`.
