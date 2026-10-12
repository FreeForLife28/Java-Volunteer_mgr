package com.volunteer.web;

import com.google.gson.JsonObject;
import com.volunteer.util.*;
import org.mindrot.jbcrypt.BCrypt;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

@WebServlet("/api/auth/*")
public class AuthServlet extends ApiServlet {
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String r = segs(req).length > 0 ? segs(req)[0] : "";
        switch (r) {
            case "me":
                send(res, 200, me(req));
                break;
            case "settings": // public: used by the sign-in page and page headers
                send(res, 200, Map.of(
                        "siteName", Settings.get("site_name", "VolunteerHub"),
                        "allowRegistration", Settings.bool("allow_registration", true)));
                break;
            default:
                throw new ApiError(404, "Not found");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String r = segs(req).length > 0 ? segs(req)[0] : "";
        switch (r) {
            case "login": login(req, res); break;
            case "register": register(req, res); break;
            case "logout":
                HttpSession s = req.getSession(false);
                if (s != null) s.invalidate();
                message(res, 200, "Signed out");
                break;
            default:
                throw new ApiError(404, "Not found");
        }
    }

    private void login(HttpServletRequest req, HttpServletResponse res) throws IOException {
        JsonObject b = body(req);
        String email = str(b, "email", true).toLowerCase();
        String password = b.has("password") && b.get("password").isJsonPrimitive() ? b.get("password").getAsString() : "";
        Map<String, Object> u = Db.one("SELECT id, name, email, password_hash, role, status FROM users WHERE email=?", email);
        if (u == null || !BCrypt.checkpw(password, (String) u.get("password_hash"))) {
            throw new ApiError(401, "Email or password is incorrect");
        }
        if (!"ACTIVE".equals(u.get("status"))) {
            throw new ApiError(403, "This account is suspended. Contact an administrator.");
        }
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate(); // avoid session fixation
        HttpSession s = req.getSession(true);
        User user = new User(((Number) u.get("id")).longValue(), (String) u.get("name"), (String) u.get("email"), (String) u.get("role"));
        s.setAttribute("user", user);
        s.setMaxInactiveInterval(8 * 60 * 60);
        send(res, 200, user);
    }

    private void register(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (!Settings.bool("allow_registration", true)) {
            throw new ApiError(403, "New registrations are turned off right now");
        }
        JsonObject b = body(req);
        String name = str(b, "name", true);
        String email = str(b, "email", true).toLowerCase();
        String role = str(b, "role", true);
        String password = str(b, "password", true);
        if (name.length() > 100) throw new ApiError(400, "Name is too long");
        if (!EMAIL.matcher(email).matches()) throw new ApiError(400, "Enter a valid email address");
        if (!role.equals("VOLUNTEER") && !role.equals("ORGANIZATION")) throw new ApiError(400, "Choose volunteer or organization");
        if (password.length() < 6) throw new ApiError(400, "Password must be at least 6 characters");
        if (Db.count("SELECT COUNT(*) FROM users WHERE email=?", email) > 0) {
            throw new ApiError(409, "An account with this email already exists");
        }
        Db.insert("INSERT INTO users (name, email, password_hash, role) VALUES (?,?,?,?)",
                name, email, BCrypt.hashpw(password, BCrypt.gensalt()), role);
        message(res, 201, "Account created. You can sign in now.");
    }
}
