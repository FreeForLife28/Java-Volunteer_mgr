package com.volunteer.util;

import java.util.*;
import java.util.regex.Pattern;

/** System-wide settings stored in the `settings` table. */
public final class Settings {
    public static final Set<String> KEYS = new LinkedHashSet<>(Arrays.asList(
            "site_name", "contact_email", "allow_registration", "require_listing_approval", "max_active_signups"));
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Settings() {}

    public static Map<String, Object> all() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map<String, Object> r : Db.query("SELECT setting_key, setting_value FROM settings ORDER BY setting_key")) {
            m.put((String) r.get("setting_key"), r.get("setting_value"));
        }
        return m;
    }

    public static String get(String key, String def) {
        Map<String, Object> r = Db.one("SELECT setting_value FROM settings WHERE setting_key=?", key);
        return r == null ? def : (String) r.get("setting_value");
    }

    public static boolean bool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }

    public static int integer(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Validates and normalises a value, or throws ApiError. */
    public static String validate(String key, String value) {
        String v = value == null ? "" : value.trim();
        switch (key) {
            case "site_name":
                if (v.isEmpty() || v.length() > 60) throw new ApiError(400, "Site name must be 1-60 characters");
                return v;
            case "contact_email":
                if (!EMAIL.matcher(v).matches()) throw new ApiError(400, "Contact email is not valid");
                return v;
            case "allow_registration":
            case "require_listing_approval":
                if (!v.equals("true") && !v.equals("false")) throw new ApiError(400, key + " must be true or false");
                return v;
            case "max_active_signups":
                try {
                    int n = Integer.parseInt(v);
                    if (n < 0 || n > 100) throw new NumberFormatException();
                    return String.valueOf(n);
                } catch (NumberFormatException e) {
                    throw new ApiError(400, "Max active sign-ups must be a whole number from 0 to 100");
                }
            default:
                throw new ApiError(400, "Unknown setting: " + key);
        }
    }

    public static void set(String key, String value) {
        Db.update("INSERT INTO settings (setting_key, setting_value) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)", key, value);
    }
}
