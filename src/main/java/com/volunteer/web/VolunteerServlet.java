package com.volunteer.web;

import com.google.gson.JsonObject;
import com.volunteer.util.*;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/** /api/volunteer/*  : browse and sign up for opportunities, track hours. */
@WebServlet("/api/volunteer/*")
public class VolunteerServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        String r = s.length > 0 ? s[0] : "";
        User u = me(req);
        switch (r) {
            case "opportunities":
                send(res, 200, Db.query(
                        "SELECT l.id, l.title, l.description, l.event_date, l.location, l.hours, l.capacity, u.name AS org_name, "
                                + "(SELECT COUNT(*) FROM signups s WHERE s.listing_id=l.id AND s.status<>'CANCELLED') AS taken, "
                                + "(SELECT COUNT(*) FROM signups s WHERE s.listing_id=l.id AND s.volunteer_id=? AND s.status<>'CANCELLED') AS mine "
                                + "FROM listings l JOIN users u ON u.id=l.org_id "
                                + "WHERE l.status='APPROVED' AND u.status='ACTIVE' AND l.event_date>=CURDATE() ORDER BY l.event_date", u.id));
                break;
            case "signups":
                send(res, 200, Db.query(
                        "SELECT s.id, s.status, s.created_at, l.id AS listing_id, l.title, l.event_date, l.location, l.hours, o.name AS org_name "
                                + "FROM signups s JOIN listings l ON l.id=s.listing_id JOIN users o ON o.id=l.org_id "
                                + "WHERE s.volunteer_id=? ORDER BY l.event_date DESC", u.id));
                break;
            case "hours":
                hours(res, u);
                break;
            default:
                throw new ApiError(404, "Not found");
        }
    }

    private void hours(HttpServletResponse res, User u) throws IOException {
        List<Map<String, Object>> entries = Db.query(
                "SELECT h.id, h.hours, h.log_date, h.notes, h.source, l.title AS listing_title "
                        + "FROM volunteer_hours h LEFT JOIN listings l ON l.id=h.listing_id "
                        + "WHERE h.volunteer_id=? ORDER BY h.log_date DESC, h.id DESC", u.id);
        double total = 0, verified = 0;
        for (Map<String, Object> e : entries) {
            double h = ((Number) e.get("hours")).doubleValue();
            total += h;
            if ("VERIFIED".equals(e.get("source"))) verified += h;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", entries);
        out.put("total", total);
        out.put("verified", verified);
        out.put("selfReported", total - verified);
        send(res, 200, out);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        User u = me(req);
        if (s.length == 1 && s[0].equals("signups")) {
            signUp(req, res, u);
        } else if (s.length == 1 && s[0].equals("hours")) {
            JsonObject b = body(req);
            double hrs = num(b, "hours", 0.25, 24);
            LocalDate when = date(b, "logDate");
            if (when.isAfter(LocalDate.now())) throw new ApiError(400, "You can only log hours for today or earlier");
            String notes = str(b, "notes", false);
            if (notes.length() > 255) throw new ApiError(400, "Notes are too long (max 255 characters)");
            Object listingId = null;
            String lid = str(b, "listingId", false);
            if (!lid.isEmpty()) {
                long l = id(lid);
                if (Db.count("SELECT COUNT(*) FROM signups WHERE listing_id=? AND volunteer_id=? AND status<>'CANCELLED'", l, u.id) == 0) {
                    throw new ApiError(400, "You are not signed up for that opportunity");
                }
                listingId = l;
            }
            Db.insert("INSERT INTO volunteer_hours (volunteer_id, listing_id, hours, log_date, notes, source) VALUES (?,?,?,?,?, 'SELF')",
                    u.id, listingId, hrs, when, notes.isEmpty() ? null : notes);
            message(res, 201, "Hours logged");
        } else {
            throw new ApiError(404, "Not found");
        }
    }

    private void signUp(HttpServletRequest req, HttpServletResponse res, User u) throws IOException {
        long lid = id(str(body(req), "listingId", true));
        Map<String, Object> l = Db.one("SELECT l.capacity, l.title FROM listings l JOIN users o ON o.id=l.org_id "
                + "WHERE l.id=? AND l.status='APPROVED' AND o.status='ACTIVE' AND l.event_date>=CURDATE()", lid);
        if (l == null) throw new ApiError(404, "This opportunity is not available");

        Map<String, Object> existing = Db.one("SELECT id, status FROM signups WHERE listing_id=? AND volunteer_id=?", lid, u.id);
        if (existing != null && !"CANCELLED".equals(existing.get("status"))) {
            throw new ApiError(409, "You are already signed up for this opportunity");
        }
        long taken = Db.count("SELECT COUNT(*) FROM signups WHERE listing_id=? AND status<>'CANCELLED'", lid);
        if (taken >= ((Number) l.get("capacity")).longValue()) throw new ApiError(409, "This opportunity is full");

        int max = Settings.integer("max_active_signups", 0);
        if (max > 0) {
            long active = Db.count("SELECT COUNT(*) FROM signups s JOIN listings l ON l.id=s.listing_id "
                    + "WHERE s.volunteer_id=? AND s.status='SIGNED_UP' AND l.event_date>=CURDATE()", u.id);
            if (active >= max) throw new ApiError(409, "You can have at most " + max + " upcoming sign-ups at a time");
        }
        if (existing != null) {
            Db.update("UPDATE signups SET status='SIGNED_UP' WHERE id=?", existing.get("id"));
        } else {
            Db.insert("INSERT INTO signups (listing_id, volunteer_id) VALUES (?,?)", lid, u.id);
        }
        message(res, 201, "You're signed up for " + l.get("title"));
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        User u = me(req);
        if (s.length == 2 && s[0].equals("signups")) {
            int n = Db.update("UPDATE signups SET status='CANCELLED' WHERE id=? AND volunteer_id=? AND status='SIGNED_UP'", id(s[1]), u.id);
            if (n == 0) throw new ApiError(404, "Only active sign-ups can be cancelled");
            message(res, 200, "Sign-up cancelled");
        } else if (s.length == 2 && s[0].equals("hours")) {
            int n = Db.update("DELETE FROM volunteer_hours WHERE id=? AND volunteer_id=? AND source='SELF'", id(s[1]), u.id);
            if (n == 0) throw new ApiError(404, "Only hours you logged yourself can be removed");
            message(res, 200, "Entry removed");
        } else {
            throw new ApiError(404, "Not found");
        }
    }
}
