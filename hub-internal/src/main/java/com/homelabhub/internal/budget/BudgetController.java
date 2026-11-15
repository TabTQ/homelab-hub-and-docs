package com.homelabhub.internal.budget;

import com.homelabhub.internal.budget.BudgetModels.Expense;
import com.homelabhub.internal.budget.BudgetModels.HistoryMonth;
import com.homelabhub.internal.budget.BudgetModels.MonthView;
import com.homelabhub.internal.budget.BudgetModels.Person;
import com.homelabhub.internal.budget.BudgetModels.Recurring;
import com.homelabhub.internal.budget.BudgetModels.Settlement;
import com.homelabhub.internal.budget.BudgetModels.StatementView;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.homelabhub.internal.member.MemberAccounts;
import com.homelabhub.internal.member.MemberPrincipal;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final MemberAccounts accounts;
    private final TransactionTemplate tx;

    BudgetController(BudgetService service, BudgetLedger ledger, MemberAccounts accounts, TransactionTemplate tx) {
        this.tx = tx;
        this.service = service;
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /** The member behind this request, or null for the admin. */
    private static MemberPrincipal member() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof MemberPrincipal p ? p : null;
    }

    /** Response of "add a person" when a login was created with it: the person plus the one-time password. */
    record PersonWithPassword(@JsonUnwrapped Person person, String password) {}

    // ---------------------------------------------------------------- people

    @GetMapping("/people")
    List<Person> people() {
        List<Person> all = service.people();
        if (member() == null) {
            return all;
        }
        // Login details are for the admin only.
        return all.stream().map(p -> new Person(p.id(), p.name(), p.archived(), p.version(), p.expenseCount(), p.recurringCount(), null)).toList();
    }

    @PostMapping("/people")
    ResponseEntity<Object> addPerson(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!Boolean.TRUE.equals(body.get("createLogin"))) {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.addPerson(body));
        }
        MemberAccounts.Generated g = accounts.generate(); // hashed before the write transaction: bcrypt is slow on a Pi
        Person created = tx.execute(st -> {
            Person p = service.addPerson(body);
            accounts.insert(p.id(), g.hash());
            return service.person(p.id());
        });
        accounts.audit("member-account", created.id(), com.homelabhub.internal.audit.AuditLog.Result.ok, "created with the person",
                req.getRemoteAddr(), req.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED).body(new PersonWithPassword(created, g.password()));
    }

    @PutMapping("/people/{id}")
    Person updatePerson(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        Person p = service.updatePerson(id, body);
        if (p.archived()) {
            accounts.disableOnArchive(id, req.getRemoteAddr(), req.getHeader("User-Agent"));
            return service.person(id);
        }
        return p;
    }

    // ---------------------------------------------------------------- member logins (admin only)

    @PostMapping("/people/{id}/account")
    ResponseEntity<Map<String, Object>> createAccount(@PathVariable long id, HttpServletRequest req) {
        String password = accounts.create(id, req.getRemoteAddr(), req.getHeader("User-Agent"));
        return ResponseEntity.ok(Map.of("password", password));
    }

    @PostMapping("/people/{id}/account/reset")
    Map<String, Object> resetAccount(@PathVariable long id, HttpServletRequest req) {
        return Map.of("password", accounts.reset(id, req.getRemoteAddr(), req.getHeader("User-Agent")));
    }

    @PutMapping("/people/{id}/account")
    Person updateAccount(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        Check c = new Check(body);
        Boolean canAdd = c.bool("canAdd");
        Boolean disabled = c.bool("disabled");
        c.done();
        accounts.update(id, canAdd, disabled, req.getRemoteAddr(), req.getHeader("User-Agent"));
        return service.person(id);
    }

    // ---------------------------------------------------------------- months

    /** Read only: creates nothing, so recurring entries of a month nobody has opened yet are not in it. Members use this. */
    @GetMapping("/months/{month}")
    MonthView view(@PathVariable String month) {
        return ledger.viewMonth(month(month, "month"));
    }

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
        MemberPrincipal m = member();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addExpense(body, m == null ? null : m.personId()));
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
        if (member() == null) {
            ledger.materialise(YearMonth.from(r[1])); // a member's read never creates entries
        }
        return ledger.statement(r[0], r[1]);
    }

    /** Built only now, streamed back and never stored. The file name is made here, never from user text. */
    @GetMapping("/export.pdf")
    ResponseEntity<byte[]> export(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                  @RequestParam(required = false) String split, @RequestParam(required = false) String people) {
        LocalDate[] r = BudgetService.range(from, to);
        StatementFilter filter = StatementFilter.parse(split, people);
        if (member() == null) {
            ledger.materialise(YearMonth.from(r[1])); // a member's read never creates entries
        }
        byte[] pdf = BudgetPdf.render(ledger.statement(r[0], r[1]), ledger.today(), filter);
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

    @ExceptionHandler(MemberAccounts.ExistsException.class)
    ResponseEntity<Map<String, Object>> accountExists(MemberAccounts.ExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "conflict", "message", e.getMessage()));
    }

    @ExceptionHandler(MemberAccounts.RefusedException.class)
    ResponseEntity<Map<String, Object>> accountRefused(MemberAccounts.RefusedException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid", "errors", Map.of(e.field(), e.getMessage())));
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
