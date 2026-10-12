# GitHub: files to push and first-time setup

This guide is for pushing the VolunteerHub source to GitHub and starting it after a
first-time clone on Windows.

## Files to push

Commit the application source and the files needed to build, initialize, and run it:

- `src/` — Java servlet code and frontend assets.
- `database/schema.sql` — database and table definitions.
- `pom.xml` — Maven project configuration and dependencies.
- `tools/jm/src/Jm.java`, `tools/jm/jm.cmd`, and `tools/jm/install-jm.ps1` — the
  source and installer for the local `jm` run command.
- `README.md`, `git.md`, and `.gitignore` — project documentation and Git exclusions.
- `src/main/resources/db.properties.example` — safe configuration template with no
  real database password.

Review the staged file list before every push:

```powershell
git status --short
git diff --cached --name-only
git diff --cached
```

Do **not** push:

- `src/main/resources/db.properties` — local database credentials (it is ignored by
  `.gitignore`; create it from the example on each machine).
- `.env` files, passwords, connection strings containing credentials, or private keys.
- `.vscode/` — currently ignored because its settings can contain machine-specific
  paths, such as a local Tomcat installation.
- `target/`, `.class`, `.jar`, or `.war` build output, logs, or local MySQL backups.

If a credential was ever committed, removing the file in a later commit is not enough:
rotate the credential and remove the secret from Git history before sharing the
repository.

## First-time setup after cloning

Install the prerequisites:

- JDK 11 or newer (with `javac` and `java` available in the terminal).
- Maven 3.6 or newer.
- MySQL 8, running locally on port 3306.
- Apache Tomcat 9 (Tomcat 10+ is not compatible with this app's `javax.servlet` API).

Open a terminal in the cloned project folder.

1. Install the `jm` command once:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools\jm\install-jm.ps1
   ```

   Open a new VS Code terminal after installation so the updated user PATH is loaded.

2. Initialize the local database and app account:

   ```powershell
   jm migrate
   ```

   The command starts a stopped local MySQL service when available, applies
   `database/schema.sql`, creates or updates the restricted `volunteer_app` account,
   and saves a generated password in the ignored `src/main/resources/db.properties`.
   Enter the MySQL administrator password when prompted. Set `JM_DB_ADMIN_USER` if
   the administrator login is not `root`. If both `DB_USER` and `DB_PASSWORD` are
   set, those credentials are used instead and the properties file is not changed.
   The configured `DB_URL`/`db.url` must point to `volunteer_db`.

3. Set `CATALINA_HOME` to the Tomcat 9 installation folder, or let `jm` ask for it:

   ```powershell
   $env:CATALINA_HOME = 'C:\path\to\apache-tomcat-9'
   ```

4. Build, deploy, start, and open the app:

   ```powershell
   jm run volunteer.war
   ```

   The manager runs Maven, copies the WAR to Tomcat, starts Tomcat, waits for the
   application to respond, and opens the app in the default browser. It prints the
   application URL and detected HTTP port. Tomcat's HTTP port may differ from 8080;
   use the URL printed by `jm`.

On first start, the app creates the initial administrator account documented in
`README.md`. Change its default password after signing in.
