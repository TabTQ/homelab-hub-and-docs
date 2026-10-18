// Shapes of /api/admin/budget (plan 5.4). Every amount is integer paise, every date an ISO string.

export interface PersonRef { id: number; name: string; archived: boolean }
export interface Person extends PersonRef { version: number; expenseCount: number; recurringCount: number }
export interface Share { personId: number; paise: number }
export interface Expense {
  id: number; name: string; amountPaise: number; paidBy: number; date: string; daySet: boolean;
  recurringId: number | null; recurringMonth: string | null; version: number; shares: Share[];
}
export interface Recurring {
  id: number; name: string; amountPaise: number; paidBy: number; splitIds: number[]; startMonth: string;
  endMonth: string | null; day: number | null; version: number; ended: boolean;
}
export interface Settlement { id: number; fromId: number; toId: number; paise: number; date: string; note: string | null }
export interface Transfer { fromId: number; toId: number; paise: number }
export interface Row {
  personId: number; paidPaise: number; sharePaise: number; periodNetPaise: number; carriedInPaise: number;
  settledPaise: number; toSettlePaise: number;
}
export interface Statement {
  rows: Row[];
  totals: Omit<Row, "personId">;
  transfers: Transfer[];
  openingTransfers: Transfer[];
  expenseCount: number;
  expenseTotalPaise: number;
  hasSettlements: boolean;
}
export interface HistoryMonth { month: string; paidPaise: Record<string, number>; totalPaise: number }
export interface MonthView {
  month: string; from: string; to: string; today: string; people: PersonRef[]; expenses: Expense[];
  statement: Statement; settlements: Settlement[]; history: HistoryMonth[];
}
export interface StatementView { from: string; to: string; people: PersonRef[]; statement: Statement; expenses: Expense[]; settlements: Settlement[] }

export const BUDGET = "/api/admin/budget";

/** Field messages from a 422 (or a 409 that names a field), keyed like the request body. */
export type FieldErrors = Record<string, string>;
