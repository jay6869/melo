#!/usr/bin/env bash
# Builds melo with plain javac and installs it to ~/.local — no Gradle, no root.
# Dependencies (~3.6 MB) are downloaded with resume + retries and checked against pinned SHA-256s,
# so slow or flaky connections are fine.
set -euo pipefail
cd "$(dirname "$0")"

MAVEN=https://repo1.maven.org/maven2
JITPACK=https://jitpack.io
NANOJSON=e9d656ddb49a412a5a0a5d5ef20ca7ef09549996
DEPS=(
    "$JITPACK/com/github/TeamNewPipe/NewPipeExtractor/v0.26.5/NewPipeExtractor-v0.26.5.jar 923bf0a60938d570ad176ed7b08fa9fec7d0772d7a386ca89a2dd3b9212979c7"
    "$JITPACK/com/github/TeamNewPipe/nanojson/$NANOJSON/nanojson-$NANOJSON.jar d495881a322b3c72e5d43284afd7d7021520028ca8b7c6ba861e3fd8d90614d5"
    "$MAVEN/org/jsoup/jsoup/1.22.2/jsoup-1.22.2.jar 596785996d3c6df16f544c232d10a9a1d88783b2a4740cf5251cb616f2be704d"
    "$MAVEN/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar 766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7"
    "$MAVEN/com/google/protobuf/protobuf-javalite/4.35.1/protobuf-javalite-4.35.1.jar 45d3769189888e491ab7d58125f2014c2a86fb8104c7aa3878c723943fce7a14"
    "$MAVEN/org/mozilla/rhino/1.8.1/rhino-1.8.1.jar 98a06d42bdd69da15bf9c2a7812b4cfe648ca9e64fe8baaeb502a32b0ed2c8ac"
    "$MAVEN/org/mozilla/rhino-engine/1.8.1/rhino-engine-1.8.1.jar ff38ad60aa32a23942f60242a433a2583a5407841745510754a43e0e61dd95cf"
)

say() { printf '\033[1;35m♫\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m✕\033[0m %s\n' "$*" >&2; exit 1; }

# ── requirements ──────────────────────────────────────────────────────────────
command -v javac >/dev/null || die "needs a JDK 21+ (Arch: sudo pacman -S jdk-openjdk)"
command -v ffmpeg >/dev/null || die "needs ffmpeg (Arch: sudo pacman -S ffmpeg)"
command -v curl >/dev/null || die "needs curl"
jv=$(javac -version 2>&1 | grep -oE '[0-9]+' | head -1)
(( jv >= 21 )) || die "needs JDK 21 or newer (found $jv)"

# ── dependencies ──────────────────────────────────────────────────────────────
mkdir -p lib
for entry in "${DEPS[@]}"; do
    url=${entry% *} sum=${entry##* } file=lib/$(basename "${entry% *}")
    if [[ -f $file ]] && echo "$sum  $file" | sha256sum -c --status; then continue; fi
    say "downloading $(basename "$file")"
    ok=0
    for attempt in 1 2 3 4 5; do
        # -C - resumes partial files; give up on a stalled connection after 60 s and retry
        if curl -fL --progress-bar -C - --connect-timeout 20 --speed-limit 512 --speed-time 60 \
                --retry 3 --retry-delay 3 --retry-all-errors -o "$file" "$url" \
           && echo "$sum  $file" | sha256sum -c --status; then
            ok=1; break
        fi
        # a corrupt partial can't be resumed: start that file over
        echo "$sum  $file" | sha256sum -c --status 2>/dev/null || { [[ $attempt -ge 2 ]] && rm -f "$file"; }
        say "retrying ($attempt/5)…"
        sleep 2
    done
    (( ok )) || die "couldn't download $url — check your connection and run ./install.sh again (it resumes)"
done

# ── build ─────────────────────────────────────────────────────────────────────
say "compiling"
rm -rf build/classes && mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
javac --release 21 -encoding UTF-8 -nowarn -XDsuppressNotes -cp "lib/*" -d build/classes @build/sources.txt 2>&1 \
    | grep -vE 'internal proprietary API|sun\.misc|^\s*\^|^[0-9]+ warnings?$' || true
[[ -f build/classes/melo/Main.class ]] || die "compilation failed"
jar --create --file build/melo.jar --main-class melo.Main -C build/classes .

# ── install ───────────────────────────────────────────────────────────────────
dest="${XDG_DATA_HOME:-$HOME/.local/share}/melo/app"
rm -rf "$dest"
mkdir -p "$dest/lib" "$dest/bin" "$HOME/.local/bin"
cp build/melo.jar lib/*.jar "$dest/lib/"
cat > "$dest/bin/melo" <<'EOF'
#!/bin/sh
here="$(dirname "$(readlink -f "$0")")/.."
opts="-XX:+UseSerialGC -Xmx384m -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"
# protobuf-javalite still uses sun.misc.Unsafe; silence the JDK 24+ deprecation notice
v=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p')
[ "${v:-0}" -ge 24 ] 2>/dev/null && opts="$opts --sun-misc-unsafe-memory-access=allow"
exec java $opts -cp "$here/lib/*" melo.Main "$@"
EOF
chmod +x "$dest/bin/melo"
ln -sf "$dest/bin/melo" "$HOME/.local/bin/melo"

say "installed → ~/.local/bin/melo"
[[ ":$PATH:" == *":$HOME/.local/bin:"* ]] || echo "  add ~/.local/bin to your PATH, e.g.:  echo 'export PATH=\$HOME/.local/bin:\$PATH' >> ~/.bashrc"
