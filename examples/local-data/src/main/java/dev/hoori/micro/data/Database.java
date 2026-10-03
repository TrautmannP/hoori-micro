package dev.hoori.micro.data;

import hoori.concurrent.Cancellation;
import hoori.jdbc.JdbcTransactions;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Properties;
import org.postgresql.Driver;

/** Stable factory, with finite acquisition and I/O defaults. Each local transaction owns its connection. */
public final class Database {
    public static final int IO_TIMEOUT_MILLIS = 10_000;

    public static JdbcTransactions.Factory factory() {
        String url = required("HOORI_DB_URL");
        String user = required("HOORI_DB_USER");
        String password = required("HOORI_DB_PASSWORD");
        String tls = System.getenv("HOORI_DB_SSLMODE");
        String sslMode = tls == null ? "require" : tls;

        // URL parameters take precedence in pgJDBC; keep timeout configuration under this factory's control.
        if (!url.startsWith("jdbc:postgresql:") || url.indexOf('?') >= 0)
            throw new IllegalArgumentException("Use a PostgreSQL JDBC URL without query parameters");

        Driver driver = new Driver();

        return budget -> {
            Cancellation.checkpoint();

            if (budget.isExpired()) throw new SQLTimeoutException("Database acquisition budget expired");

            long seconds = 1 + (budget.remainingNanos() - 1) / 1_000_000_000L;
            Properties properties = new Properties();
            properties.setProperty("user", user);
            properties.setProperty("password", password);
            properties.setProperty("sslmode", sslMode);
            properties.setProperty("connectTimeout", Long.toString(Math.min(5, seconds)));
            // Keep the upstream login ceiling above finite socket waits: a login timeout abandons its helper.
            properties.setProperty("loginTimeout", "300");
            properties.setProperty("socketTimeout", Long.toString(Math.min(10, seconds)));
            properties.setProperty("cancelSignalTimeout", "2");
            Connection connection = driver.connect(url, properties);

            if (connection == null) throw new SQLException("Unsupported database URL");

            return connection;
        };
    }

    private static String required(String name) {
        String value = System.getenv(name);

        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + name);

        return value;
    }
}
