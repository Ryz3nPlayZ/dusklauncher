#!/bin/sh
# DuskLauncher on Linux without the AppImage: the latest release's .deb,
# unpacked into your home folder and run against the system's own WebKitGTK.
# (The AppImage carries Ubuntu 22.04's WebKit/EGL libraries, which can't open
# a display on newer Mesa — Arch, Hyprland — and the window stays grey.)
#
#   curl -fsSL https://raw.githubusercontent.com/Ryz3nPlayZ/dusklauncher/main/launcher/scripts/install-linux.sh | sh
#
# Running it again updates (the launcher's UPDATE button does exactly that).
# `... | sh -s -- --uninstall` removes it; instances, accounts and settings in
# ~/.local/share/FasterLauncher are kept either way. No sudo, nothing outside
# your home folder.
set -eu

REPO=Ryz3nPlayZ/dusklauncher
DATA=${XDG_DATA_HOME:-$HOME/.local/share}
DIR=$DATA/dusklauncher
BIN=$HOME/.local/bin
DESKTOP=$DATA/applications/dusklauncher.desktop

say() { printf '%s\n' "$*"; }
die() {
    printf 'dusklauncher: %s\n' "$*" >&2
    exit 1
}

if [ "${1:-}" = "--uninstall" ]; then
    rm -rf "$DIR"
    rm -f "$BIN/dusklauncher" "$DESKTOP"
    say "DuskLauncher removed. Instances and settings are still in $DATA/FasterLauncher."
    exit 0
fi

[ "$(uname -s)" = Linux ] || die "this installer is for Linux"
[ "$(uname -m)" = x86_64 ] || die "only x86_64 Linux builds are published"
command -v curl >/dev/null 2>&1 || die "curl is needed"
command -v tar >/dev/null 2>&1 || die "tar is needed"

# ---- the system's WebKitGTK -------------------------------------------------

has_lib() {
    if command -v ldconfig >/dev/null 2>&1 && ldconfig -p 2>/dev/null | grep -q "$1"; then return 0; fi
    for d in /usr/lib /usr/lib64 /usr/lib/x86_64-linux-gnu /lib/x86_64-linux-gnu /run/current-system/sw/lib; do
        [ -e "$d/$1" ] && return 0
    done
    return 1
}

if ! has_lib libwebkit2gtk-4.1.so.0; then
    ID= ID_LIKE=
    [ -r /etc/os-release ] && . /etc/os-release
    case " $ID $ID_LIKE " in
        *" arch "*) cmd="sudo pacman -S --needed webkit2gtk-4.1" ;;
        *" debian "* | *" ubuntu "*) cmd="sudo apt install libwebkit2gtk-4.1-0" ;;
        *" fedora "* | *" rhel "*) cmd="sudo dnf install webkit2gtk4.1" ;;
        *suse*) cmd="sudo zypper install libwebkit2gtk-4_1-0" ;;
        *) cmd="" ;;
    esac
    say "DuskLauncher needs WebKitGTK 4.1 (libwebkit2gtk-4.1.so.0), which isn't installed."
    [ -n "$cmd" ] && say "Install it with:  $cmd" || say "Install your distro's webkit2gtk 4.1 package."
    say "Then run this again."
    exit 1
fi

# ---- download -----------------------------------------------------------------

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

url=$(curl -fsSL "https://github.com/$REPO/releases/latest/download/latest.json" | grep -o 'https://[^"]*_amd64\.deb' | head -n 1) ||
    die "couldn't read the latest release from GitHub"
[ -n "$url" ] || die "the latest release has no Linux package"
version=$(printf '%s' "$url" | sed 's|.*/DuskLauncher_\(.*\)_amd64\.deb|\1|')

say "Downloading DuskLauncher $version..."
curl -fL --progress-bar -o "$TMP/pkg.deb" "$url" || die "download failed"

# a .deb is an ar archive holding data.tar.*; whichever unpacker is around
mkdir "$TMP/deb" "$TMP/root"
if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$TMP/pkg.deb" "$TMP/root"
else
    if command -v bsdtar >/dev/null 2>&1; then
        bsdtar -xf "$TMP/pkg.deb" -C "$TMP/deb"
    elif command -v ar >/dev/null 2>&1; then
        (cd "$TMP/deb" && ar x ../pkg.deb)
    else
        die "unpacking needs one of dpkg-deb, bsdtar or ar (binutils)"
    fi
    tar -xf "$TMP/deb"/data.tar.* -C "$TMP/root"
fi
[ -x "$TMP/root/usr/bin/fasterlauncher" ] || die "the package didn't contain the launcher"

# ---- install ------------------------------------------------------------------
# Tauri looks for its resources (client mod jars, modpacks) in
# <program folder>/../lib/DuskLauncher, so the two stay side by side.

NEW=$DIR.new
rm -rf "$NEW"
mkdir -p "$NEW/bin" "$NEW/lib"
mv "$TMP/root/usr/bin/fasterlauncher" "$NEW/bin/fasterlauncher"
mv "$TMP/root/usr/lib/DuskLauncher" "$NEW/lib/DuskLauncher"
icon=$(ls "$TMP"/root/usr/share/icons/hicolor/128x128/apps/*.png 2>/dev/null | head -n 1)
[ -n "$icon" ] && cp "$icon" "$NEW/icon.png"
# the launcher's UPDATE button re-runs this script when it sees this file
printf '%s\n' "$version" >"$NEW/installed-by-script"

# swap folders, so a launcher that's open restarts into the new version
if [ -d "$DIR" ]; then
    rm -rf "$DIR.old"
    mv "$DIR" "$DIR.old"
fi
mv "$NEW" "$DIR"
rm -rf "$DIR.old"

mkdir -p "$BIN" "$(dirname "$DESKTOP")"
ln -sf "$DIR/bin/fasterlauncher" "$BIN/dusklauncher"
cat >"$DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=DuskLauncher
Comment=Minecraft launcher
Exec="$DIR/bin/fasterlauncher"
Icon=$DIR/icon.png
StartupWMClass=fasterlauncher
Categories=Game;
Terminal=false
EOF
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$(dirname "$DESKTOP")" >/dev/null 2>&1 || true

say "DuskLauncher $version installed. Open it from your app menu, or run: dusklauncher"
case ":$PATH:" in
    *":$BIN:"*) ;;
    *) say "($BIN isn't on your PATH, so from a terminal use $BIN/dusklauncher)" ;;
esac
