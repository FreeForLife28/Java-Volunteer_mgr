package com.volunteer.util;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Small JDBC helper. Every call opens and closes its own connection (fine for local use). */
public final class Db {
    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = Db.class.getResourceAsStream("/db.properties")) {
            if (in != null) PROPS.load(in);
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Db() {}

    private static String cfg(String envKey, String propKey) {
        String v = System.getenv(envKey);
        return (v != null && !v.isEmpty()) ? v : PROPS.getProperty(propKey);
    }

    public static Connection conn() throws SQLException {
        return DriverManager.getConnection(cfg("DB_URL", "db.url"), cfg("DB_USER", "db.user"), cfg("DB_PASSWORD", "db.password"));
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    /** Converts DB types into things Gson can serialise. */
    private static Object norm(Object v) {
        if (v instanceof BigDecimal) return ((BigDecimal) v).doubleValue();
        if (v instanceof java.time.temporal.TemporalAccessor || v instanceof java.util.Date) return v.toString();
        return v;
    }

    public static List<Map<String, Object>> query(String sql, Object... params) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), norm(rs.getObject(i)));
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    /** First row or null. */
    public static Map<String, Object> one(String sql, Object... params) {
        List<Map<String, Object>> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int update(String sql, Object... params) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    /** Insert and return the generated id. */
    public static long insert(String sql, Object... params) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public static long count(String sql, Object... params) {
        Map<String, Object> r = one(sql, params);
        return r == null ? 0 : ((Number) r.values().iterator().next()).longValue();
    }
}
