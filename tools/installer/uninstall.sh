#!/bin/sh
set -eu

INSTALL_DIR="$HOME/.local/share/volunteerhub/jm"
BIN_DIR="$HOME/.local/bin"
rm -f "$BIN_DIR/jm"
rm -rf "$INSTALL_DIR"

remove_path_config() {
    file=$1
    [ -f "$file" ] || return 0
    temp_file=$(mktemp "${TMPDIR:-/tmp}/jm-uninstall.XXXXXX")
    awk '
        /^# >>> VolunteerHub jm >>>$/ { removing=1; next }
        /^# <<< VolunteerHub jm <<<$/{ removing=0; next }
        !removing { print }
    ' "$file" > "$temp_file"
    cat "$temp_file" > "$file"
    rm -f "$temp_file"
}
remove_path_config "$HOME/.profile"
remove_path_config "$HOME/.bashrc"
remove_path_config "$HOME/.zshrc"

echo 'Removed the user-level jm launcher and PATH entries.'
echo 'Project-local tools under .jm were left unchanged.'
