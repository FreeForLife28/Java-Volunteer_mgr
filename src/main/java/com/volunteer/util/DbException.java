package com.volunteer.util;

import java.sql.SQLException;

public class DbException extends RuntimeException {
    public final String sqlState;

    public DbException(SQLException cause) {
        super(cause);
        this.sqlState = cause.getSQLState();
    }
}
