package com.homelabhub.internal.budget;

import com.homelabhub.internal.budget.BalanceCalculator.Row;
import com.homelabhub.internal.budget.BalanceCalculator.Statement;
import com.homelabhub.internal.budget.BudgetModels.Expense;
import com.homelabhub.internal.budget.BudgetModels.PersonRef;
import com.homelabhub.internal.budget.BudgetModels.Settlement;
import com.homelabhub.internal.budget.BudgetModels.Share;
import com.homelabhub.internal.budget.BudgetModels.StatementView;
import com.homelabhub.internal.budget.TransferPlanner.Transfer;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The budget statement as an A4 PDF (plan 5.5), drawn with Apache PDFBox and an embedded Noto Sans (the standard PDF
 * fonts have no ₹). Built from the same {@link StatementView} the API returns, so the PDF can never disagree with the
 * screen. Created per request and never written to disk.
 */
public final class BudgetPdf {

    static final String REGULAR = "/fonts/NotoSans-Regular.ttf";
    static final String BOLD = "/fonts/NotoSans-Bold.ttf";
    /**
     * Every non-ASCII character the templates use; a test checks both fonts have them. Noto Sans has no arrow (U+2192),
     * so settlements read "Ravi paid Anu" rather than "Ravi → Anu".
     */
    static final String SYMBOLS = "₹−·–";

    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_YEAR = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MON = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);
    private static final DateTimeFormatter MON_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private static final float MARGIN = 40;
    private static final float BODY = 9.5f;
    private static final float LINE = 13.5f;
    private static final Color GREY = new Color(0x55, 0x55, 0x55);
    private static final Color LIGHT = new Color(0xdd, 0xdd, 0xdd);
    private static final Color MID = new Color(0x99, 0x99, 0x99);

    private BudgetPdf() {}

    /** Is [from, to] exactly one calendar month? */
    static boolean isWholeMonth(LocalDate from, LocalDate to) {
        return from.getDayOfMonth() == 1 && to.equals(YearMonth.from(from).atEndOfMonth());
    }

    /** {@code budget-2026-09.pdf} for a whole month, {@code budget-2026-08-10_to_2026-09-20.pdf} otherwise. Never user text. */
    public static String filename(LocalDate from, LocalDate to) {
        return isWholeMonth(from, to) ? "budget-" + YearMonth.from(from) + ".pdf" : "budget-" + from + "_to_" + to + ".pdf";
    }

    public static byte[] render(StatementView view, LocalDate generated) {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font regular = load(doc, REGULAR);
            PDType0Font bold = load(doc, BOLD);
            new Writer(doc, regular, bold, view, generated).write();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static PDType0Font load(PDDocument doc, String resource) throws IOException {
        try (InputStream in = BudgetPdf.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Font " + resource + " is missing from the jar");
            }
            return PDType0Font.load(doc, in);
        }
    }

    /** Whether the font can draw this code point (PDType0Font throws on a missing glyph rather than drawing a box). */
    static boolean canEncode(PDType0Font font, int codePoint) {
        try {
            font.encode(new String(Character.toChars(codePoint)));
            return true;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- layout

    private record Col(String title, float width, boolean right) {}

    private static final class Writer {
        private final PDDocument doc;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final StatementView view;
        private final LocalDate generated;
        private final Map<Long, String> names = new HashMap<>();
        private final Map<Integer, Boolean> encodable = new HashMap<>();
        private final LocalDate from;
        private final LocalDate to;
        private final float width;
        private PDPageContentStream cs;
        private float y;

        Writer(PDDocument doc, PDType0Font regular, PDType0Font bold, StatementView view, LocalDate generated) {
            this.doc = doc;
            this.regular = regular;
            this.bold = bold;
            this.view = view;
            this.generated = generated;
            this.from = LocalDate.parse(view.from());
            this.to = LocalDate.parse(view.to());
            this.width = PDRectangle.A4.getWidth() - 2 * MARGIN;
            for (PersonRef p : view.people()) {
                names.put(p.id(), p.name());
            }
        }

        void write() throws IOException {
            String title = isWholeMonth(from, to) ? "Budget summary · " + MONTH_TITLE.format(from)
                    : "Budget summary · " + LONG.format(from) + " – " + LONG.format(to);
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(clean(title));
            info.setCreator("Homelab Hub");
            newPage();
            text(bold, 17, MARGIN, y, title, Color.BLACK);
            y -= 18;
            text(regular, BODY, MARGIN, y, "Period " + LONG.format(from) + " – " + LONG.format(to) + " · Generated "
                    + LONG.format(generated) + " · All amounts in ₹", GREY);
            y -= 10;
            Statement s = view.statement();

            heading("Who pays whom");
            if (s.transfers().isEmpty()) {
                paragraph("Nobody owes anything.", GREY);
            }
            for (Transfer t : s.transfers()) {
                need(LINE + 4);
                y -= LINE;
                runs(MARGIN, y, new Object[] {bold, name(t.fromId()), regular, " pays ", bold, name(t.toId())});
                String amt = Money.format(t.paise());
                text(bold, BODY, MARGIN + width - w(bold, BODY, amt), y, amt, Color.BLACK);
                y -= 5;
                rule(LIGHT, 0.5f);
            }
            if (!s.openingTransfers().isEmpty()) {
                String owed = s.openingTransfers().stream()
                        .map(t -> name(t.fromId()) + " owes " + name(t.toId()) + " " + Money.format(t.paise()))
                        .collect(Collectors.joining("; "));
                y -= 3;
                paragraph("Carried in from before this period: " + owed + ".", GREY);
            }

            heading("People");
            List<Col> cols = new ArrayList<>(List.of(new Col("Person", 0, false), new Col("Paid", 70, true), new Col("Share", 70, true),
                    new Col("This period", 70, true), new Col("Carried in", 70, true)));
            if (s.hasSettlements()) {
                cols.add(new Col("Settled", 62, true));
            }
            cols.add(new Col("To settle", 72, true));
            List<Col> people = flex(cols);
            header(people);
            for (Row r : s.rows()) {
                List<String> cells = new ArrayList<>(List.of(name(r.personId()), Money.plainSigned(r.paidPaise()), Money.plainSigned(r.sharePaise()),
                        Money.withSign(r.periodNetPaise()), Money.withSign(r.carriedInPaise())));
                if (s.hasSettlements()) {
                    cells.add(Money.withSign(r.settledPaise()));
                }
                cells.add(Money.withSign(r.toSettlePaise()));
                row(people, cells, false);
            }
            List<String> total = new ArrayList<>(List.of("Total", Money.plainSigned(s.totals().paidPaise()), Money.plainSigned(s.totals().sharePaise()),
                    Money.withSign(s.totals().periodNetPaise()), Money.withSign(s.totals().carriedInPaise())));
            if (s.hasSettlements()) {
                total.add(Money.withSign(s.totals().settledPaise()));
            }
            total.add(Money.withSign(s.totals().toSettlePaise()));
            row(people, total, true);

            heading("Expenses");
            float dateWidth = from.getYear() != to.getYear() ? 100 : 78;
            List<Col> ex = flex(List.of(new Col("Date", dateWidth, false), new Col("Payment", 0, false), new Col("Paid by", 80, false),
                    new Col("Split among", 150, false), new Col("Amount", 72, true)));
            if (view.expenses().isEmpty()) {
                paragraph("No expenses in this period.", GREY);
            } else {
                header(ex);
                for (Expense e : view.expenses()) {
                    row(ex, List.of(when(e), e.name(), name(e.paidBy()), split(e), Money.plainSigned(e.amountPaise())), false);
                }
                row(ex, List.of("Total", "", "", s.expenseCount() + (s.expenseCount() == 1 ? " expense" : " expenses"),
                        Money.plainSigned(s.expenseTotalPaise())), true);
            }

            heading("Payments marked as settled");
            if (view.settlements().isEmpty()) {
                paragraph("None in this period.", GREY);
            } else {
                List<Col> st = flex(List.of(new Col("Date", dateWidth, false), new Col("Who", 170, false), new Col("Amount", 72, true), new Col("Note", 0, false)));
                header(st);
                for (Settlement x : view.settlements()) {
                    row(st, List.of(DAY_YEAR.format(LocalDate.parse(x.date())), name(x.fromId()) + " paid " + name(x.toId()),
                            Money.plainSigned(x.paise()), x.note() == null ? "" : x.note()), false);
                }
            }
            cs.close();
            footers();
        }

        // ------------------------------------------------------------ pieces

        private String name(long id) {
            return names.getOrDefault(id, "#" + id);
        }

        private String when(Expense e) {
            LocalDate d = LocalDate.parse(e.date());
            boolean years = from.getYear() != to.getYear();
            if (!e.daySet()) {
                return (years ? MON_YEAR : MON).format(d) + " (no day)";
            }
            return (years ? DAY_YEAR : DAY).format(d);
        }

        private String split(Expense e) {
            return e.shares().stream().map(Share::personId).map(this::name).collect(Collectors.joining(", "));
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - MARGIN - 10;
        }

        /** Starts a new page unless {@code h} points still fit above the footer. Returns true if it did. */
        private boolean need(float h) throws IOException {
            if (y - h < MARGIN + 24) {
                newPage();
                return true;
            }
            return false;
        }

        private void heading(String t) throws IOException {
            need(60);
            y -= 26;
            text(bold, 10, MARGIN, y, t.toUpperCase(Locale.ENGLISH), Color.BLACK);
            y -= 5;
            rule(Color.BLACK, 1.2f);
        }

        private void paragraph(String t, Color color) throws IOException {
            for (String line : wrap(regular, BODY, t, width)) {
                need(LINE);
                y -= LINE;
                text(regular, BODY, MARGIN, y, line, color);
            }
        }

        private void rule(Color color, float thickness) throws IOException {
            cs.setStrokingColor(color);
            cs.setLineWidth(thickness);
            cs.moveTo(MARGIN, y);
            cs.lineTo(MARGIN + width, y);
            cs.stroke();
        }

        /** Gives the column with width 0 whatever is left. */
        private List<Col> flex(List<Col> cols) {
            float fixed = 0;
            for (Col c : cols) {
                fixed += c.width();
            }
            List<Col> out = new ArrayList<>();
            for (Col c : cols) {
                out.add(c.width() == 0 ? new Col(c.title(), width - fixed, c.right()) : c);
            }
            return out;
        }

        private void header(List<Col> cols) throws IOException {
            need(LINE * 3);
            drawHeader(cols);
        }

        private void drawHeader(List<Col> cols) throws IOException {
            y -= LINE;
            float x = MARGIN;
            for (Col c : cols) {
                float tx = c.right() ? x + c.width() - 4 - w(bold, 8.5f, c.title()) : x + 4;
                text(bold, 8.5f, tx, y, c.title(), GREY);
                x += c.width();
            }
            y -= 5;
            rule(MID, 0.7f);
        }

        /** A table row; long cells wrap. On a page break the table header is repeated. */
        private void row(List<Col> cols, List<String> cells, boolean total) throws IOException {
            PDType0Font f = total ? bold : regular;
            List<List<String>> lines = new ArrayList<>();
            int height = 1;
            for (int i = 0; i < cols.size(); i++) {
                List<String> l = wrap(f, BODY, cells.get(i), cols.get(i).width() - 8);
                lines.add(l);
                height = Math.max(height, l.size());
            }
            float h = height * LINE + 5;
            if (need(h + (total ? 2 : 0))) {
                drawHeader(cols);
            }
            if (total) {
                y -= 1;
                rule(Color.BLACK, 1.2f);
            }
            float top = y;
            for (int k = 0; k < height; k++) {
                y -= LINE;
                float x = MARGIN;
                for (int i = 0; i < cols.size(); i++) {
                    Col c = cols.get(i);
                    List<String> l = lines.get(i);
                    if (k < l.size()) {
                        String t = l.get(k);
                        float tx = c.right() ? x + c.width() - 4 - w(f, BODY, t) : x + 4;
                        text(f, BODY, tx, y, t, Color.BLACK);
                    }
                    x += c.width();
                }
            }
            y = top - h;
            if (!total) {
                rule(LIGHT, 0.5f);
            }
        }

        private void runs(float x, float yy, Object[] parts) throws IOException {
            for (int i = 0; i < parts.length; i += 2) {
                PDType0Font f = (PDType0Font) parts[i];
                String t = (String) parts[i + 1];
                text(f, BODY, x, yy, t, Color.BLACK);
                x += w(f, BODY, t);
            }
        }

        private void footers() throws IOException {
            int total = doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage page = doc.getPage(i);
                try (PDPageContentStream f = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                    cs = f;
                    text(regular, 8, MARGIN, MARGIN - 8, "Homelab Hub · Budget", GREY);
                    String p = "Page " + (i + 1) + " of " + total;
                    text(regular, 8, MARGIN + width - w(regular, 8, p), MARGIN - 8, p, GREY);
                }
            }
        }

        // ------------------------------------------------------------ text

        private void text(PDType0Font f, float size, float x, float yy, String s, Color color) throws IOException {
            cs.beginText();
            cs.setNonStrokingColor(color);
            cs.setFont(f, size);
            cs.newLineAtOffset(x, yy);
            cs.showText(clean(s));
            cs.endText();
        }

        private float w(PDType0Font f, float size, String s) throws IOException {
            return f.getStringWidth(clean(s)) / 1000 * size;
        }

        /** Strips control characters and replaces anything the fonts cannot draw with "?". */
        private String clean(String s) {
            StringBuilder sb = new StringBuilder();
            s.codePoints().forEach(cp -> {
                if (Character.isISOControl(cp)) {
                    sb.append(' ');
                } else if (encodable.computeIfAbsent(cp, k -> canEncode(regular, k) && canEncode(bold, k))) {
                    sb.appendCodePoint(cp);
                } else {
                    sb.append('?');
                }
            });
            return sb.toString();
        }

        /** Word wrap by measured width; a single word longer than the line is broken by characters. */
        private List<String> wrap(PDType0Font f, float size, String s, float max) throws IOException {
            List<String> out = new ArrayList<>();
            String t = clean(s == null ? "" : s);
            if (t.isEmpty()) {
                out.add("");
                return out;
            }
            StringBuilder line = new StringBuilder();
            for (String word : t.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (w(f, size, candidate) <= max) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                // the word alone may still be too long
                String rest = word;
                while (w(f, size, rest) > max && rest.length() > 1) {
                    int cut = rest.length() - 1;
                    while (cut > 1 && w(f, size, rest.substring(0, cut)) > max) {
                        cut--;
                    }
                    out.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line.append(rest);
            }
            if (line.length() > 0) {
                out.add(line.toString());
            }
            return out;
        }
    }
}
