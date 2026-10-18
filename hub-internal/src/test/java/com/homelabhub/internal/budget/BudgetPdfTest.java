package com.homelabhub.internal.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homelabhub.internal.budget.BalanceCalculator.Statement;
import com.homelabhub.internal.budget.BalanceCalculator.Totals;
import com.homelabhub.internal.budget.BudgetModels.Expense;
import com.homelabhub.internal.budget.BudgetModels.PersonRef;
import com.homelabhub.internal.budget.BudgetModels.Settlement;
import com.homelabhub.internal.budget.BudgetModels.Share;
import com.homelabhub.internal.budget.BudgetModels.StatementView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** The PDF export (plan 4.2): glyphs, figures, pagination and awkward input. */
class BudgetPdfTest {

    static final List<PersonRef> PEOPLE = List.of(new PersonRef(1, "Anu", false), new PersonRef(2, "Ravi", false), new PersonRef(3, "Meera", false));

    /** Builds a view from expenses (and settlements) the same way the ledger does. */
    static StatementView view(LocalDate from, LocalDate to, Map<Long, Totals> before, List<Expense> expenses, List<Settlement> settlements) {
        Map<Long, long[]> t = new HashMap<>();
        for (Expense e : expenses) {
            t.computeIfAbsent(e.paidBy(), k -> new long[4])[0] += e.amountPaise();
            for (Share s : e.shares()) {
                t.computeIfAbsent(s.personId(), k -> new long[4])[1] += s.paise();
            }
        }
        for (Settlement s : settlements) {
            t.computeIfAbsent(s.fromId(), k -> new long[4])[2] += s.paise();
            t.computeIfAbsent(s.toId(), k -> new long[4])[3] += s.paise();
        }
        Map<Long, Totals> in = new HashMap<>();
        t.forEach((k, v) -> in.put(k, new Totals(v[0], v[1], v[2], v[3])));
        Map<Long, Boolean> people = new LinkedHashMap<>();
        PEOPLE.forEach(p -> people.put(p.id(), p.archived()));
        long total = expenses.stream().mapToLong(Expense::amountPaise).sum();
        Statement s = BalanceCalculator.statement(people, before, in, expenses.size(), total, !settlements.isEmpty());
        return new StatementView(from.toString(), to.toString(), PEOPLE, s, expenses, settlements);
    }

    static Expense exp(long id, String name, long amount, long paidBy, String date, boolean daySet, List<Long> split) {
        List<Share> shares = new ArrayList<>();
        Splitter.split(amount, split).forEach((p, v) -> shares.add(new Share(p, v)));
        return new Expense(id, name, amount, paidBy, date, daySet, null, null, 0, shares);
    }

