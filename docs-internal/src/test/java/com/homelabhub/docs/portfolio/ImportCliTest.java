package com.homelabhub.docs.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportCliTest {

    @TempDir Path dir;

    private record Out(int code, String out, String err) {}

    private Out run(String... args) {
        PrintStream so = System.out, se = System.err;
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        System.setOut(new PrintStream(o));
        System.setErr(new PrintStream(e));
        try {
            int code = ImportCli.run(args, dir.resolve("data").toString());
            return new Out(code, o.toString(), e.toString());
        } finally {
            System.setOut(so);
            System.setErr(se);
        }
    }

    private Path files() throws Exception {
        Files.writeString(dir.resolve("export.json"), PortfolioImporterTest.EXPORT);
        Files.writeString(dir.resolve("decisions.csv"), PortfolioImporterTest.DECISIONS);
        return dir;
    }

    private int countSkills() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("data").resolve("docs.db"));
             var rs = c.createStatement().executeQuery("SELECT count(*) FROM skills")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void importsFromFilesCreatingAndMigratingTheDatabase() throws Exception {
        files();
        Out r = run("import", "--export", dir.resolve("export.json").toString(), "--decisions", dir.resolve("decisions.csv").toString());
        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).contains("Import complete").contains("experiences").contains("New skills: 2");
        assertThat(countSkills()).isEqualTo(6);
    }

    @Test
    void aSecondRunWithoutReplaceFailsWithAClearMessageAndExitCode1() throws Exception {
        files();
        String[] args = {"import", "--export", dir.resolve("export.json").toString(), "--decisions", dir.resolve("decisions.csv").toString()};
        assertThat(run(args).code()).isZero();
        Out again = run(args);
        assertThat(again.code()).isEqualTo(1);
        assertThat(again.err()).contains("--replace");
        assertThat(countSkills()).isEqualTo(6);
    }

    @Test
    void dryRunSavesNothing() throws Exception {
        files();
        Out r = run("import", "--export", dir.resolve("export.json").toString(), "--decisions", dir.resolve("decisions.csv").toString(), "--dry-run");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("DRY RUN");
        assertThat(countSkills()).isZero();
    }

    @Test
    void problemsArePrintedOneByOneAndTheDatabaseStaysEmpty() throws Exception {
        Files.writeString(dir.resolve("export.json"), PortfolioImporterTest.EXPORT);
        Files.writeString(dir.resolve("decisions.csv"), "owner_type,owner_slug,source_text,status,proposed_skills,candidates,decision\n");
        Out r = run("import", "--export", dir.resolve("export.json").toString(), "--decisions", dir.resolve("decisions.csv").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("nothing was saved").contains("has no decision");
        assertThat(countSkills()).isZero();
    }

    @Test
    void badArgumentsAndMissingFilesAreReportedNotStackTraces() {
        assertThat(run("import").code()).isEqualTo(2);
        assertThat(run("import", "--bogus").code()).isEqualTo(2);
        Out missing = run("import", "--export", "/no/such/file.json", "--decisions", "/no/such.csv");
        assertThat(missing.code()).isEqualTo(1);
        assertThat(missing.err()).startsWith("ERROR:").doesNotContain("\tat ");
    }
}
