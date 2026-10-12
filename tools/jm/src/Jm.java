import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.awt.Desktop;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Jm {
    private static final Pattern MYSQL_URL =
            Pattern.compile("^jdbc:mysql://([^/:?]+)(?::(\\d+))?");
    private static final Pattern MYSQL_DATABASE_URL =
            Pattern.compile("^jdbc:mysql://([^/:?]+)(?::(\\d+))?/([^/?]+)");
    private static final Pattern HTTP_CONNECTOR =
            Pattern.compile("<Connector\\b(?=[^>]*\\bport\\s*=\\s*\"(\\d+)\")(?=[^>]*\\bprotocol\\s*=\\s*\"[^\"]*HTTP)[^>]*>",
                    Pattern.CASE_INSENSITIVE);

    private Jm() {}

    public static void main(String[] args) {
        try {
            if (args.length == 1 && "migrate".equalsIgnoreCase(args[0])) {
                Path project = findProjectRoot(Paths.get("").toAbsolutePath());
                migrateDatabase(project);
                return;
            }
            if (args.length != 2 || !"run".equalsIgnoreCase(args[0])) {
                throw new IllegalArgumentException("Usage: jm run <war-file> | jm migrate");
            }

            Path project = findProjectRoot(Paths.get("").toAbsolutePath());
            String warName = Paths.get(args[1]).getFileName().toString();
            if (!warName.toLowerCase(Locale.ROOT).endsWith(".war")) {
                throw new IllegalArgumentException("Expected a .war file name.");
            }

            ensureDatabase(project);
            runMaven(project);
            deployAndStart(project, warName);
        } catch (Exception e) {
            System.err.println("jm: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void migrateDatabase(Path project) throws Exception {
        Path config = project.resolve("src/main/resources/db.properties");
        Properties properties = new Properties();
        if (Files.isRegularFile(config)) {
            try (java.io.InputStream input = Files.newInputStream(config)) {
                properties.load(input);
            }
        }

        String jdbcUrl = System.getenv("DB_URL");
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            jdbcUrl = properties.getProperty("db.url",
                    "jdbc:mysql://localhost:3306/volunteer_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        }
        Matcher matcher = MYSQL_DATABASE_URL.matcher(jdbcUrl);
        if (!matcher.find() || !"volunteer_db".equals(matcher.group(3))) {
            throw new IllegalStateException("jm migrate currently initializes the volunteer_db schema. "
                    + "Set DB_URL/db.url to a MySQL URL ending in /volunteer_db.");
        }
        String host = matcher.group(1);
        int port = matcher.group(2) == null ? 3306 : Integer.parseInt(matcher.group(2));

        String configuredUser = System.getenv("DB_USER");
        String configuredPassword = System.getenv("DB_PASSWORD");
        if ((configuredUser == null || configuredUser.isEmpty())
                != (configuredPassword == null || configuredPassword.isEmpty())) {
            throw new IllegalStateException("Set both DB_USER and DB_PASSWORD, or unset both to let jm configure "
                    + "the local application account.");
        }
        boolean useEnvironmentCredentials = configuredUser != null && !configuredUser.isEmpty();
        String appUser = useEnvironmentCredentials
                ? configuredUser : properties.getProperty("db.user", "volunteer_app");
        String appPassword = useEnvironmentCredentials ? configuredPassword : generatePassword();
        if (!appUser.matches("[A-Za-z0-9_.-]{1,32}")) {
            throw new IllegalStateException("The configured application database user must contain only letters, "
                    + "numbers, '.', '_' or '-' and be at most 32 characters.");
        }

        ensureDatabase(project);
        Path schema = project.resolve("database").resolve("schema.sql");
        if (!Files.isRegularFile(schema)) {
            throw new IllegalStateException("Database schema was not found: " + schema);
        }

        String adminUser = System.getenv("JM_DB_ADMIN_USER");
        if (adminUser == null || adminUser.trim().isEmpty()) {
            adminUser = "root";
        }
        if (!adminUser.matches("[A-Za-z0-9_.-]{1,32}")) {
            throw new IllegalStateException("JM_DB_ADMIN_USER must contain only letters, numbers, '.', '_' or '-'.");
        }

        Path migrationScript = Files.createTempFile("jm-migrate-", ".sql");
        try {
            String sql = new String(Files.readAllBytes(schema), StandardCharsets.UTF_8)
                    + System.lineSeparator()
                    + "CREATE USER IF NOT EXISTS '" + escapeSql(appUser) + "'@'localhost' IDENTIFIED BY '"
                    + escapeSql(appPassword) + "';" + System.lineSeparator()
                    + "ALTER USER '" + escapeSql(appUser) + "'@'localhost' IDENTIFIED BY '"
                    + escapeSql(appPassword) + "';" + System.lineSeparator()
                    + "GRANT ALL PRIVILEGES ON volunteer_db.* TO '" + escapeSql(appUser) + "'@'localhost';"
                    + System.lineSeparator();
            Files.write(migrationScript, sql.getBytes(StandardCharsets.UTF_8));

            System.out.println("Applying database schema and configuring the application account...");
            System.out.println("Enter the MySQL administrator password when prompted.");
            List<String> command = new ArrayList<>();
            command.add("mysql");
            command.add("--host=" + host);
            command.add("--port=" + port);
            command.add("--user=" + adminUser);
            command.add("-p");
            int exitCode = new ProcessBuilder(command)
                    .directory(project.toFile())
                    .redirectInput(migrationScript.toFile())
                    .inheritIO()
                    .start()
                    .waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("MySQL migration failed with exit code " + exitCode
                        + ". Check the administrator account and MySQL permissions.");
            }
        } finally {
            Files.deleteIfExists(migrationScript);
        }

        if (!useEnvironmentCredentials) {
            properties.setProperty("db.url", jdbcUrl);
            properties.setProperty("db.user", appUser);
            properties.setProperty("db.password", appPassword);
            Files.createDirectories(config.getParent());
            try (java.io.OutputStream output = Files.newOutputStream(config)) {
                properties.store(output, "Local VolunteerHub database configuration");
            }
        }
        System.out.println("Database migration completed. Schema and application account are ready.");
        if (!useEnvironmentCredentials) {
            System.out.println("Local connection settings saved to " + config + ". Keep this file private.");
        } else {
            System.out.println("The application will use DB_URL, DB_USER and DB_PASSWORD from the environment.");
        }
    }

    private static String generatePassword() {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            password.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return password.toString();
    }

    private static String escapeSql(String value) {
        return value.replace("\\", "\\\\").replace("'", "''");
    }

    private static Path findProjectRoot(Path start) {
        for (Path current = start; current != null; current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
        }
        throw new IllegalStateException("Run jm from the VolunteerHub project folder or one of its subfolders.");
    }

    private static void ensureDatabase(Path project) throws Exception {
        Properties properties = new Properties();
        Path config = project.resolve("src/main/resources/db.properties");
        if (Files.isRegularFile(config)) {
            try (java.io.InputStream input = Files.newInputStream(config)) {
                properties.load(input);
            }
        }

        String jdbcUrl = System.getenv("DB_URL");
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            jdbcUrl = properties.getProperty("db.url",
                    "jdbc:mysql://localhost:3306/volunteer_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        }

        Matcher matcher = MYSQL_URL.matcher(jdbcUrl);
        if (!matcher.find()) {
            throw new IllegalStateException("Could not determine the MySQL host and port from DB_URL/db.url.");
        }
        String host = matcher.group(1);
        int port = matcher.group(2) == null ? 3306 : Integer.parseInt(matcher.group(2));
        if (isDatabaseReachable(host, port)) {
            System.out.println("MySQL is already reachable at " + host + ":" + port + ".");
            return;
        }

        if (!isLocalHost(host)) {
            throw new IllegalStateException("MySQL is not reachable at " + host + ":" + port
                    + ". Start the configured database server and try again.");
        }

        List<String> services = findMysqlServices();
        if (services.isEmpty()) {
            throw new IllegalStateException("No MySQL Windows service was found and port " + port
                    + " is not reachable. Start MySQL manually and try again.");
        }

        String serviceToStart = null;
        for (String service : services) {
            if (service.endsWith("|Running")) {
                serviceToStart = service.substring(0, service.indexOf('|'));
                break;
            }
            if (serviceToStart == null) {
                serviceToStart = service.substring(0, service.indexOf('|'));
            }
        }

        System.out.println("Starting MySQL service " + serviceToStart + "...");
        String startScript = "$ErrorActionPreference='Stop'; Start-Service -Name '"
                + serviceToStart.replace("'", "''") + "'";
        CommandResult start = capture("powershell", "-NoProfile", "-NonInteractive",
                "-Command", startScript);
        if (start.exitCode != 0) {
            throw new IllegalStateException("Could not start MySQL service " + serviceToStart
                    + ". Try running VS Code as administrator.\n" + start.output.trim());
        }

        long deadline = System.currentTimeMillis() + 45000;
        while (System.currentTimeMillis() < deadline) {
            if (isDatabaseReachable(host, port)) {
                System.out.println("MySQL is ready.");
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("MySQL service started, but " + host + ":" + port
                + " did not become reachable within 45 seconds.");
    }

    private static List<String> findMysqlServices() throws Exception {
        String script = "Get-Service | Where-Object { $_.Name -match 'mysql|maria' } "
                + "| ForEach-Object { Write-Output ($_.Name + '|' + $_.Status) }";
        CommandResult result = capture("powershell", "-NoProfile", "-NonInteractive",
                "-Command", script);
        if (result.exitCode != 0) {
            throw new IllegalStateException("Could not inspect Windows services.\n" + result.output.trim());
        }

        List<String> services = new ArrayList<>();
        for (String line : result.output.split("\\R")) {
            if (line.contains("|")) {
                services.add(line.trim());
            }
        }
        return services;
    }

    private static boolean isDatabaseReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }

    private static void runMaven(Path project) throws Exception {
        System.out.println("Building VolunteerHub with Maven...");
        int exitCode = new ProcessBuilder("cmd.exe", "/c", "mvn clean package")
                .directory(project.toFile())
                .inheritIO()
                .start()
                .waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Maven build failed with exit code " + exitCode + ".");
        }
    }

    private static void deployAndStart(Path project, String warName) throws Exception {
        Path war = project.resolve("target").resolve(warName);
        if (!Files.isRegularFile(war)) {
            throw new IllegalStateException("Build did not produce " + war + ".");
        }

        String tomcatHome = System.getenv("CATALINA_HOME");
        if (tomcatHome == null || tomcatHome.isEmpty()) {
            tomcatHome = System.getenv("TOMCAT_HOME");
        }
        Path tomcat = validTomcatHome(tomcatHome);
        if (tomcat == null) {
            if (tomcatHome != null && !tomcatHome.trim().isEmpty()) {
                System.out.println("Configured Tomcat folder is invalid: " + tomcatHome);
            }
            tomcat = promptForTomcatHome();
        }

        Path startup = tomcat.resolve("bin").resolve("startup.bat");
        Path webapps = tomcat.resolve("webapps");

        Path deployedWar = webapps.resolve(warName);
        Files.copy(war, deployedWar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Copied " + warName + " to " + webapps + ".");

        int exitCode = new ProcessBuilder("cmd.exe", "/c", "call \"" + startup + "\"")
                .directory(tomcat.toFile())
                .inheritIO()
                .start()
                .waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Tomcat startup failed with exit code " + exitCode + ".");
        }
        int port = findHttpPort(tomcat);
        String context = warName.substring(0, warName.length() - 4);
        String appUrl = "http://localhost:" + port + ("/".equals(context) ? "/" : "/" + context + "/");
        System.out.println("Waiting for VolunteerHub at " + appUrl + " ...");
        waitForApplication(appUrl);
        System.out.println("VolunteerHub is running on port " + port + ".");
        System.out.println("Application URL: " + appUrl);
        openBrowser(appUrl);
        System.out.println("If this is the first database setup, import database/schema.sql once.");
    }

    private static Path promptForTomcatHome() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("Enter the full Tomcat 9 folder path (contains bin\\startup.bat), e.g. C:\\apache-tomcat-9.0.x: ");
                System.out.flush();
                if (!scanner.hasNextLine()) {
                    throw new IllegalStateException("Tomcat folder was not provided.");
                }
                String input = scanner.nextLine().trim();
                Path tomcat = validTomcatHome(input);
                if (tomcat != null) {
                    return tomcat;
                }
                System.out.println("That folder does not contain both bin\\startup.bat and webapps. Try again.");
            }
        }
    }

    private static Path validTomcatHome(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String cleaned = value.trim();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        try {
            Path tomcat = Paths.get(cleaned).toAbsolutePath().normalize();
            if (Files.isRegularFile(tomcat.resolve("bin").resolve("startup.bat"))
                    && Files.isDirectory(tomcat.resolve("webapps"))) {
                return tomcat;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static int findHttpPort(Path tomcat) throws IOException {
        Path serverConfig = tomcat.resolve("conf").resolve("server.xml");
        if (!Files.isRegularFile(serverConfig)) {
            return 8080;
        }
        Matcher matcher = HTTP_CONNECTOR.matcher(
                new String(Files.readAllBytes(serverConfig), StandardCharsets.UTF_8));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 8080;
    }

    private static void waitForApplication(String appUrl) throws Exception {
        long deadline = System.currentTimeMillis() + 120000;
        String lastFailure = "No response received yet.";
        while (System.currentTimeMillis() < deadline) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(appUrl).toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(2000);
                connection.setInstanceFollowRedirects(true);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 400) {
                    return;
                }
                lastFailure = "HTTP " + status;
            } catch (IOException e) {
                lastFailure = e.getMessage();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("VolunteerHub did not become available within 120 seconds. Last result: "
                + lastFailure + ". "
                + "Check Tomcat logs under the configured Tomcat folder's logs directory.");
    }

    private static void openBrowser(String appUrl) throws Exception {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(appUrl));
            } else {
                new ProcessBuilder("cmd.exe", "/c", "start", "", appUrl).start();
            }
            System.out.println("Opened VolunteerHub in your default browser.");
        } catch (Exception e) {
            System.err.println("Could not open a browser automatically: " + e.getMessage());
            System.err.println("Open this URL manually: " + appUrl);
        }
    }

    private static CommandResult capture(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
            }
        }
        int exitCode = process.waitFor();
        return new CommandResult(exitCode, output.toString(StandardCharsets.UTF_8.name()));
    }

    private static final class CommandResult {
        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
