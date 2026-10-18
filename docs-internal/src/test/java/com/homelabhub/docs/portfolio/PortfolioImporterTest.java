package com.homelabhub.docs.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelabhub.docs.portfolio.PortfolioImporter.Decision;
import com.homelabhub.docs.portfolio.PortfolioImporter.Extras;
import com.homelabhub.docs.portfolio.PortfolioImporter.ImportException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class PortfolioImporterTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;
    SingleConnectionDataSource ds;
    JdbcClient jdbc;
    PortfolioImporter importer;

    @BeforeEach
    void setUp() {
        ds = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("t.db") + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000", true);
        ds.setSuppressClose(true);
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = JdbcClient.create(ds);
        importer = new PortfolioImporter(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    static final String EXPORT = """
            {"schemaVersion":1,"exportedAt":"2026-09-24T00:00:00Z","source":{"host":"h","database":"d","schema":"portfolio"},"tables":{
              "profile":[{"id":1,"name":"Tabish","title":"Data Engineer","tagline":"t","location":"Delhi","email":"e@x","phone":"1","brand":"B","status":"remote","summary":"s",
                          "bio":["p1","p2"],"socials":{"github":"https://github.com/x"},"stats":[{"value":"4+","label":"Years","sub":"x"}]}],
              "skill_groups":[{"id":10,"category":"Languages","icon":"Code","sort_order":1},{"id":11,"category":"Cloud","icon":"Cloud","sort_order":2}],
              "skills":[{"id":100,"group_id":10,"label":"Python 3","sort_order":1},{"id":101,"group_id":11,"label":"AWS S3","sort_order":1},
                        {"id":102,"group_id":11,"label":"AWS Glue (Catalog & Jobs)","sort_order":2},{"id":103,"group_id":11,"label":"AWS Secrets Manager","sort_order":3}],
              "experiences":[{"id":1,"slug":"genpact","role":"Engineer","company":"G","client":"C","duration":"2025 - now","location":"Delhi","summary":"sum","skills":["Python 3","AWS S3 / Glue","Binance API","Java"],
                              "highlights":["h1"],"sort_order":1}],
              "projects":[{"id":5,"slug":"redshift","kind":"flagship","title":"Redshift","description":"d","tags":[],"repo_url":null,"live_url":null,"type":null,"sort_order":1},
                          {"id":6,"slug":"bot","kind":"small","title":"Bot","description":"d","tags":["Python","NumPy"],"repo_url":"https://github.com/x/bot","live_url":null,"type":"Automation","sort_order":2}],
              "project_case_studies":[{"id":3,"project_id":5,"client":"GE","role":"r","duration":"2025","context":"ctx","platform":["AWS S3","AWS Glue","Secrets Manager","Mystery Tool"],
                                       "metrics":[{"label":"x","before":"a","after":"b"}],"layers":[],"tech_stack":[]}],
              "project_deliverables":[{"id":1,"project_id":5,"deliverable_key":"d1","title":"T","tag":"tag","body":"b","sort_order":1}],
              "academic_items":[{"id":1,"type":"Degree","title":"M.Tech","institution":"JMI","date":"2019 - 2023","description":null,"url":null,"sort_order":1}],
              "homelab_devices":[{"id":20,"device_key":"raspberry-pi","category":"raspberry_pi","name":"Pi","summary":"s","ip_address":"192.168.10.101","status":"Active","hardware":[{"component":"CPU","spec":"x"}],"extra":{},"sort_order":1}],
              "homelab_containers":[{"id":30,"container_key":"lxc-1","device_id":20,"name":"C","ip_address":null,"purpose":"p","resources":"2C","status":"Active","services":[],"sort_order":1}],
              "homelab_services":[{"id":40,"device_id":20,"service_name":"Grafana","container_name":"grafana","host_port":"3000","function":"metrics","sort_order":1}],
              "homelab_network_entities":[{"id":50,"device_id":20,"entity_type":"vlan","name":"VLAN 10","spec":{"subnet":"x"},"sort_order":1}]
            }}
            """;

    static final String DECISIONS = """
            owner_type,owner_slug,source_text,status,proposed_skills,candidates,decision
            experience,genpact,Python 3,exact,Python 3,,accept
            experience,genpact,AWS S3 / Glue,fuzzy,,AWS S3 (0.63),"map:AWS S3;AWS Glue (Catalog & Jobs)"
            experience,genpact,Binance API,unmatched,,,free
            experience,genpact,Java,unmatched,,,new:Java|Languages
            project,bot,Python,fuzzy,,Python 3 (0.86),map:Python 3
            project,bot,NumPy,unmatched,,,"new:NumPy|Python Ecosystem"
            """;

    private static final Extras EXTRAS = new Extras(
            Map.of("devices", Map.of("raspberry-pi", List.of("Docker / Compose", "Python 3")), "containers", Map.of("lxc-1", List.of("Python 3")),
                    "services", Map.of("Grafana", List.of("Python 3", "Nope"))),
            Map.of("AWS Glue", "AWS Glue (Catalog & Jobs)", "Secrets Manager", "AWS Secrets Manager"));

    private JsonNode export() throws Exception {
        return JSON.readTree(EXPORT);
    }

    private List<Decision> decisions() {
        return PortfolioImporter.parseDecisions(DECISIONS);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private List<String> skillsOf(String linkTable, String ownerCol, String ownerSql) {
        return jdbc.sql("SELECT s.label FROM " + linkTable + " l JOIN skills s ON s.id = l.skill_id WHERE l." + ownerCol + " = (" + ownerSql + ") ORDER BY s.label")
                .query(String.class).list();
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    void importsEverythingAndReconcilesRowCounts() throws Exception {
        var report = importer.run(export(), decisions(), EXTRAS, false, false);

        assertThat(report.imported()).containsEntry("profile", 1).containsEntry("experiences", 1).containsEntry("projects", 2).containsEntry("project_case_studies", 1)
                .containsEntry("project_deliverables", 1).containsEntry("academic_items", 1).containsEntry("homelab_devices", 1).containsEntry("homelab_containers", 1)
                .containsEntry("homelab_services", 1).containsEntry("homelab_network_entities", 1);
        assertThat(report.imported().get("skills")).isEqualTo(4);
        assertThat(count("skills")).as("4 from the export plus Java and NumPy").isEqualTo(6);
        assertThat(count("skill_groups")).as("2 exported plus 'Python Ecosystem'").isEqualTo(3);
        assertThat(report.newSkills()).isEqualTo(2);
        assertThat(report.newGroups()).isEqualTo(1);
        assertThat(report.dryRun()).isFalse();
    }

    @Test
    void freeTextSkillsAreResolvedThroughTheReviewedDecisions() throws Exception {
        importer.run(export(), decisions(), EXTRAS, false, false);
        assertThat(skillsOf("experience_skills", "experience_id", "SELECT id FROM experiences WHERE slug='genpact'"))
                .containsExactly("AWS Glue (Catalog & Jobs)", "AWS S3", "Java", "Python 3");
        assertThat(jdbc.sql("SELECT text FROM unlinked_tags WHERE owner_type='experience'").query(String.class).list()).containsExactly("Binance API");
        assertThat(skillsOf("project_skills", "project_id", "SELECT id FROM projects WHERE slug='bot'")).containsExactly("NumPy", "Python 3");
        assertThat(jdbc.sql("SELECT g.category FROM skills s JOIN skill_groups g ON g.id = s.group_id WHERE s.label = 'NumPy'").query(String.class).single()).isEqualTo("Python Ecosystem");
    }

    @Test
    void theCaseStudyPlatformListLinksTheFlagshipProjectAndReportsWhatItCouldNotMatch() throws Exception {
        var report = importer.run(export(), decisions(), EXTRAS, false, false);
        assertThat(skillsOf("project_skills", "project_id", "SELECT id FROM projects WHERE slug='redshift'"))
                .containsExactly("AWS Glue (Catalog & Jobs)", "AWS S3", "AWS Secrets Manager");
        assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("Mystery Tool"));
    }

    @Test
    void homelabItemsGetTheirMappedSkillsAndUnknownMappingsAreWarnings() throws Exception {
        var report = importer.run(export(), decisions(), EXTRAS, false, false);
        assertThat(skillsOf("device_skills", "device_id", "SELECT id FROM homelab_devices")).containsExactly("Python 3");
        assertThat(skillsOf("container_skills", "container_id", "SELECT id FROM homelab_containers")).containsExactly("Python 3");
        assertThat(skillsOf("service_skills", "service_id", "SELECT id FROM homelab_services")).containsExactly("Python 3");
        assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("Docker / Compose")).anySatisfy(w -> assertThat(w).contains("'Nope'"));
    }

    @Test
    void jsonColumnsAndRenamedColumnsSurvive() throws Exception {
        importer.run(export(), decisions(), EXTRAS, false, false);
        assertThat(jdbc.sql("SELECT bio FROM profile").query(String.class).single()).isEqualTo("[\"p1\",\"p2\"]");
        assertThat(jdbc.sql("SELECT duration_label FROM experiences").query(String.class).single()).isEqualTo("2025 - now");
        assertThat(jdbc.sql("SELECT date_label FROM academic_items").query(String.class).single()).isEqualTo("2019 - 2023");
        assertThat(jdbc.sql("SELECT spec FROM homelab_network_entities").query(String.class).single()).isEqualTo("{\"subnet\":\"x\"}");
        assertThat(jdbc.sql("SELECT repo_url FROM projects WHERE slug='redshift'").query(String.class).optional()).isEmpty();
    }

    // ---------------------------------------------------------------- safety

    @Test
    void refusesToOverwriteExistingContentUnlessToldTo() throws Exception {
        importer.run(export(), decisions(), EXTRAS, false, false);
        assertThatThrownBy(() -> importer.run(export(), decisions(), EXTRAS, false, false)).isInstanceOf(ImportException.class).hasMessageContaining("--replace");
        assertThat(count("experiences")).isEqualTo(1);
    }

    @Test
    void replaceIsIdempotentNoDuplicatesNoLeftovers() throws Exception {
        importer.run(export(), decisions(), EXTRAS, false, false);
        importer.run(export(), decisions(), EXTRAS, true, false);
        importer.run(export(), decisions(), EXTRAS, true, false);
        assertThat(count("experiences")).isEqualTo(1);
        assertThat(count("skills")).isEqualTo(6);
        assertThat(count("skill_groups")).isEqualTo(3);
        assertThat(count("experience_skills")).isEqualTo(4);
        assertThat(count("unlinked_tags")).isEqualTo(1);
        assertThat(count("homelab_services")).isEqualTo(1);
    }

    @Test
    void aDryRunReportsTheSameNumbersButSavesNothing() throws Exception {
        var report = importer.run(export(), decisions(), EXTRAS, false, true);
        assertThat(report.dryRun()).isTrue();
        assertThat(report.imported()).containsEntry("experiences", 1);
        for (String t : List.of("profile", "experiences", "projects", "skills", "skill_groups", "homelab_devices", "unlinked_tags")) {
            assertThat(count(t)).as(t).isZero();
        }
    }

    @Test
    void aStringWithoutADecisionStopsTheWholeImportAndSavesNothing() throws Exception {
        List<Decision> missing = PortfolioImporter.parseDecisions(DECISIONS.replace("experience,genpact,Java,unmatched,,,new:Java|Languages\n", ""));
        assertThatThrownBy(() -> importer.run(export(), missing, EXTRAS, false, false)).isInstanceOfSatisfying(ImportException.class, e -> {
            assertThat(e.getMessage()).contains("nothing was saved");
            assertThat(e.problems()).anySatisfy(p -> assertThat(p).contains("'Java' has no decision"));
        });
        assertThat(count("profile")).isZero();
        assertThat(count("skills")).as("the transaction rolled back completely").isZero();
    }

    @Test
    void blankAndMalformedDecisionsAreProblemsNotGuesses() throws Exception {
        List<Decision> blank = PortfolioImporter.parseDecisions(DECISIONS.replace("map:Python 3", ""));
        assertThatThrownBy(() -> importer.run(export(), blank, EXTRAS, false, false)).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.problems()).anySatisfy(p -> assertThat(p).contains("needs a decision")));
        List<Decision> badMap = PortfolioImporter.parseDecisions(DECISIONS.replace("map:Python 3", "map:No Such Skill"));
        assertThatThrownBy(() -> importer.run(export(), badMap, EXTRAS, false, false)).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.problems()).anySatisfy(p -> assertThat(p).contains("not a skill")));
        List<Decision> badNew = PortfolioImporter.parseDecisions(DECISIONS.replace("new:Java|Languages", "new:JavaWithoutGroup"));
        assertThatThrownBy(() -> importer.run(export(), badNew, EXTRAS, false, false)).isInstanceOf(ImportException.class);
        assertThat(count("skills")).isZero();
    }

    @Test
    void anExportThatIsNotVersionOneOrHasBrokenReferencesIsRefused() throws Exception {
        assertThatThrownBy(() -> importer.run(JSON.readTree("{\"schemaVersion\":2,\"tables\":{}}"), decisions(), EXTRAS, false, false)).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> importer.run(JSON.readTree("{\"hello\":1}"), decisions(), EXTRAS, false, false)).isInstanceOf(ImportException.class);
        String orphan = EXPORT.replace("\"device_id\":20,\"name\":\"C\"", "\"device_id\":999,\"name\":\"C\"");
        assertThatThrownBy(() -> importer.run(JSON.readTree(orphan), decisions(), EXTRAS, false, false)).isInstanceOfSatisfying(ImportException.class,
                e -> assertThat(e.problems()).anySatisfy(p -> assertThat(p).contains("unknown device")));
        assertThat(count("profile")).isZero();
    }

    @Test
    void aMissingProfileIsAProblem() throws Exception {
        String none = EXPORT.replaceAll("(?s)\"profile\":\\[\\{.*?\\}\\],\\s*\"skill_groups\"", "\"profile\":[],\"skill_groups\"");
        assertThatThrownBy(() -> importer.run(JSON.readTree(none), decisions(), EXTRAS, false, false)).isInstanceOf(ImportException.class);
    }

    // ---------------------------------------------------------------- the decisions file

    @Test
    void theCsvReaderHandlesQuotesCommasNewlinesAndWindowsLineEndings() {
        String csv = "owner_type,owner_slug,source_text,status,proposed_skills,candidates,decision\r\n"
                + "experience,a,\"Hello, \"\"World\"\"\",fuzzy,,\"X (0.9); Y (0.8)\",\"map:A;B\"\r\n"
                + "project,b,\"multi\nline\",unmatched,,,free\r\n\r\n";
        List<Decision> d = PortfolioImporter.parseDecisions(csv);
        assertThat(d).hasSize(2);
        assertThat(d.get(0).sourceText()).isEqualTo("Hello, \"World\"");
        assertThat(d.get(0).decision()).isEqualTo("map:A;B");
        assertThat(d.get(1).sourceText()).isEqualTo("multi\nline");
    }

    @Test
    void aDecisionsFileWithoutTheRequiredColumnsIsRefused() {
        assertThatThrownBy(() -> PortfolioImporter.parseDecisions("a,b,c\n1,2,3\n")).isInstanceOf(ImportException.class);
        assertThat(PortfolioImporter.parseDecisions("")).isEmpty();
    }

    // ---------------------------------------------------------------- the real export (only when it is on this machine)

    @Test
    void theRealExportImportsAndMatchesTheNumbersFromTheMockup() throws Exception {
        Path real = Path.of("../.scratch/export-out/portfolio-export.v1.json");
        Path dec = Path.of("../tools/export-portfolio/skill-mapping-decisions.accepted.csv");
        Path ext = Path.of("../tools/export-portfolio/import-extras.json");
        Assumptions.assumeTrue(Files.exists(real) && Files.exists(dec) && Files.exists(ext), "the real export is not on this machine");

        Extras extras = ImportCli.readExtras(JSON, Files.readString(ext));
        var report = importer.run(JSON.readTree(Files.readString(real)), PortfolioImporter.parseDecisions(Files.readString(dec)), extras, false, false);

        assertThat(count("skills")).as("48 exported + 14 new").isEqualTo(62);
        assertThat(count("skill_groups")).isEqualTo(11);
        assertThat(count("experiences")).isEqualTo(3);
        assertThat(count("projects")).isEqualTo(4);
        assertThat(count("project_deliverables")).isEqualTo(8);
        assertThat(count("academic_items")).isEqualTo(7);
        assertThat(count("homelab_devices")).isEqualTo(3);
        assertThat(count("homelab_containers")).isEqualTo(7);
        assertThat(count("homelab_services")).isEqualTo(10);
        assertThat(count("homelab_network_entities")).isEqualTo(7);
        assertThat(report.warnings()).as(report.warnings().toString()).isEmpty();
        assertThat(jdbc.sql("SELECT count(DISTINCT skill_id) FROM (SELECT skill_id FROM experience_skills UNION ALL SELECT skill_id FROM project_skills UNION ALL "
                + "SELECT skill_id FROM device_skills UNION ALL SELECT skill_id FROM container_skills UNION ALL SELECT skill_id FROM service_skills)").query(Integer.class).single())
                .as("skills with linked content, as shown in the mockup").isEqualTo(37);
    }
}
