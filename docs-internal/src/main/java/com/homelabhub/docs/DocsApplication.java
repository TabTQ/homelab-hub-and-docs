package com.homelabhub.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class DocsApplication {

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("hash-password")) {
            printPasswordHash();
            return;
        }
        if (args.length > 0 && args[0].equals("import")) {
            System.exit(com.homelabhub.docs.portfolio.ImportCli.run(args, System.getenv().getOrDefault("DOCS_DATA_DIR", "./data")));
            return;
        }
        if (args.length > 0 && args[0].equals("migrate-portfolio")) {
            System.exit(com.homelabhub.docs.portfolio.PortfolioMigrator.run(args, System.getenv().getOrDefault("DOCS_DATA_DIR", "./data")));
            return;
        }
        // SQLite creates the database file but not its directory.
        Files.createDirectories(Path.of(System.getenv().getOrDefault("DOCS_DATA_DIR", "./data")));
        SpringApplication.run(DocsApplication.class, args);
    }

    /** `java -jar docs-internal.jar hash-password` reads one line from stdin and prints the bcrypt hash for DOCS_PASSWORD_HASH. */
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
}
