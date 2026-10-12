package com.volunteer.web;

import com.volunteer.util.Db;
import org.mindrot.jbcrypt.BCrypt;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/** Creates a default admin account the first time the app starts against an empty users table. */
@WebListener
public class AppListener implements ServletContextListener {
    public static final String ADMIN_EMAIL = "admin@volunteerhub.local";
    public static final String ADMIN_PASSWORD = "Admin@123";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        try {
            if (Db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN'") == 0) {
                Db.insert("INSERT INTO users (name, email, password_hash, role, status) VALUES (?,?,?,?,?)",
                        "Administrator", ADMIN_EMAIL, BCrypt.hashpw(ADMIN_PASSWORD, BCrypt.gensalt()), "ADMIN", "ACTIVE");
                sce.getServletContext().log("Created default admin " + ADMIN_EMAIL + " (change the password after first login)");
            }
        } catch (Exception e) {
            sce.getServletContext().log("Could not seed admin. Did you run database/schema.sql and set db.properties?", e);
        }
    }
}
