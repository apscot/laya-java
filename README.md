# laya-java

[![Maven Central](https://img.shields.io/maven-central/v/io.github.apscot/laya-core)](https://central.sonatype.com/artifact/io.github.apscot/laya-core)
[![CI](https://github.com/apscot/laya-java/actions/workflows/ci.yml/badge.svg)](https://github.com/apscot/laya-java/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**Run [Laya](https://github.com/NandhaKishorM/laya) typed-decision models in pure Java.** No Python, no PyTorch, no native model runtime to install.

Laya is a small model that answers questions about a piece of text with **probabilities** instead of generating text. It is fast, deterministic, and cheap to
run locally, which makes it a good fit for routing, triage, moderation, and scoring in applications.

| Question type | Example | Answer |
|---|---|---|
| yes/no (`noul` / `predicate`) | "Does the customer want money back?" | `0.84` |
| choice | "Which team should handle this: billing or technical?" | `billing` (92 %), `technical` (8 %) |
| score | "How frustrated is the customer? Calm / Frustrated / Very angry" | `1.09` (between "Frustrated" and "Very angry") |

laya-java gives you:

- **An in-process library** (`Agent.predict`), with no server and no HTTP.
- **A Spring Boot starter** that exposes the model over HTTP, compatible with **OpenAI's Decisions API** (`POST /v1/decisions`) and the **Jev / System One protocol** (`POST /v1/systemone`).
- **A JDK-only client** and a **Gradle plugin** for running a local server in development and tests.
- **Optional macOS acceleration** through Apple's Accelerate framework.

> laya-java is an independent port. It is not an official release of Convai Innovations or the Laya project. Model weights are not bundled; they are downloaded
> from Hugging Face on first use under their own Apache-2.0 license.

## Contents
[Install](#install) · [Use it in code](#use-it-in-code) · [Spring Boot endpoint](#spring-boot-endpoint) · [Standalone server](#standalone-server) · [Client](#client) ·
[Gradle plugin](#gradle-plugin) · [Compatibility](#compatibility) · [Performance](#performance) · [Reading the scores](#reading-the-answers) ·
[Limitations](#limitations) · [Modules](#modules) · [Building](#building-from-source) · [License](#license)

## Install

Requires **JDK 17 or newer**. Artifacts are published to Maven Central under `io.github.apscot`.

**Gradle (Kotlin DSL)**
```kotlin
dependencies {
    implementation(platform("io.github.apscot:laya-bom:0.1.0"))
    implementation("io.github.apscot:laya-core")
    runtimeOnly("io.github.apscot:laya-accelerate")        // optional: faster on macOS
}
```

**Maven**
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.apscot</groupId>
      <artifactId>laya-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>io.github.apscot</groupId>
    <artifactId>laya-core</artifactId>
  </dependency>
</dependencies>
```

For best performance start the JVM with `--add-modules=jdk.incubator.vector` (see [Performance](#performance)).

## Use it in code

```java
import io.github.apscot.laya.core.Agent;
import io.github.apscot.laya.core.Question;

try (Agent agent = Agent.load("aac6fef/laya-mlx")) {            // downloaded once to ~/.cache/laya
    Map<String, Question> questions = new LinkedHashMap<>();
    questions.put("dept", Question.choice("Which team should handle this?", List.of("billing", "technical", "sales")));
    questions.put("urgent", Question.noul("Does this convey urgency?", null));
    questions.put("frustration", Question.score("How frustrated is the customer?", List.of("Calm", "Frustrated", "Very angry")));

    Map<String, Object> result = agent.predict("Help! My payouts have been failing for 3 days.", questions);
    // result.answers.dept.choice          -> "billing"
    // result.answers.urgent.noul          -> 0.7419
    // result.answers.frustration.score    -> 1.0889
}
```

`Agent.load` accepts a Hugging Face repository id or a local directory. Checkpoints are cached in `~/.cache/laya` (override with `LAYA_CACHE`). Questions about the
same text are evaluated independently; the state can be plain text or a JSON-like map or list (such as a chat history).

## Spring Boot endpoint

Add the starter and your application serves the model over HTTP.

```kotlin
implementation(platform("io.github.apscot:laya-bom:0.1.0"))
implementation("io.github.apscot:laya-spring-boot-starter")
```

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

**The `model` field.** Laya is the model. The field exists because the OpenAI and Jev request formats include it. Use `english`, `multilingual`, or `typed-decisions`
to select a checkpoint; any other value routes automatically by the language of the text.

**API key.** None is required. Set `laya.api-key` to require `Authorization: Bearer <key>` on `/v1/*`.

| Property | Meaning | Default |
|---|---|---|
| `laya.models.<name>` | Hugging Face repo id or local directory for a model name | `english`, `multilingual`, `typed-decisions` (the published checkpoints) |
| `laya.default-model` | Model used when none is matched | `english` |
| `laya.api-key` | Bearer token required on `/v1/*` | none |
| `laya.preload` | Load the default model at startup | `false` |
| `laya.endpoint.enabled` | Expose the endpoints | `true` |
| `laya.endpoint.base-path` | Prefix for the endpoint paths | empty |

The server needs roughly 2.5 GB of memory for the English model (`-Xmx3g` is a safe setting).

## Standalone server

Without Spring, `laya-api` includes a dependency-free HTTP server (`io.github.apscot.laya.api.LayaHttpServer`) configured through environment variables:
`LAYA_PORT` (default 8000), `LAYA_HOST`, `LAYA_API_KEY`, `LAYA_MODELS`, `LAYA_PRELOAD`. It also serves `/health`.

## Client

`laya-client` is a JDK-only client for Laya or any Jev-compatible server.

```java
LayaClient client = new LayaClient("http://localhost:8080", null);       // second argument: API key, or null
Map<String, Object> r = client.systemOne("I was billed twice",
        Map.of("refund", Question.noul("Does the customer want money back?", null)));
double probability = LayaClient.noul(r, "refund");
```

## Gradle plugin

```kotlin
plugins { id("io.github.apscot.laya") version "0.1.0" }

laya {
    model.set("aac6fef/laya-mlx")
    port.set(8002)
    testIntegration.set(true)    // start a server around `test` and pass -Dlaya.base-url
}
```

Tasks: `layaServe` starts a local server and waits until it is healthy; `layaStop` stops it.

## Compatibility

- **OpenAI Decisions API**: `predicate`, `choice` and `score` questions, text input.
- **Jev / System One**: works with [`spring-ai-community/spring-ai-typesafe`](https://github.com/spring-ai-community/spring-ai-typesafe) as a local replacement for the hosted API:
  ```properties
  spring.ai.typesafe.base-url=http://localhost:8080
  spring.ai.typesafe.api-key=anything     # that client requires a non-empty key; laya-java ignores it unless laya.api-key is set
  ```
- **Checkpoints**: `aac6fef/laya-mlx` (English), `aac6fef/laya-multilingual-mlx`, `aac6fef/laya-typed-decisions-mlx` (converted from the original `convaiinnovations/laya` models).

Numerical results are tested against the Python [`laya-mlx`](https://github.com/mizorewww/laya-mlx) reference (tokenizer ids, scores and final answers) for all three
checkpoints. These tests establish agreement with the reference on a fixed set of cases, not accuracy on every possible input.

## Performance

Measured on an Apple M5 (10 cores), JDK 21, English checkpoint, warm, 31-token question:

| Backend | Median latency |
|---|---|
| `laya-accelerate` (macOS Accelerate) | ~60 ms |
| Portable Java (Vector API) | ~186 ms |
| Python / MLX on a GPU (published figure, M3 Max) | ~13 ms |

A 512-token input takes about one second with Accelerate. laya-java is slower than MLX on a GPU; its advantage is running anywhere a JVM runs.

Tips:
- Start the JVM with `--add-modules=jdk.incubator.vector`. When using the `JAVA_TOOL_OPTIONS` environment variable the flag must include the `=`. Without it, laya-java falls back to a slower scalar kernel.
- Load the model once and reuse the `Agent`; the first call includes warm-up.
- Use `laya.preload=true` in servers.

## Reading the answers

Each answer includes probabilities and two confidence values.

- **choice**: `choice` is the most likely option; `probabilities` covers every option.
- **yes/no**: `noul` (or `probability` in the OpenAI format) is P(true).
- **score**: levels are numbered `0 … n-1` in the order you give them. `score` is the expected level: Σ index × probability. For levels `Calm`, `Frustrated`, `Very angry`
  with probabilities `0.03 / 0.85 / 0.12` the score is `1.09`. Always supply levels from lowest to highest, and use the per-level probabilities when you need more than the average.
- **`answer_confidence`** is the probability of the top answer. **`confidence`** measures how peaked the distribution is (1 = certain, 0 = evenly spread); the two are
  different scales and should not share a threshold.

Probabilities are calibrated with temperature scaling taken from the checkpoint, but are not guaranteed to match real-world frequencies. Before using a threshold in
production, measure it on labelled examples from your own data.

## Limitations

- Checkpoints that use the "parallel" option layout are not supported (none are published yet).
- Image input is not supported; `/v1/decisions` returns an error for image parts.
- `predict_shortlist`, `predict_tournament`, `min_confidence` gating, email presets and streaming from the Python library are not included.
- Language routing between the English and multilingual checkpoints is a simple heuristic, not the full upstream detector.
- The `/v1/decisions` response follows OpenAI's public documentation and published examples. If your code depends on exact field names, check them against the OpenAI SDK you use.
- Like the original model, Laya is weaker on criteria that need world knowledge. Validate it on your own data.

## Modules

| Artifact | Purpose |
|---|---|
| `laya-core` | The inference engine (`Agent`, `Question`) |
| `laya-accelerate` | Optional macOS Accelerate backend (uses JNA) |
| `laya-api` | Protocol handlers for both formats and a dependency-free HTTP server |
| `laya-client` | JDK-only client |
| `laya-spring-boot-starter` | Auto-configured endpoints for Spring Boot 3 |
| `laya-gradle-plugin` | `io.github.apscot.laya` plugin |
| `laya-bom` | Aligned versions |

## Building from source

```bash
git clone https://github.com/apscot/laya-java.git
cd laya-java
gradle build
```

Tests that need model checkpoints are skipped unless `LAYA_MODEL_DIR`, `LAYA_MODEL_DIR_MULTILINGUAL` and `LAYA_MODEL_DIR_TYPED` point at local checkpoints.
See [TESTING.md](TESTING.md) for a step-by-step walkthrough and [RELEASING.md](RELEASING.md) for the release process. The Python script in `tools/` generates test
fixtures from the reference implementation; it is a development tool and is not part of any published artifact.

## License and acknowledgements

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).

laya-java builds on [Laya](https://github.com/NandhaKishorM/laya) by Convai Innovations and contributors, the [laya-mlx](https://github.com/mizorewww/laya-mlx) port
(used as the numerical reference), and the ModernBERT architecture. Thanks to the authors of all three.
