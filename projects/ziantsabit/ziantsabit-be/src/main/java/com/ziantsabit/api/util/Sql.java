package com.ziantsabit.api.util;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.support.AbstractSqlTypeValue;

/** The handful of JDBC conversions the repositories share. */
public final class Sql {

    private Sql() {
    }

    /** "Now", at the precision Postgres stores, so a write's response matches a later read. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** A bindable timestamp; the driver takes OffsetDateTime but not Instant. */
    public static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** A {@code varchar[]} parameter. Passed as a real SQL array rather than a list,
     *  which the named-parameter layer would otherwise expand into {@code (?, ?, ...)}. */
    public static AbstractSqlTypeValue textArray(List<String> values) {
        return new AbstractSqlTypeValue() {
            @Override
            protected Object createTypeValue(Connection connection, int sqlType, String typeName)
                    throws SQLException {
                return connection.createArrayOf("varchar", values.toArray());
            }
        };
    }

    public static List<String> strings(ResultSet row, String column) throws SQLException {
        Array array = row.getArray(column);
        if (array == null) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
    }

    /** Escape a user's search term for LIKE/ILIKE, as Django's icontains did. */
    public static String containing(String term) {
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /**
     * WHERE clauses and their parameters, built up one filter at a time.
     * Every route that lists or looks up a row assembles one of these, so a
     * filter added for the list applies to the detail route too -- as it did
     * when both went through one Django queryset.
     */
    public static final class Where {

        private final List<String> clauses = new ArrayList<>();
        private final Map<String, Object> params = new LinkedHashMap<>();

        public Where and(String clause) {
            clauses.add(clause);
            return this;
        }

        public Where and(String clause, String name, Object value) {
            clauses.add(clause);
            params.put(name, value);
            return this;
        }

        public Where param(String name, Object value) {
            params.put(name, value);
            return this;
        }

        public String sql() {
            return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        }

        public Map<String, Object> params() {
            return params;
        }
    }
}
