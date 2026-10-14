# Building the AAR

## With Nix (recommended)

The flake at the repo root pins the whole toolchain (JDK 17, Go, Android SDK
35, build-tools 34.0.0, NDK 27.3.13750724) and builds the AAR hermetically:

```sh
nix develop            # shell with ANDROID_HOME, NDK, JDK, Go, age
./gradlew assembleDebug
```

Inside `nix develop`, `NERUPPU_AGE_MAVEN` points Gradle at the Nix-built
AAR (`nix build .#neruppu-age`), so `build-aar.sh` is not needed. Also:

- `nix flake check` builds the AAR and runs the Go round-trip tests against
  the real `age` CLI;
- `nix run .#neruppu-decrypt -- -i key.txt capture.jpg.age`.

When `go.mod`/`go.sum` change, update `vendorHash` in `flake.nix` (set it to
`pkgs.lib.fakeHash`, build, copy the `got:` hash).

## Without Nix

The Android app depends on `org.havenapp.neruppu:neruppu-age:1.0.0`, resolved
from the repo-local Maven repository `age-encryption/maven/` (declared in
`settings.gradle.kts`). That directory is git-ignored; build it once per
checkout, before the first Gradle build:

```sh
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
./age-encryption/build-aar.sh
./gradlew assembleDebug
```

`build-aar.sh` installs gomobile at the `golang.org/x/mobile` version pinned
in `go.mod` (kept there by `tools.go`, which `gomobile bind` requires), binds
for arm64/arm/amd64 at API 26 (the app's minSdk), and writes the AAR + POM.

Why a local Maven repo and not `implementation(files("…aar"))`: AGP 8 rejects
direct `.aar` file dependencies in library modules like `:data`.

`golang.org/x/mobile` is pinned to a February 2026 commit, the last one whose
`go.mod` asks for Go 1.24 rather than 1.25+.

## Note on `go.mod`

The `go.mod` is the normal one and resolves through the module proxy.

The prototype was built and tested in a sandbox with no proxy access, using
local clones and `replace` directives:

```
replace filippo.io/age           => ../vendor-age            # v1.2.1
replace filippo.io/edwards25519  => ../vendor-edwards25519   # v1.1.0
replace golang.org/x/crypto      => ../vendor-crypto         # v0.24.0
replace golang.org/x/sys         => ../vendor-sys            # v0.21.0
replace golang.org/x/term        => ../vendor-term           # v0.21.0
```

Add them back if you need an offline build. `go test ./...` passes either way
(it shells out to the `age` binary, which must be on PATH).

## Versions, and why they differ from Cryptocam

Cryptocam pins `filippo.io/age v1.0.0-rc.1`, Go 1.16 and a gomobile from
February 2021. This module targets age **v1.2.1** and current Go. The three
API calls used — `age.Encrypt`, `age.ParseRecipients`,
`age.ParseX25519Recipient` — are unchanged across that range, so the bump is
free.

## Generated binding surface

gomobile exposes the Go package `neruppuage` as a Java/Kotlin package of the
same name, with a class `Neruppuage` holding the package-level functions:

| Go                                     | Kotlin                                              |
|----------------------------------------|-----------------------------------------------------|
| `EncryptBytes([]byte, string) ([]byte, error)` | `Neruppuage.encryptBytes(ByteArray, String): ByteArray` (throws) |
| `NewWriterToFd(int, string) (*Writer, error)`  | `Neruppuage.newWriterToFd(Long, String): Writer` (throws) |
| `IsValidRecipient(string) bool`        | `Neruppuage.isValidRecipient(String): Boolean`      |
| `(*Writer).Write([]byte) (int, error)` | `Writer.write(ByteArray): Long` (throws)            |
| `(*Writer).Close() error`              | `Writer.close()` (throws)                           |

Go `int` maps to Kotlin `Long`, hence `fd.toLong()` at the call site.

## Size

The AAR embeds the Go runtime: roughly 2–4 MB per ABI. Build with
`-target=android/arm64,android/arm` and enable ABI splits in Gradle if that
matters.

## Verifying a build

`go test ./...` covers:

- round trip through the real `age` CLI, for both the bytes and fd paths;
- short writes across an age STREAM chunk boundary (64 KiB);
- that an unfinalized stream is undecryptable (the failure mode to surface in
  the UI);
- that a private key or an ssh key is rejected as a recipient — the accident
  that would put a secret on the device;
- multi-recipient output decryptable by each key.
