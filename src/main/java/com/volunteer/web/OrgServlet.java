package com.volunteer.web;

import com.google.gson.JsonObject;
import com.volunteer.util.*;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/** /api/org/*  : post/manage opportunities and track participation. */
@WebServlet("/api/org/*")
public class OrgServlet extends ApiServlet {

    private static class Listing {
        String title, description, location;
        LocalDate date;
        double hours;
        int capacity;
    }

    private Listing read(JsonObject b) {
        Listing l = new Listing();
        l.title = str(b, "title", true);
        l.description = str(b, "description", true);
        l.location = str(b, "location", true);
        l.date = date(b, "eventDate");
        l.hours = num(b, "hours", 0.25, 24);
        l.capacity = (int) num(b, "capacity", 1, 10000);
        if (l.title.length() > 150) throw new ApiError(400, "Title is too long (max 150 characters)");
        if (l.location.length() > 200) throw new ApiError(400, "Location is too long (max 200 characters)");
        if (l.date.isBefore(LocalDate.now())) throw new ApiError(400, "Event date cannot be in the past");
        return l;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        String r = s.length > 0 ? s[0] : "";
        User u = me(req);
        switch (r) {
            case "listings":
                send(res, 200, Db.query(
                        "SELECT l.id, l.title, l.description, l.event_date, l.location, l.hours, l.capacity, l.status, "
                                + "(SELECT COUNT(*) FROM signups s WHERE s.listing_id=l.id AND s.status<>'CANCELLED') AS signups "
                                + "FROM listings l WHERE l.org_id=? ORDER BY l.event_date DESC", u.id));
                break;
            case "participation":
                send(res, 200, Db.query(
                        "SELECT s.id, s.status, s.created_at, l.id AS listing_id, l.title, l.event_date, l.hours, "
                                + "v.id AS volunteer_id, v.name AS volunteer_name, v.email AS volunteer_email "
                                + "FROM signups s JOIN listings l ON l.id=s.listing_id JOIN users v ON v.id=s.volunteer_id "
                                + "WHERE l.org_id=? AND s.status<>'CANCELLED' ORDER BY l.event_date DESC, v.name", u.id));
                break;
            default:
                throw new ApiError(404, "Not found");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        User u = me(req);
        if (s.length == 1 && s[0].equals("listings")) {
            Listing l = read(body(req));
            boolean needsApproval = Settings.bool("require_listing_approval", true);
            Db.insert("INSERT INTO listings (org_id, title, description, event_date, location, hours, capacity, status) VALUES (?,?,?,?,?,?,?,?)",
                    u.id, l.title, l.description, l.date, l.location, l.hours, l.capacity, needsApproval ? "PENDING" : "APPROVED");
            message(res, 201, needsApproval
                    ? "Opportunity posted. It will go live once an administrator approves it."
                    : "Opportunity posted and live for volunteers.");
        } else if (s.length == 2 && s[0].equals("participation")) {
            String status = str(body(req), "status", true);
            if (!Set.of("SIGNED_UP", "ATTENDED", "NO_SHOW").contains(status)) throw new ApiError(400, "Invalid status");
            long signupId = id(s[1]);
            Map<String, Object> row = Db.one(
                    "SELECT s.id, s.volunteer_id, s.listing_id, l.hours, l.event_date FROM signups s "
                            + "JOIN listings l ON l.id=s.listing_id WHERE s.id=? AND l.org_id=?", signupId, u.id);
            if (row == null) throw new ApiError(404, "Sign-up not found");
            Db.update("UPDATE signups SET status=? WHERE id=?", status, signupId);
            if (status.equals("ATTENDED")) {
                // Attendance confirmed by the organization automatically credits the volunteer's hours.
                Db.update("INSERT INTO volunteer_hours (volunteer_id, listing_id, signup_id, hours, log_date, notes, source) "
                                + "VALUES (?,?,?,?,?,?, 'VERIFIED') ON DUPLICATE KEY UPDATE hours=VALUES(hours)",
                        row.get("volunteer_id"), row.get("listing_id"), signupId, row.get("hours"), row.get("event_date"),
                        "Attendance confirmed by organization");
            } else {
                Db.update("DELETE FROM volunteer_hours WHERE signup_id=?", signupId);
            }
            message(res, 200, "Participation updated");
        } else {
            throw new ApiError(404, "Not found");
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        User u = me(req);
        if (s.length == 2 && s[0].equals("listings")) {
            long lid = id(s[1]);
            Listing l = read(body(req));
            if (Db.count("SELECT COUNT(*) FROM listings WHERE id=? AND org_id=?", lid, u.id) == 0) throw new ApiError(404, "Opportunity not found");
            long taken = Db.count("SELECT COUNT(*) FROM signups WHERE listing_id=? AND status<>'CANCELLED'", lid);
            if (l.capacity < taken) throw new ApiError(400, "Capacity cannot be lower than the " + taken + " volunteers already signed up");
            boolean needsApproval = Settings.bool("require_listing_approval", true);
            Db.update("UPDATE listings SET title=?, description=?, event_date=?, location=?, hours=?, capacity=?, status=? WHERE id=? AND org_id=?",
                    l.title, l.description, l.date, l.location, l.hours, l.capacity, needsApproval ? "PENDING" : "APPROVED", lid, u.id);
            message(res, 200, needsApproval ? "Opportunity updated and sent for re-approval" : "Opportunity updated");
        } else {
            throw new ApiError(404, "Not found");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String[] s = segs(req);
        User u = me(req);
        if (s.length == 2 && s[0].equals("listings")) {
            int n = Db.update("DELETE FROM listings WHERE id=? AND org_id=?", id(s[1]), u.id);
            if (n == 0) throw new ApiError(404, "Opportunity not found");
            message(res, 200, "Opportunity deleted");
        } else {
            throw new ApiError(404, "Not found");
        }
    }
}
