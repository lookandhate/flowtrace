# Flow Trace for Go

<!-- Plugin description -->
Flow Trace shows where a Go value comes from and where it goes, across function and constructor boundaries.

Right-click a variable, parameter, method receiver, or struct field in a Go file and choose:

- **Trace Value Origins** — the topmost sources of the value (literals, composite literals, constructors, calls into libraries) and the path that brings it to the variable: call arguments at every call site, assignments, struct fields, `return` statements, and calls through interfaces.
- **Trace Value Destinations** — the parameters, constructors, struct fields, and method receivers the value passes through, and the places where it is finally used.

Results open in the Flow Trace tool window as a tree and a layered graph. Double-click any node to open the code.

The analysis covers the code of the current project. Calls into the standard library and module dependencies are shown as external sources or end points.
<!-- Plugin description end -->

Shortcuts: `Ctrl+Alt+Shift+O` (origins) and `Ctrl+Alt+Shift+U` (destinations). The UI is in English; with the Russian language pack it is shown in Russian.

## Limitations

- The analysis does not distinguish instances: a struct field is one node for all objects, and a call through an interface leads to every implementation in the project.
- Libraries (standard library, `go.mod` dependencies, repositories pulled in with `replace` unless opened as part of the project) are not entered.
- Positional struct literals (`Store{cfg}`), parameters of anonymous functions, and values passed through channels, maps, and slices are not traced.
- A trace stops at 400 nodes or depth 40; truncated nodes are marked.

## Building

The plugin targets GoLand 2026.3, which requires Java 25. The JBR bundled with GoLand works:

```
export JAVA_HOME=~/Applications/GoLand.app/Contents/jbr/Contents/Home
./gradlew test buildPlugin verifyPlugin
```

If no JDK 25 is installed, Gradle provisions one through the foojay resolver, but `./gradlew` itself still needs some Java in `JAVA_HOME`.

By default the build downloads GoLand `platformVersion` from `gradle.properties`. To build against an installed GoLand instead (faster, matches your exact build), set the path in `~/.gradle/gradle.properties`, which is outside the repository:

```
goLandPath=/Users/<you>/Applications/GoLand.app
```

The archive is `build/distributions/flowtrace-<version>.zip`. Install it with Settings → Plugins → ⚙ → Install Plugin from Disk.

## Publishing

Signing and publishing read their secrets from environment variables; nothing secret is stored in the repository.

| Variable | Meaning |
| --- | --- |
| `CERTIFICATE_CHAIN` | PEM certificate chain (`chain.crt`) |
| `PRIVATE_KEY` | PEM private key (`private.pem`) |
| `PRIVATE_KEY_PASSWORD` | password of the private key |
| `PUBLISH_TOKEN` | JetBrains Marketplace token from https://plugins.jetbrains.com/author/me/tokens |

Create a key and a certificate once:

```
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

Then:

```
export CERTIFICATE_CHAIN="$(cat chain.crt)" PRIVATE_KEY="$(cat private.pem)" PRIVATE_KEY_PASSWORD=...
./gradlew signPlugin verifyPluginSignature   # signed archive: build/distributions/flowtrace-<version>-signed.zip
PUBLISH_TOKEN=... ./gradlew publishPlugin     # only after the first version was uploaded through the website
```

A version with a pre-release suffix (`0.4.0-beta.1`) is published to the matching channel (`beta`); a plain version goes to the default channel.

## License

[MIT](LICENSE)
