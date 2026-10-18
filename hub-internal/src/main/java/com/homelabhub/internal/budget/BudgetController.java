package com.homelabhub.internal.budget;

import com.homelabhub.internal.budget.BudgetModels.Expense;
import com.homelabhub.internal.budget.BudgetModels.HistoryMonth;
import com.homelabhub.internal.budget.BudgetModels.MonthView;
import com.homelabhub.internal.budget.BudgetModels.Person;
import com.homelabhub.internal.budget.BudgetModels.Recurring;
import com.homelabhub.internal.budget.BudgetModels.Settlement;
import com.homelabhub.internal.budget.BudgetModels.StatementView;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The Budget page's API (plan 5.4). Everything is under /api/admin/budget, so it needs the login in both the "all" and
 * "edit" auth modes without any change to SecurityConfig. All amounts are integer paise.
 *
 * <p>Note on HTTP semantics: opening a month, a statement and the PDF export may create recurring entries (idempotent,
 * plan D8/D9). That is deliberate: nothing runs in the background, so entries appear when a month is looked at.
 */
@RestController
@RequestMapping("/api/admin/budget")
class BudgetController {

    private final BudgetService service;
    private final BudgetLedger ledger;

    BudgetController(BudgetService service, BudgetLedger ledger) {
        this.service = service;
        this.ledger = ledger;
    }

    // ---------------------------------------------------------------- people

    @GetMapping("/people")
    List<Person> people() {
        return service.people();
    }

    @PostMapping("/people")
    ResponseEntity<Person> addPerson(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addPerson(body));
    }

    @PutMapping("/people/{id}")
    Person updatePerson(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.updatePerson(id, body);
    }

    // ---------------------------------------------------------------- months

    @PostMapping("/months/{month}/open")
    MonthView open(@PathVariable String month) {
        return ledger.openMonth(month(month, "month"));
    }

    @GetMapping("/history")
    List<HistoryMonth> history(@RequestParam String to, @RequestParam(defaultValue = "6") int months) {
        return ledger.history(month(to, "to"), months);
    }

    private static YearMonth month(String text, String field) {
        YearMonth m = Check.parseMonth(text);
        if (m == null) {
            throw new BudgetService.ValidationException(Map.of(field, "Must be a month between 2000-01 and 2100-12 (YYYY-MM)."));
        }
        return m;
    }

    // ---------------------------------------------------------------- expenses

    @PostMapping("/expenses")
    ResponseEntity<Expense> addExpense(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addExpense(body));
    }

    @PutMapping("/expenses/{id}")
    Expense updateExpense(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.updateExpense(id, body);
    }

    @DeleteMapping("/expenses/{id}")
    Map<String, Object> deleteExpense(@PathVariable long id) {
        service.deleteExpense(id);
        return Map.of("deleted", id);
    }

    // ---------------------------------------------------------------- recurring

    @GetMapping("/recurring")
    List<Recurring> recurring() {
        return service.recurring();
    }

    @PostMapping("/recurring")
    ResponseEntity<Recurring> addRecurring(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addRecurring(body));
    }

    @PutMapping("/recurring/{id}")
    Recurring updateRecurring(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.updateRecurring(id, body);
    }

    @DeleteMapping("/recurring/{id}")
    Map<String, Object> deleteRecurring(@PathVariable long id) {
        service.deleteRecurring(id);
        return Map.of("deleted", id);
    }

    // ---------------------------------------------------------------- settlements

    @PostMapping("/settlements")
    ResponseEntity<Settlement> addSettlement(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addSettlement(body));
    }

    @DeleteMapping("/settlements/{id}")
    Map<String, Object> deleteSettlement(@PathVariable long id) {
        service.deleteSettlement(id);
        return Map.of("deleted", id);
    }

    // ---------------------------------------------------------------- statements and PDF

    @GetMapping("/statement")
    StatementView statement(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate[] r = BudgetService.range(from, to);
        ledger.materialise(YearMonth.from(r[1]));
        return ledger.statement(r[0], r[1]);
    }

    /** Built only now, streamed back and never stored. The file name is made here, never from user text. */
    @GetMapping("/export.pdf")
    ResponseEntity<byte[]> export(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate[] r = BudgetService.range(from, to);
        ledger.materialise(YearMonth.from(r[1]));
        byte[] pdf = BudgetPdf.render(ledger.statement(r[0], r[1]), ledger.today());
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_PDF);
        h.setContentDisposition(ContentDisposition.attachment().filename(BudgetPdf.filename(r[0], r[1])).build());
        h.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(pdf, h, HttpStatus.OK);
    }

    // ---------------------------------------------------------------- errors (same shapes as the portfolio editor)

    @ExceptionHandler(BudgetService.ValidationException.class)
    ResponseEntity<Map<String, Object>> invalid(BudgetService.ValidationException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid", "errors", e.errors()));
    }

    @ExceptionHandler(BudgetService.ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(BudgetService.ConflictException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "conflict");
        m.put("message", e.getMessage());
        if (e.current() != null) {
            m.put("current", e.current());
        }
        if (e.errors() != null) {
            m.put("errors", e.errors());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(m);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, Object>> missing(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found", "message", "That no longer exists. Reload the page."));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, Object>> unreadable(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "bad_request", "message", "The request is not valid."));
    }

    /** sqlite-jdbc reports constraint violations as UncategorizedSQLException (see PortfolioController). Other errors stay 500s. */
    @ExceptionHandler({DataIntegrityViolationException.class, org.springframework.jdbc.UncategorizedSQLException.class})
    ResponseEntity<Map<String, Object>> integrity(org.springframework.dao.DataAccessException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (!(msg.contains("constraint") || msg.contains("UNIQUE") || msg.contains("FOREIGN KEY"))) {
            throw e;
        }
        String text = msg.contains("UNIQUE") ? "That value is already used." : msg.contains("FOREIGN KEY")
                ? "That refers to something that does not exist." : "That conflicts with existing content.";
        return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid", "errors", Map.of("_", text)));
    }
}
