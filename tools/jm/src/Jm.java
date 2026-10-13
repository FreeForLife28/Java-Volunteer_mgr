import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.security.MessageDigest;
import java.awt.Desktop;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Jm {
    private static final String MAVEN_VERSION = "3.10.0";
    private static final String MAVEN_SHA512 = "22d31676d5b92ed53308c19ecded3245d0efd0cddfec51ec7bab62f4f13ad70ddddceff8e62940e0e8e2421f3cde8106737f4becec0aa034ffeff2a754e88337";
    private static final String TOMCAT_VERSION = "9.0.122";
    private static final String TOMCAT_SHA512 = "5e004cf0b80dfd2465182130a11834190ab9ea98811d37c178afa1c07ddfb5a1e4defd7e52baa377088f1774bf9a2fe9006d17cc2a98af31c2a1ae25e3204a6f";
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
            if (args.length > 0 && "init".equalsIgnoreCase(args[0])) {
                if (args.length != 1) {
                    throw new IllegalArgumentException("Usage: jm init");
                }
                Path project = findProjectRoot(Paths.get("").toAbsolutePath());
                initEnvironment(project);
                return;
            }
            if (args.length > 0 && "migrate".equalsIgnoreCase(args[0])) {
                if (args.length != 2) {
                    throw new IllegalArgumentException("Usage: jm migrate <database_name>");
                }
                Path project = findProjectRoot(Paths.get("").toAbsolutePath());
                migrateDatabase(project, args[1]);
                return;
            }
            if (args.length != 2 || !"run".equalsIgnoreCase(args[0])) {
                throw new IllegalArgumentException("Usage: jm init | jm migrate <database_name> | jm run <war-file>");
            }

            Path project = findProjectRoot(Paths.get("").toAbsolutePath());
            String warName = Paths.get(args[1]).getFileName().toString();
            if (!warName.toLowerCase(Locale.ROOT).endsWith(".war")) {
                throw new IllegalArgumentException("Expected a .war file name.");
            }

            requireDatabaseConfiguration(project);
            runMaven(project);
            deployAndStart(project, warName);
        } catch (Exception e) {
            System.err.println("jm: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void migrateDatabase(Path project, String databaseName) throws Exception {
        if (databaseName == null || !databaseName.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Database name must start with a letter and contain only letters, "
                    + "numbers, or underscores (up to 64 characters).");
        }

        Path schema = project.resolve("database").resolve("schema.sql");
        if (!Files.isRegularFile(schema)) {
            throw new IllegalStateException("Database schema was not found: " + schema);
        }

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
        if (!matcher.find()) {
            throw new IllegalStateException("Could not determine the local MySQL host and port from DB_URL/db.url.");
        }
        String host = matcher.group(1);
        int port = matcher.group(2) == null ? 3306 : Integer.parseInt(matcher.group(2));
        if (!isLocalHost(host)) {
            throw new IllegalStateException("jm migrate only creates databases on a local MySQL server. "
                    + "DB_URL/db.url currently points to " + host + ".");
        }
        int queryStart = jdbcUrl.indexOf('?');
        String urlOptions = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        String effectiveJdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + databaseName + urlOptions;

        String environmentUrl = System.getenv("DB_URL");
        if (environmentUrl != null && !environmentUrl.isEmpty()) {
            Matcher environmentUrlMatcher = MYSQL_DATABASE_URL.matcher(environmentUrl);
            if (!environmentUrlMatcher.find() || !databaseName.equals(environmentUrlMatcher.group(3))) {
                throw new IllegalStateException("DB_URL is set to a different database. Set it to "
                        + effectiveJdbcUrl + " or clear DB_URL before running this command.");
            }
        }

        String configuredUser = System.getenv("DB_USER");
        String configuredPassword = System.getenv("DB_PASSWORD");
        if ((configuredUser == null || configuredUser.isEmpty())
                != (configuredPassword == null || configuredPassword.isEmpty())) {
            throw new IllegalStateException("Set both DB_USER and DB_PASSWORD, or unset both to let jm configure "
                    + "the local application account.");
        }
        boolean useEnvironmentCredentials = configuredUser != null && !configuredUser.isEmpty();
        String appUser = useEnvironmentCredentials ? configuredUser
                : properties.getProperty("db.user", "volunteer_app");
        String appPassword = useEnvironmentCredentials ? configuredPassword
                : properties.getProperty("db.password");
        if (appPassword == null || appPassword.trim().isEmpty() || "CHANGE_ME".equals(appPassword)) {
            appPassword = generatePassword();
        }
        if (!appUser.matches("[A-Za-z0-9_.-]{1,32}")) {
            throw new IllegalStateException("The configured application database user must contain only letters, "
                    + "numbers, '.', '_' or '-' and be at most 32 characters.");
        }

        String connectionUrl = "jdbc:mysql://" + host + ":" + port + "/" + databaseName + urlOptions;
        ensureDatabase(project, connectionUrl);

        String adminUser = System.getenv("JM_DB_ADMIN_USER");
        if (adminUser == null || adminUser.trim().isEmpty()) {
            adminUser = "root";
        }
        if (!adminUser.matches("[A-Za-z0-9_.-]{1,32}")) {
            throw new IllegalStateException("JM_DB_ADMIN_USER must contain only letters, numbers, '.', '_' or '-'.");
        }

        Path migrationScript = Files.createTempFile("jm-migrate-", ".sql");
        try {
            String sql = "CREATE DATABASE IF NOT EXISTS `" + databaseName + "` "
                    + "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" + System.lineSeparator()
                    + "USE `" + databaseName + "`;" + System.lineSeparator()
                    + new String(Files.readAllBytes(schema), StandardCharsets.UTF_8)
                    + System.lineSeparator()
                    + "CREATE USER IF NOT EXISTS '" + escapeSql(appUser) + "'@'localhost' IDENTIFIED BY '"
                    + escapeSql(appPassword) + "';" + System.lineSeparator()
                    + "ALTER USER '" + escapeSql(appUser) + "'@'localhost' IDENTIFIED BY '"
                    + escapeSql(appPassword) + "';" + System.lineSeparator()
                    + "GRANT ALL PRIVILEGES ON `" + databaseName + "`.* TO '"
                    + escapeSql(appUser) + "'@'localhost';"
                    + System.lineSeparator();
            Files.write(migrationScript, sql.getBytes(StandardCharsets.UTF_8));

            System.out.println("Applying database schema and configuring the application account...");
            System.out.println("Enter the MySQL administrator password when prompted.");
            List<String> command = new ArrayList<>();
            command.add(findMysqlClient());
            command.add("--host=" + host);
            command.add("--port=" + port);
            command.add("--user=" + adminUser);
            command.add("-p");
            int exitCode = new ProcessBuilder(command)
                    .directory(project.toFile())
                    .inheritIO()
                    .redirectInput(migrationScript.toFile())
                    .start()
                    .waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("MySQL migration failed with exit code " + exitCode
                        + ". Check the administrator account and MySQL permissions.");
            }
        } finally {
            Files.deleteIfExists(migrationScript);
        }

        properties.setProperty("db.url", effectiveJdbcUrl);
        properties.setProperty("db.user", appUser);
        properties.setProperty("db.password", appPassword);
        Files.createDirectories(config.getParent());
        try (java.io.OutputStream output = Files.newOutputStream(config)) {
            properties.store(output, "Local VolunteerHub database configuration");
        }
        System.out.println("Database migration completed.");
        System.out.println("Database connected successfully: " + databaseName);
        System.out.println("Connection settings saved to " + config + ". Keep this file private.");
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

    private static String findMysqlClient() throws Exception {
        String configured = System.getenv("JM_MYSQL_CLIENT");
        if (configured != null && !configured.trim().isEmpty()) {
            Path client = Paths.get(configured.trim()).toAbsolutePath().normalize();
            if (Files.isRegularFile(client)) {
                return client.toString();
            }
            throw new IllegalStateException("JM_MYSQL_CLIENT does not point to a MySQL client executable: " + client);
        }

        CommandResult onPath = capture("where.exe", "mysql.exe");
        if (onPath.exitCode == 0) {
            for (String line : onPath.output.split("\\R")) {
                Path candidate = Paths.get(line.trim());
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }

        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            Path mysqlRoot = Paths.get(programFiles, "MySQL");
            if (Files.isDirectory(mysqlRoot)) {
                try (java.util.stream.Stream<Path> installations = Files.list(mysqlRoot)) {
                    Path client = installations
                            .filter(Files::isDirectory)
                            .map(path -> path.resolve("bin").resolve("mysql.exe"))
                            .filter(Files::isRegularFile)
                            .findFirst()
                            .orElse(null);
                    if (client != null) {
                        return client.toString();
                    }
                }
            }
        }
        throw new IllegalStateException("Could not find mysql.exe. Add the MySQL Server bin folder to PATH or set "
                + "JM_MYSQL_CLIENT to the full path of mysql.exe.");
    }

    private static void initEnvironment(Path project) throws Exception {
        Path jmDirectory = project.resolve(".jm");
        Path toolsDirectory = jmDirectory.resolve("tools");
        Files.createDirectories(toolsDirectory);

        Properties settings = loadToolSettings(project);
        Path mavenCommand = findMavenCommand(settings);
        if (mavenCommand == null) {
            Path localMaven = toolsDirectory.resolve("apache-maven-" + MAVEN_VERSION);
            if (Files.isRegularFile(localMaven.resolve("bin/mvn.cmd"))) {
                mavenCommand = localMaven.resolve("bin/mvn.cmd");
            }
        }
        if (mavenCommand == null) {
            System.out.println("Maven was not found. Installing Apache Maven " + MAVEN_VERSION + " locally...");
            Path mavenHome = installArchive(
                    "https://dlcdn.apache.org/maven/maven-3/" + MAVEN_VERSION
                            + "/binaries/apache-maven-" + MAVEN_VERSION + "-bin.zip",
                    "https://downloads.apache.org/maven/maven-3/" + MAVEN_VERSION
                            + "/binaries/apache-maven-" + MAVEN_VERSION + "-bin.zip.sha512",
                    MAVEN_SHA512, toolsDirectory,
                    "apache-maven-" + MAVEN_VERSION + "/bin/mvn.cmd");
            mavenCommand = mavenHome.resolve("bin/mvn.cmd");
        } else {
            System.out.println("Using Maven at " + mavenCommand);
        }

        Path tomcatHome = findTomcatHome(settings);
        if (tomcatHome == null) {
            tomcatHome = validTomcatHome(toolsDirectory.resolve("apache-tomcat-" + TOMCAT_VERSION).toString());
        }
        if (tomcatHome == null) {
            System.out.println("Tomcat 9 was not found. Installing Apache Tomcat " + TOMCAT_VERSION + " locally...");
            tomcatHome = installArchive(
                    "https://dlcdn.apache.org/tomcat/tomcat-9/v" + TOMCAT_VERSION
                            + "/bin/apache-tomcat-" + TOMCAT_VERSION + ".zip",
                    "https://downloads.apache.org/tomcat/tomcat-9/v" + TOMCAT_VERSION
                            + "/bin/apache-tomcat-" + TOMCAT_VERSION + ".zip.sha512",
                    TOMCAT_SHA512, toolsDirectory,
                    "apache-tomcat-" + TOMCAT_VERSION + "/bin/startup.bat");
        } else {
            System.out.println("Using Tomcat 9 at " + tomcatHome);
        }

        ensurePom(project);
        settings.setProperty("maven.cmd", mavenCommand.toString());
        settings.setProperty("tomcat.home", tomcatHome.toString());
        saveToolSettings(project, settings);
        initDependencies(project, mavenCommand);
        System.out.println("Environment initialized. Next run: jm migrate <database_name>");
    }

    private static void ensurePom(Path project) throws IOException {
        Path pom = project.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            System.out.println("Using existing " + pom + ".");
            return;
        }
        Path template = project.resolve("tools/jm/templates/pom.xml");
        if (!Files.isRegularFile(template)) {
            throw new IllegalStateException("pom.xml is missing and its template was not found: " + template);
        }
        Files.copy(template, pom);
        System.out.println("Created " + pom + " from the project template.");
    }

    private static Path installArchive(String archiveUrl, String checksumUrl, String expectedSha512,
            Path toolsDirectory, String requiredRelativePath) throws Exception {
        Files.createDirectories(toolsDirectory);
        Path required = toolsDirectory.resolve(requiredRelativePath).normalize();
        Path home = required.getParent().getParent();
        if (Files.isRegularFile(required)) {
            return home;
        }

        Path downloads = toolsDirectory.resolve("downloads");
        Files.createDirectories(downloads);
        String fileName = archiveUrl.substring(archiveUrl.lastIndexOf('/') + 1);
        Path archive = downloads.resolve(fileName);
        Path checksumFile = downloads.resolve(fileName + ".sha512");
        System.out.println("Downloading " + fileName + " from Apache...");
        download(archiveUrl, archive);
        download(checksumUrl, checksumFile);
        String downloadedChecksum = readSha512(checksumFile);
        String actualChecksum = sha512(archive);
        if (!actualChecksum.equalsIgnoreCase(downloadedChecksum)
                || !actualChecksum.equalsIgnoreCase(expectedSha512)) {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(checksumFile);
            throw new IllegalStateException("SHA-512 verification failed for " + fileName + ".");
        }

        extractZip(archive, toolsDirectory);
        Files.deleteIfExists(archive);
        Files.deleteIfExists(checksumFile);
        if (!Files.isRegularFile(required)) {
            throw new IllegalStateException("Archive did not contain the expected file: " + requiredRelativePath);
        }
        return home;
    }

    private static void download(String url, Path destination) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Download failed with HTTP " + status + ": " + url);
            }
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readSha512(Path checksumFile) throws IOException {
        String value = new String(Files.readAllBytes(checksumFile), StandardCharsets.UTF_8).trim();
        Matcher matcher = Pattern.compile("(?i)^([0-9a-f]{128})").matcher(value);
        if (!matcher.find()) {
            throw new IOException("Apache checksum file did not contain a SHA-512 value.");
        }
        return matcher.group(1);
    }

    private static String sha512(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(128);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void extractZip(Path archive, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path output = root.resolve(entry.getName()).normalize();
                if (!output.startsWith(root)) {
                    throw new IOException("Archive contains an invalid path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(zip, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private static Properties loadToolSettings(Path project) throws IOException {
        Path settingsFile = project.resolve(".jm/tools.properties");
        Properties settings = new Properties();
        if (Files.isRegularFile(settingsFile)) {
            try (InputStream input = Files.newInputStream(settingsFile)) {
                settings.load(input);
            }
        }
        return settings;
    }

    private static void saveToolSettings(Path project, Properties settings) throws IOException {
        Path settingsFile = project.resolve(".jm/tools.properties");
        Files.createDirectories(settingsFile.getParent());
        try (java.io.OutputStream output = Files.newOutputStream(settingsFile)) {
            settings.store(output, "Local VolunteerHub tool locations");
        }
    }

    private static Path findMavenCommand(Properties settings) throws Exception {
        String savedCommand = settings.getProperty("maven.cmd");
        if (savedCommand != null && Files.isRegularFile(Paths.get(savedCommand))) {
            return Paths.get(savedCommand).toAbsolutePath().normalize();
        }
        List<String> configuredHomes = new ArrayList<>();
        String value = System.getenv("MAVEN_HOME");
        if (value != null && !value.trim().isEmpty()) configuredHomes.add(value);
        value = System.getenv("M2_HOME");
        if (value != null && !value.trim().isEmpty()) configuredHomes.add(value);
        value = settings.getProperty("maven.home");
        if (value != null && !value.trim().isEmpty()) configuredHomes.add(value);
        for (String configured : configuredHomes) {
            Path home = Paths.get(configured).toAbsolutePath().normalize();
            Path command = home.resolve("bin/mvn.cmd");
            if (Files.isRegularFile(command)) return command;
        }

        CommandResult onPath = capture("where.exe", "mvn.cmd");
        if (onPath.exitCode == 0) {
            for (String line : onPath.output.split("\\R")) {
                Path executable = Paths.get(line.trim());
                if (Files.isRegularFile(executable)) return executable;
            }
        }
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            Path apache = Paths.get(programFiles, "Apache");
            if (Files.isDirectory(apache)) {
                try (java.util.stream.Stream<Path> children = Files.list(apache)) {
                    Path found = children.filter(Files::isDirectory)
                            .map(path -> path.resolve("bin/mvn.cmd"))
                            .filter(Files::isRegularFile).findFirst().orElse(null);
                    if (found != null) return found;
                } catch (IOException ignored) { }
            }
        }
        return null;
    }

    private static Path findTomcatHome(Properties settings) {
        List<String> configuredHomes = new ArrayList<>();
        String value = System.getenv("CATALINA_HOME");
        if (value != null && !value.trim().isEmpty()) configuredHomes.add(value);
        value = System.getenv("TOMCAT_HOME");
        if (value != null && !value.trim().isEmpty()) configuredHomes.add(value);
        value = settings.getProperty("tomcat.home");
        if (value != null && !value.trim().isEmpty()) configuredHomes.add(value);
        for (String configured : configuredHomes) {
            Path home = validTomcatHome(configured);
            if (home != null) return home;
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path apache = Paths.get(localAppData, "Apache");
            if (Files.isDirectory(apache)) {
                try (java.util.stream.Stream<Path> children = Files.list(apache)) {
                    Path found = children.filter(Files::isDirectory)
                            .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                            .map(path -> validTomcatHome(path.toString()))
                            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
                    if (found != null) return found;
                } catch (IOException ignored) { }
            }
        }
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            Path apache = Paths.get(programFiles, "Apache Software Foundation");
            if (Files.isDirectory(apache)) {
                try (java.util.stream.Stream<Path> children = Files.list(apache)) {
                    Path found = children.filter(Files::isDirectory)
                            .map(path -> validTomcatHome(path.toString()))
                            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
                    if (found != null) return found;
                } catch (IOException ignored) { }
            }
        }
        return null;
    }

    private static void initDependencies(Path project, Path mavenCommand) throws Exception {
        System.out.println("Resolving project dependencies with Maven...");
        runMavenGoal(project, mavenCommand, "-DskipTests dependency:go-offline");
    }

    private static void runMavenGoal(Path project, Path mavenCommand, String goals) throws Exception {
        int exitCode = new ProcessBuilder("cmd.exe", "/c", "call \"" + mavenCommand + "\" -B " + goals)
                .directory(project.toFile()).inheritIO().start().waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Maven failed with exit code " + exitCode + ".");
        }
    }

    private static Path findProjectRoot(Path start) {
        for (Path current = start; current != null; current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    || Files.isRegularFile(current.resolve("tools/jm/src/Jm.java"))) {
                return current;
            }
        }
        throw new IllegalStateException("Run jm from the VolunteerHub project folder or one of its subfolders.");
    }

    private static void requireDatabaseConfiguration(Path project) {
        if (!Files.isRegularFile(project.resolve("src/main/resources/db.properties"))) {
            throw new IllegalStateException("Database is not configured. Run jm migrate <database_name> first.");
        }
    }

    private static void ensureDatabase(Path project, String jdbcUrl) throws Exception {

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
        Properties settings = loadToolSettings(project);
        Path mavenCommand = findMavenCommand(settings);
        if (mavenCommand == null) throw new IllegalStateException("Maven is missing. Run jm init first.");
        runMavenGoal(project, mavenCommand, "clean package");
    }

    private static void deployAndStart(Path project, String warName) throws Exception {
        Path war = project.resolve("target").resolve(warName);
        if (!Files.isRegularFile(war)) {
            throw new IllegalStateException("Build did not produce " + war + ".");
        }

        Path tomcat = findTomcatHome(loadToolSettings(project));
        if (tomcat == null) throw new IllegalStateException("Tomcat 9 is missing. Run jm init first.");

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
                    && Files.isDirectory(tomcat.resolve("webapps"))
                    && isTomcat9(tomcat)) {
                return tomcat;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static boolean isTomcat9(Path tomcat) {
        Path catalinaJar = tomcat.resolve("lib/catalina.jar");
        if (!Files.isRegularFile(catalinaJar)) return false;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(catalinaJar.toFile())) {
            java.util.jar.Manifest manifest = jar.getManifest();
            String version = manifest == null ? null
                    : manifest.getMainAttributes().getValue("Implementation-Version");
            return version != null && version.startsWith("9.");
        } catch (IOException e) {
            return false;
        }
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
