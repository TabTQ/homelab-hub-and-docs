package com.homelabhub.docs.portfolio;

import java.util.List;

/**
 * One editable kind of portfolio content. SQL is only ever built from these static specs, never from request keys, so
 * a request cannot name a column or a table.
 */
public record ResourceSpec(
        String path, String title, String table, List<Field> fields, String orderBy, boolean singleton,
        String parentApi, String parentColumn, String parentPath,
        String skillLinkTable, String skillLinkOwnerColumn, String tagsOwnerType,
        boolean hasUpdatedAt, List<Usage> usage) {

    public enum Type { TEXT, LONGTEXT, INT, URL, SLUG, ENUM, TEXT_LIST, JSON_LIST, JSON_OBJECT }

    /**
     * @param api      name in the JSON API (camelCase)
     * @param nullable the column allows NULL. A NOT NULL optional text column stores "" instead.
     */
    public record Field(String api, String column, String label, Type type, boolean required, boolean nullable, int max, List<String> allowed, String help) {

        public static Field text(String api, String column, String label, boolean required, boolean nullable, int max) {
            return new Field(api, column, label, Type.TEXT, required, nullable, max, List.of(), null);
        }

        public static Field longText(String api, String column, String label, boolean required, boolean nullable) {
            return new Field(api, column, label, Type.LONGTEXT, required, nullable, 20_000, List.of(), null);
        }

        public static Field url(String api, String column, String label) {
            return new Field(api, column, label, Type.URL, false, true, 500, List.of(), "Must start with http:// or https://");
        }

        public static Field slug(String api, String column, String label) {
            return new Field(api, column, label, Type.SLUG, true, false, 64, List.of(), "Lower-case letters, digits and -. Used in the address, so it should not change.");
        }

        public static Field choice(String api, String column, String label, boolean required, String... allowed) {
            return new Field(api, column, label, Type.ENUM, required, !required, 40, List.of(allowed), null);
        }

        public static Field sortOrder() {
            return new Field("sortOrder", "sort_order", "Sort order", Type.INT, false, false, 1_000_000, List.of(), "Lower numbers appear first");
        }

        public static Field list(String api, String column, String label, String help) {
            return new Field(api, column, label, Type.TEXT_LIST, false, false, 2000, List.of(), help);
        }

        public static Field jsonList(String api, String column, String label, String help) {
            return new Field(api, column, label, Type.JSON_LIST, false, false, 65_536, List.of(), help);
        }

        public static Field jsonObject(String api, String column, String label, String help) {
            return new Field(api, column, label, Type.JSON_OBJECT, false, false, 65_536, List.of(), help);
        }
    }

    /** "How many things depend on this row", for the delete confirmation. The SQL takes the row id as its only parameter. */
    public record Usage(String label, String countSql) {}

    public boolean hasParent() {
        return parentColumn != null;
    }
}
