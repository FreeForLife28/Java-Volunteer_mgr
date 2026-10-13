# JM installer

These scripts install or remove the `jm` command for the current user. They do not
install a separate runtime: JDK 11 or newer (including `javac`, `jar`, and `java`)
must already be available on `PATH`.

## Windows

Double-click `install.cmd`, or run `install.ps1` from PowerShell. Open a new terminal
after installation, then run:

```text
jm init
jm migrate volunteer_db
jm run volunteer.war
```

Run these commands from the cloned repository folder.

Use `uninstall.cmd` or `uninstall.ps1` to remove the per-user launcher and PATH entry.

## macOS and Linux

From the repository root, run `sh tools/installer/install.sh`. Open a new terminal so
`~/.local/bin` is loaded from the shell profile, then run the same three commands above.
Run `sh tools/installer/uninstall.sh` to remove the user launcher and PATH entries.

`jm init` downloads Maven and Tomcat into the project `.jm/tools` directory when they
are not already available. `jm run` checks MySQL before building and starts a stopped
local service when supported: Windows services, Homebrew services on macOS, and systemd
on Linux. Linux may prompt for administrator credentials. Install MySQL 8 and its client
separately.
