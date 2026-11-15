package com.homelabhub.internal.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.budget.BudgetModels.Expense;
import com.homelabhub.internal.budget.BudgetModels.Share;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StatementFilterTest {

    private static Expense e(long paidBy, long... sharers) {
        List<Share> shares = java.util.Arrays.stream(sharers).mapToObj(p -> new Share(p, 100)).toList();
        return new Expense(1, "x", 100L * sharers.length, paidBy, "2026-09-05", true, null, null, 0, shares);
    }

    @Test
    void splitMeansMoreThanOneShareRowWhoeverPaid() {
        StatementFilter f = new StatementFilter(true, Set.of());
        assertThat(f.matches(e(1, 1))).isFalse();
        assertThat(f.matches(e(1, 2))).isFalse(); // one share row, even though someone else paid
        assertThat(f.matches(e(1, 1, 2))).isTrue();
        assertThat(f.matches(e(3, 1, 2))).isTrue();
    }

    @Test
    void peopleMatchPayersAndSharers() {
        StatementFilter f = new StatementFilter(false, Set.of(2L));
        assertThat(f.matches(e(2, 1))).isTrue();
        assertThat(f.matches(e(1, 1, 2))).isTrue();
        assertThat(f.matches(e(1, 1, 3))).isFalse();
    }

    @Test
    void bothMustHoldAndNothingMeansEverything() {
        StatementFilter f = new StatementFilter(true, Set.of(2L));
        assertThat(f.matches(e(2, 2))).isFalse();
        assertThat(f.matches(e(1, 1, 2))).isTrue();
        assertThat(StatementFilter.NONE.active()).isFalse();
        assertThat(StatementFilter.NONE.matches(e(9, 9))).isTrue();
        assertThat(StatementFilter.parse("true", "1, 2").people()).containsExactly(1L, 2L);
        assertThat(StatementFilter.parse(null, null).active()).isFalse();
    }
}
