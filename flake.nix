{
  description = "Neruppu: offline-first physical monitoring for Android, with age-encrypted captures";

  # Channel tarball rather than github:NixOS/nixpkgs: same content, and it
  # resolves without the GitHub API (which some CI/sandboxed networks block).
  inputs.nixpkgs.url = "https://channels.nixos.org/nixos-26.05/nixexprs.tar.xz";

  outputs = { self, nixpkgs }:
    let
      # androidenv ships the SDK/NDK for these hosts only.
      systems = [ "x86_64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true; # Android SDK licence
          android_sdk.accept_license = true;
        };
      }));

      # Keep in sync with app/build.gradle.kts and age-encryption/build-aar.sh.
      versions = {
        platform = "35";
        buildTools = "34.0.0";
        ndk = "27.3.13750724";
        minSdk = "26";
      };

      mkAndroid = pkgs: pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ versions.platform ];
        buildToolsVersions = [ versions.buildTools ];
        includeNDK = true;
        ndkVersions = [ versions.ndk ];
        includeEmulator = false;
        includeSystemImages = false;
        includeSources = false;
      };
    in
    {
      packages = forAllSystems (pkgs:
        let
          android = mkAndroid pkgs;
          sdkRoot = "${android.androidsdk}/libexec/android-sdk";
        in
        rec {
          # gomobile AAR, laid out as a Maven repository
          # (org/havenapp/neruppu/neruppu-age/1.0.0/…), so Gradle can use the
          # store path directly through NERUPPU_AGE_MAVEN.
          neruppu-age = pkgs.buildGoModule {
            pname = "neruppu-age";
            version = "1.0.0";

            src = pkgs.lib.cleanSourceWith {
              src = ./age-encryption;
              filter = path: _type:
                !(builtins.elem (baseNameOf path) [ "maven" ".gobin" ]);
            };

            # Module cache in proxy format: gomobile builds in its own temp
            # module and needs a GOPROXY, not a vendor/ directory.
            proxyVendor = true;
            vendorHash = "sha256-cX1YIyfYKGlsrHc13P2vjdnB3fQW1OKKybUAvV5eg+A=";
            # gomobile binds in a scratch module ("gobind") that requires the
            # whole `go list -m all` graph directly, then runs `go mod tidy`
            # there, which resolves more than this module's own build needs.
            # Replay that here so the module proxy has everything offline.
            overrideModAttrs = old: {
              preBuild = (old.preBuild or "") + ''
                src_dir=$PWD
                scratch=$TMPDIR/gobind-scratch
                mkdir -p "$scratch"
                go list -mod=mod -m -f '{{if not .Main}}{{.Path}}@{{.Version}}{{end}}' all \
                  > "$TMPDIR/gobind-mods"
                (
                  cd "$scratch"
                  go mod init gobind
                  while read -r mod; do
                    [ -n "$mod" ] && go mod edit -require="$mod"
                  done < "$TMPDIR/gobind-mods"
                  go mod edit -replace="org.havenapp.neruppu/age=$src_dir"
                  cat > main.go <<'EOF'
                package main

                import (
                	_ "golang.org/x/mobile/bind/java"
                	_ "golang.org/x/mobile/bind/seq"
                	_ "org.havenapp.neruppu/age"
                )

                func main() {}
                EOF
                  GOFLAGS=-mod=mod go mod tidy
                )
              '';
            };

            nativeBuildInputs = [ pkgs.jdk17 ];
            nativeCheckInputs = [ pkgs.age ];

            ANDROID_HOME = sdkRoot;
            ANDROID_NDK_HOME = "${sdkRoot}/ndk/${versions.ndk}";

            buildPhase = ''
              runHook preBuild
              export HOME=$TMPDIR
              # javac compiles the generated bindings, which carry Go doc comments
              export JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
              export GOBIN=$TMPDIR/bin PATH=$TMPDIR/bin:$PATH
              # gomobile/gobind at the x/mobile version pinned in go.mod
              go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
              gomobile bind \
                -target=android/arm64,android/arm,android/amd64 \
                -androidapi ${versions.minSdk} \
                -trimpath -ldflags="-s -w" \
                -o neruppu-age.aar .
              runHook postBuild
            '';

            doCheck = true;
            checkPhase = ''
              runHook preCheck
              go test ./...
              runHook postCheck
            '';

            installPhase = ''
              runHook preInstall
              dir=$out/org/havenapp/neruppu/neruppu-age/$version
              install -Dm644 neruppu-age.aar $dir/neruppu-age-$version.aar
              cat > $dir/neruppu-age-$version.pom <<EOF
              <?xml version="1.0" encoding="UTF-8"?>
              <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>org.havenapp.neruppu</groupId>
                <artifactId>neruppu-age</artifactId>
                <version>$version</version>
                <packaging>aar</packaging>
              </project>
              EOF
              runHook postInstall
            '';
          };

          neruppu-decrypt = pkgs.writeShellApplication {
            name = "neruppu-decrypt";
            runtimeInputs = [ pkgs.age pkgs.coreutils ];
            text = builtins.readFile ./tools/neruppu-decrypt;
          };

          neruppu-receiver = pkgs.writeShellApplication {
            name = "neruppu-receiver";
            runtimeInputs = [ pkgs.python3 ];
            text = ''exec python3 -I ${./tools/neruppu-receiver} "$@"'';
          };

          default = neruppu-age;
        });

      apps = forAllSystems (pkgs: {
        neruppu-decrypt = {
          type = "app";
          program = "${self.packages.${pkgs.stdenv.hostPlatform.system}.neruppu-decrypt}/bin/neruppu-decrypt";
          meta.description = "Decrypt Neruppu captures (.age) with an age identity";
        };
        neruppu-receiver = {
          type = "app";
          program = "${self.packages.${pkgs.stdenv.hostPlatform.system}.neruppu-receiver}/bin/neruppu-receiver";
          meta.description = "Minimal endpoint for the HTTPS webhook transport";
        };
      });

      checks = forAllSystems (pkgs: {
        # Builds the AAR and runs the Go round-trip tests against the age CLI.
        inherit (self.packages.${pkgs.stdenv.hostPlatform.system}) neruppu-age neruppu-decrypt neruppu-receiver;
      });

      devShells = forAllSystems (pkgs:
        let
          android = mkAndroid pkgs;
          sdkRoot = "${android.androidsdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = [
              android.androidsdk
              pkgs.jdk17
              pkgs.go
              pkgs.age
              self.packages.${pkgs.stdenv.hostPlatform.system}.neruppu-decrypt
            ];

            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;
            ANDROID_NDK_HOME = "${sdkRoot}/ndk/${versions.ndk}";
            JAVA_HOME = pkgs.jdk17.home;

            # Gradle resolves the AAR from the Nix store: no need to run
            # age-encryption/build-aar.sh inside this shell.
            NERUPPU_AGE_MAVEN = self.packages.${pkgs.stdenv.hostPlatform.system}.neruppu-age;

            # The aapt2 that AGP downloads from Maven is dynamically linked
            # against FHS paths; use the SDK's own (patched) binary instead.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${versions.buildTools}/aapt2";

            shellHook = ''
              echo "Neruppu dev shell — ./gradlew assembleDebug"
            '';
          };
        });
    };
}
