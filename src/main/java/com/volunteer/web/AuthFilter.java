package com.volunteer.web;

import com.volunteer.util.ApiServlet;
import com.volunteer.util.Db;
import com.volunteer.util.User;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.Map;

/** Protects /api/*: requires a session, enforces role per area, and blocks cross-site form posts. */
@WebFilter("/api/*")
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        res.setHeader("Cache-Control", "no-store");

        String path = req.getRequestURI().substring(req.getContextPath().length());

        // CSRF guard: browsers cannot add custom headers cross-site without a CORS preflight.
        if (!"GET".equals(req.getMethod()) && req.getHeader("X-Requested-With") == null) {
            ApiServlet.error(res, 403, "Missing X-Requested-With header");
            return;
        }

        if (path.startsWith("/api/auth")) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = req.getSession(false);
        User user = session == null ? null : (User) session.getAttribute("user");
        if (user == null) {
            ApiServlet.error(res, 401, "Please sign in");
            return;
        }

        // Re-check the account on every request so suspended/deleted users lose access immediately.
        Map<String, Object> row = Db.one("SELECT status FROM users WHERE id=?", user.id);
        if (row == null || !"ACTIVE".equals(row.get("status"))) {
            session.invalidate();
            ApiServlet.error(res, 401, "Your account is no longer active");
            return;
        }

        String required = null;
        if (path.startsWith("/api/admin")) required = "ADMIN";
        else if (path.startsWith("/api/org")) required = "ORGANIZATION";
        else if (path.startsWith("/api/volunteer")) required = "VOLUNTEER";
        else if (!path.startsWith("/api/messages")) {
            ApiServlet.error(res, 404, "Not found");
            return;
        }
        if (required != null && !required.equals(user.role)) {
            ApiServlet.error(res, 403, "You do not have access to this area");
            return;
        }
        chain.doFilter(request, response);
    }
}
