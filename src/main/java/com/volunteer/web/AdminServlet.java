package com.volunteer.web;

import com.google.gson.JsonObject;
import com.volunteer.util.*;
import org.mindrot.jbcrypt.BCrypt;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;

/** /api/admin/*  : users, listing approvals, settings, statistics. */
@WebServlet("/api/admin/*")
public class AdminServlet extends ApiServlet {
    private static final Set<String> ROLES = Set.of("ADMIN", "ORGANIZATION", "VOLUNTEER");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        String r = s.length > 0 ? s[0] : "";
        switch (r) {
            case "users":
                send(res, 200, Db.query("SELECT id, name, email, role, status, created_at FROM users ORDER BY id"));
                break;
            case "listings":
                send(res, 200, Db.query(
                        "SELECT l.id, l.title, l.description, l.event_date, l.location, l.hours, l.capacity, l.status, l.created_at, "
                                + "u.name AS org_name, "
                                + "(SELECT COUNT(*) FROM signups s WHERE s.listing_id=l.id AND s.status<>'CANCELLED') AS signups "
                                + "FROM listings l JOIN users u ON u.id=l.org_id "
                                + "ORDER BY FIELD(l.status,'PENDING','APPROVED','REJECTED'), l.event_date DESC"));
                break;
            case "settings":
                send(res, 200, Settings.all());
                break;
            case "stats":
                stats(res);
                break;
            default:
                throw new ApiError(404, "Not found");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        if (s.length == 1 && s[0].equals("users")) {
            JsonObject b = body(req);
            String name = str(b, "name", true);
            String email = str(b, "email", true).toLowerCase();
            String role = str(b, "role", true);
            String status = str(b, "status", false);
            if (status.isEmpty()) status = "ACTIVE";
            String password = str(b, "password", true);
            validateUser(name, email, role, status);
            if (password.length() < 6) throw new ApiError(400, "Password must be at least 6 characters");
            Db.insert("INSERT INTO users (name, email, password_hash, role, status) VALUES (?,?,?,?,?)",
                    name, email, BCrypt.hashpw(password, BCrypt.gensalt()), role, status);
            message(res, 201, "User created successfully");
        } else if (s.length == 3 && s[0].equals("listings") && s[2].equals("status")) {
            String status = str(body(req), "status", true);
            if (!Set.of("APPROVED", "REJECTED", "PENDING").contains(status)) throw new ApiError(400, "Invalid status");
            int n = Db.update("UPDATE listings SET status=? WHERE id=?", status, id(s[1]));
            if (n == 0) throw new ApiError(404, "Listing not found");
            message(res, 200, "Listing " + status.toLowerCase());
        } else {
            throw new ApiError(404, "Not found");
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        if (s.length == 2 && s[0].equals("users")) {
            long uid = id(s[1]);
            JsonObject b = body(req);
            String name = str(b, "name", true);
            String email = str(b, "email", true).toLowerCase();
            String role = str(b, "role", true);
            String status = str(b, "status", true);
            String password = str(b, "password", false);
            validateUser(name, email, role, status);
            if (uid == me(req).id && (!role.equals("ADMIN") || !status.equals("ACTIVE"))) {
                throw new ApiError(400, "You cannot demote or suspend your own account");
            }
            if (!password.isEmpty()) {
                if (password.length() < 6) throw new ApiError(400, "Password must be at least 6 characters");
                Db.update("UPDATE users SET password_hash=? WHERE id=?", BCrypt.hashpw(password, BCrypt.gensalt()), uid);
            }
            int n = Db.update("UPDATE users SET name=?, email=?, role=?, status=? WHERE id=?", name, email, role, status, uid);
            if (n == 0 && Db.count("SELECT COUNT(*) FROM users WHERE id=?", uid) == 0) throw new ApiError(404, "User not found");
            message(res, 200, "User updated successfully");
        } else if (s.length == 1 && s[0].equals("settings")) {
            JsonObject b = body(req);
            Map<String, String> clean = new LinkedHashMap<>();
            for (String key : b.keySet()) {
                clean.put(key, Settings.validate(key, str(b, key, false)));
            }
            if (clean.isEmpty()) throw new ApiError(400, "No settings provided");
            clean.forEach(Settings::set);
            message(res, 200, "Settings updated successfully");
        } else {
            throw new ApiError(404, "Not found");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        if (s.length == 2 && s[0].equals("users")) {
            long uid = id(s[1]);
            if (uid == me(req).id) throw new ApiError(400, "You cannot delete your own account");
            int n = Db.update("DELETE FROM users WHERE id=?", uid);
            if (n == 0) throw new ApiError(404, "User not found");
            message(res, 200, "User deleted successfully");
        } else {
            throw new ApiError(404, "Not found");
        }
    }

    private void validateUser(String name, String email, String role, String status) {
        if (name.length() > 100) throw new ApiError(400, "Name is too long");
        if (!AuthServlet.EMAIL.matcher(email).matches()) throw new ApiError(400, "Enter a valid email address");
        if (!ROLES.contains(role)) throw new ApiError(400, "Invalid role");
        if (!STATUSES.contains(status)) throw new ApiError(400, "Invalid status");
    }

    private void stats(HttpServletResponse res) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("usersByRole", Db.query("SELECT role, COUNT(*) AS count FROM users GROUP BY role"));
        out.put("listingsByStatus", Db.query("SELECT status, COUNT(*) AS count FROM listings GROUP BY status"));
        out.put("signups", Db.count("SELECT COUNT(*) FROM signups WHERE status<>'CANCELLED'"));
        out.put("attended", Db.count("SELECT COUNT(*) FROM signups WHERE status='ATTENDED'"));
        Map<String, Object> h = Db.one("SELECT COALESCE(SUM(hours),0) AS total FROM volunteer_hours");
        out.put("hours", h == null ? 0 : h.get("total"));
        out.put("pending", Db.query(
                "SELECT l.id, l.title, l.event_date, l.location, u.name AS org_name FROM listings l "
                        + "JOIN users u ON u.id=l.org_id WHERE l.status='PENDING' ORDER BY l.created_at"));
        out.put("top", Db.query(
                "SELECT l.title, u.name AS org_name, COUNT(s.id) AS signups FROM listings l "
                        + "JOIN users u ON u.id=l.org_id LEFT JOIN signups s ON s.listing_id=l.id AND s.status<>'CANCELLED' "
                        + "WHERE l.status='APPROVED' GROUP BY l.id, l.title, u.name ORDER BY signups DESC, l.event_date LIMIT 5"));
        send(res, 200, out);
    }
}
