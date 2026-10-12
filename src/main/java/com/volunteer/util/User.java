package com.volunteer.util;

import java.io.Serializable;

/** The signed-in user, stored in the HTTP session. */
public class User implements Serializable {
    public final long id;
    public final String name;
    public final String email;
    public final String role;

    public User(long id, String name, String email, String role) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.role = role;
    }
}
