#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
INSTALL_DIR="$HOME/.local/share/volunteerhub/jm"
BIN_DIR="$HOME/.local/bin"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/volunteer-jm-install.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM

for tool in javac jar java; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "jm installer: JDK 11 or newer is required ($tool was not found on PATH)." >&2
        exit 1
    fi
done

mkdir -p "$INSTALL_DIR" "$BIN_DIR"
javac --release 11 -encoding UTF-8 -d "$TMP_DIR/classes" "$REPO_ROOT/tools/jm/src/Jm.java"
jar --create --file "$INSTALL_DIR/jm.jar" --main-class Jm -C "$TMP_DIR/classes" .
cat > "$BIN_DIR/jm" <<'LAUNCHER'
#!/bin/sh
exec java -jar "$HOME/.local/share/volunteerhub/jm/jm.jar" "$@"
LAUNCHER
chmod 755 "$BIN_DIR/jm"

add_path_config() {
    file=$1
    [ -e "$file" ] || : > "$file"
    if ! grep -Fq '# >>> VolunteerHub jm >>>' "$file"; then
        printf '\n%s\n%s\n%s\n' '# >>> VolunteerHub jm >>>' 'export PATH="$HOME/.local/bin:$PATH"' '# <<< VolunteerHub jm <<<' >> "$file"
    fi
}
add_path_config "$HOME/.profile"
add_path_config "$HOME/.bashrc"
add_path_config "$HOME/.zshrc"

echo "Installed jm in $INSTALL_DIR"
case ":$PATH:" in
    *":$BIN_DIR:"*) echo 'Open a new terminal, then run: jm init' ;;
    *) echo "Open a new terminal to load $BIN_DIR into PATH, then run: jm init" ;;
esac
