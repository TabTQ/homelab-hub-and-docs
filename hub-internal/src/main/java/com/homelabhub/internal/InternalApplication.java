package com.homelabhub.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class InternalApplication {

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("hash-password")) {
            printPasswordHash();
            return;
        }
        if (args.length > 0 && args[0].equals("import")) {
            System.err.println("The portfolio moved to Docs. Use: java -jar docs-internal.jar import ... (or migrate-portfolio --from <hub backup>).");
            System.exit(2);
            return;
        }
        if (args.length > 0 && args[0].equals("fingerprint")) {
            System.exit(com.homelabhub.internal.proxmox.FingerprintCli.run(args));
            return;
        }
        if (args.length > 0 && args[0].equals("terminal-hostkey")) {
            System.exit(com.homelabhub.internal.terminal.HostKeyCli.run(args));
            return;
        }
        if (args.length > 0 && args[0].equals("validate-config")) {
            System.exit(validateConfig(args));
            return;
        }
        // SQLite creates the database file but not its directory.
        Files.createDirectories(Path.of(System.getenv().getOrDefault("HUB_DATA_DIR", "./data")));
        SpringApplication.run(InternalApplication.class, args);
    }

    /** `java -jar hub-internal.jar hash-password` reads one line from stdin and prints the bcrypt hash for HUB_PASSWORD_HASH. */
    private static void printPasswordHash() throws IOException {
        var console = System.console();
        String pw = console != null
                ? new String(console.readPassword("New password: "))
                : new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        if (pw == null || pw.isBlank()) {
            System.err.println("No password given.");
            System.exit(1);
        }
        if (pw.length() > 200) {
            System.err.println("Password too long (max 200 characters).");
            System.exit(1);
        }
        System.out.println(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(pw));
    }

    /** `java -jar hub-internal.jar validate-config dashboard.yaml` prints every issue with its line. Exit 0 when usable. */
    private static int validateConfig(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: validate-config <file>");
            return 2;
        }
        String text = Files.readString(Path.of(args[1]));
        var result = new com.homelabhub.internal.config.ConfigParser(name -> true).parse(text);
        for (var i : result.issues()) {
            System.out.printf("%s line %d %s: %s%n", i.level(), i.line(), i.path().isEmpty() ? "(file)" : i.path(), i.message());
        }
        System.out.println(result.ok() ? "OK" : "INVALID");
        return result.ok() ? 0 : 1;
    }
}
