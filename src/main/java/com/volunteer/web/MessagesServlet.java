package com.volunteer.web;

import com.google.gson.JsonObject;
import com.volunteer.util.*;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;

/**
 * /api/messages/*  : messages and feedback between organizations and volunteers.
 * Volunteers can write to any active organization; organizations can write to volunteers signed up for their opportunities.
 */
@WebServlet("/api/messages/*")
public class MessagesServlet extends ApiServlet {

    private List<Map<String, Object>> contacts(User u) {
        if ("VOLUNTEER".equals(u.role)) {
            return Db.query("SELECT id, name FROM users WHERE role='ORGANIZATION' AND status='ACTIVE' ORDER BY name");
        }
        if ("ORGANIZATION".equals(u.role)) {
            return Db.query("SELECT DISTINCT v.id, v.name FROM signups s JOIN listings l ON l.id=s.listing_id "
                    + "JOIN users v ON v.id=s.volunteer_id WHERE l.org_id=? AND s.status<>'CANCELLED' AND v.status='ACTIVE' ORDER BY v.name", u.id);
        }
        return new ArrayList<>();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        User u = me(req);
        String[] s = segs(req);
        if (s.length == 1 && s[0].equals("contacts")) {
            send(res, 200, contacts(u));
        } else if (s.length == 0) {
            send(res, 200, Db.query(
                    "SELECT m.id, m.kind, m.body, m.created_at, m.sender_id, m.receiver_id, "
                            + "a.name AS sender_name, b.name AS receiver_name "
                            + "FROM messages m JOIN users a ON a.id=m.sender_id JOIN users b ON b.id=m.receiver_id "
                            + "WHERE m.sender_id=? OR m.receiver_id=? ORDER BY m.created_at DESC, m.id DESC LIMIT 200", u.id, u.id));
        } else {
            throw new ApiError(404, "Not found");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        User u = me(req);
        if (segs(req).length != 0) throw new ApiError(404, "Not found");
        JsonObject b = body(req);
        long to = id(str(b, "receiverId", true));
        String kind = str(b, "kind", false);
        if (kind.isEmpty()) kind = "MESSAGE";
        if (!kind.equals("MESSAGE") && !kind.equals("FEEDBACK")) throw new ApiError(400, "Invalid type");
        String text = str(b, "body", true);
        if (text.length() > 2000) throw new ApiError(400, "Message is too long (max 2000 characters)");

        boolean allowed = false;
        for (Map<String, Object> c : contacts(u)) {
            if (((Number) c.get("id")).longValue() == to) { allowed = true; break; }
        }
        if (!allowed) throw new ApiError(403, "You can't message this person");

        Db.insert("INSERT INTO messages (sender_id, receiver_id, kind, body) VALUES (?,?,?,?)", u.id, to, kind, text);
        message(res, 201, kind.equals("FEEDBACK") ? "Feedback sent" : "Message sent");
    }
}
