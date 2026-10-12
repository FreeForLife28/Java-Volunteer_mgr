package com.volunteer.util;

import com.google.gson.*;

import javax.servlet.ServletException;
import javax.servlet.http.*;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Base class: JSON helpers + uniform error handling for every API servlet. */
public abstract class ApiServlet extends HttpServlet {
    public static final Gson GSON = new Gson();

    public static void send(HttpServletResponse res, int status, Object body) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(GSON.toJson(body));
    }

    public static void message(HttpServletResponse res, int status, String msg) throws IOException {
        send(res, status, Map.of("message", msg));
    }

    public static void error(HttpServletResponse res, int status, String msg) throws IOException {
        send(res, status, Map.of("error", msg));
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        try {
            super.service(req, res);
        } catch (ApiError e) {
            error(res, e.status, e.getMessage());
        } catch (DbException e) {
            if (e.sqlState != null && e.sqlState.startsWith("23")) {
                error(res, 409, "That record already exists, or other data depends on it.");
            } else {
                log("Database error", e);
                error(res, 500, "Database error. Check that MySQL is running and db.properties is correct.");
            }
        } catch (RuntimeException e) {
            log("Unexpected error", e);
            error(res, 500, "Something went wrong on the server.");
        }
    }

    // ---- request helpers ----

    protected User me(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        User u = s == null ? null : (User) s.getAttribute("user");
        if (u == null) throw new ApiError(401, "Please sign in");
        return u;
    }

    protected String[] segs(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/")) return new String[0];
        return p.substring(1).split("/");
    }

    protected long id(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            throw new ApiError(400, "Invalid id");
        }
    }

    protected JsonObject body(HttpServletRequest req) {
        try {
            JsonElement el = JsonParser.parseReader(req.getReader());
            return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException | IOException e) {
            throw new ApiError(400, "Invalid JSON body");
        }
    }

    protected String str(JsonObject b, String key, boolean required) {
        JsonElement e = b.get(key);
        String v = (e != null && e.isJsonPrimitive()) ? e.getAsString().trim() : "";
        if (required && v.isEmpty()) throw new ApiError(400, "Missing required field: " + key);
        return v;
    }

    protected double num(JsonObject b, String key, double min, double max) {
        String v = str(b, key, true);
        double d;
        try {
            d = Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new ApiError(400, key + " must be a number");
        }
        if (Double.isNaN(d) || d < min || d > max) throw new ApiError(400, key + " must be between " + min + " and " + max);
        return d;
    }

    protected LocalDate date(JsonObject b, String key) {
        try {
            return LocalDate.parse(str(b, key, true));
        } catch (DateTimeParseException e) {
            throw new ApiError(400, key + " must be a valid date (YYYY-MM-DD)");
        }
    }
}