    static StatementView september(List<Settlement> settlements) {
        List<Long> all = List.of(1L, 2L, 3L);
        List<Expense> ex = List.of(
                exp(1, "Rent", 3000000, 1, "2026-09-01", true, all),
                exp(2, "Internet", 119900, 2, "2026-09-05", true, all),
                exp(3, "Groceries", 240000, 2, "2026-09-12", true, all),
                exp(4, "Electricity bill", 185050, 3, "2026-09-15", false, all),
                exp(5, "Dinner out", 360000, 3, "2026-09-20", true, List.of(1L, 3L)));
        Map<Long, Totals> before = Map.of(2L, new Totals(0, 30000, 0, 0), 3L, new Totals(60000, 30000, 0, 0));
        return view(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), before, ex, settlements);
    }

    static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void bothFontsHaveEverySymbolTheTemplatesUse() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (String font : List.of(BudgetPdf.REGULAR, BudgetPdf.BOLD)) {
                PDType0Font f = BudgetPdf.load(doc, font);
                String all = BudgetPdf.SYMBOLS + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,:;()+-#?'\"/&";
                all.codePoints().forEach(cp -> assertThat(BudgetPdf.canEncode(f, cp)).as("%s U+%04X", font, cp).isTrue());
            }
        }
    }

    @Test
    void theSeptemberPdfShowsTheReferenceFigures() throws Exception {
        byte[] pdf = BudgetPdf.render(september(List.of()), LocalDate.parse("2026-09-30"));
        String t = text(pdf);
        assertThat(t).contains("Budget summary · September 2026", "Period 1 Sep 2026 – 30 Sep 2026 · Generated 30 Sep 2026 · All amounts in ₹",
                "Ravi pays Anu", "₹8,517.50", "Meera pays Anu", "₹7,865.99", "Ravi owes Meera ₹300.00", "+16,383.49", "−8,517.50",
                "Sep (no day)", "01 Sep", "Anu, Ravi, Meera", "39,049.50", "None in this period.", "Page 1 of 1", "Homelab Hub · Budget");
        assertThat(t).doesNotContain("Settled", "?");
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "budget-2026-09-sample.pdf"), pdf); // kept for a visual check
    }

    @Test
    void settlementsAddTheSettledColumnAndTheirOwnSection() throws Exception {
        String t = text(BudgetPdf.render(september(List.of(new Settlement(1, 2, 1, 500000, "2026-09-25", "UPI"))), LocalDate.parse("2026-09-30")));
        assertThat(t).contains("Settled", "25 Sep 2026", "Ravi paid Anu", "5,000.00", "UPI", "₹3,517.50");
        assertThat(t).doesNotContain("None in this period.");
    }

    @Test
    void nobodyOwesAnything() throws Exception {
        String t = text(BudgetPdf.render(view(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), Map.of(), List.of(), List.of()),
                LocalDate.parse("2026-10-02")));
        assertThat(t).contains("Nobody owes anything.", "No expenses in this period.", "Budget summary · October 2026");
    }

    @Test
    void aLongRangePaginatesAndRepeatsTheTableHeader() throws Exception {
        List<Expense> ex = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            ex.add(exp(i + 1, "A rather long payment name number " + i + " that goes on and on to force a wrap in the column",
                    1000 + i, 1 + i % 3, LocalDate.parse("2025-08-10").plusDays(i).toString(), i % 5 != 0, List.of(1L, 2L, 3L)));
        }
        long t0 = System.nanoTime();
        byte[] pdf = BudgetPdf.render(view(LocalDate.parse("2025-08-10"), LocalDate.parse("2026-09-20"), Map.of(), ex, List.of()), LocalDate.parse("2026-09-26"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = doc.getNumberOfPages();
            assertThat(pages).isGreaterThan(5);
            String t = new PDFTextStripper().getText(doc);
            assertThat(t).contains("Page 1 of " + pages, "Page " + pages + " of " + pages, "10 Aug 2025 – 20 Sep 2026");
            // the Expenses header ("Split among") appears on every page that holds expense rows
            int headers = t.split("Split among", -1).length - 1;
            assertThat(headers).isGreaterThanOrEqualTo(pages - 1);
            assertThat(t).contains("Aug 2025 (no day)"); // ranges over two years show the year
        }
        assertThat(ms).as("300 expenses took %d ms", ms).isLessThan(10_000);
        assertThat(pdf.length).isLessThan(2_000_000);
    }

    @Test
    void charactersTheFontCannotDrawBecomeQuestionMarks() throws Exception {
        List<Expense> ex = List.of(exp(1, "Pizza 🍕 party\u0007 中", 1000, 1, "2026-09-02", true, List.of(1L)));
        String t = text(BudgetPdf.render(view(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), Map.of(), ex, List.of()), LocalDate.parse("2026-09-30")));
        assertThat(t).contains("Pizza ? party");
    }

    /**
     * PDFBox depends on commons-logging. With that jar on the classpath spring-jcl prints a warning to stdout, which
     * corrupted `java -jar hub-internal.jar hash-password` (its first output line must be the hash). The pom excludes it.
     */
    @Test
    void pdfboxDoesNotBringTheCommonsLoggingJar() {
        assertThatThrownBy(() -> Class.forName("org.apache.commons.logging.impl.LogFactoryImpl")).isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void filenames() {
        assertThat(BudgetPdf.filename(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))).isEqualTo("budget-2026-09.pdf");
        assertThat(BudgetPdf.filename(LocalDate.parse("2028-02-01"), LocalDate.parse("2028-02-29"))).isEqualTo("budget-2028-02.pdf");
        assertThat(BudgetPdf.filename(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-29"))).isEqualTo("budget-2026-09-01_to_2026-09-29.pdf");
    }
}
