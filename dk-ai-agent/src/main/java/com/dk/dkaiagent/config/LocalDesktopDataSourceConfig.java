package com.dk.dkaiagent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Encrypted embedded files only; no H2 TCP listener or AUTO_SERVER process. */
@Configuration
@Profile("local-desktop")
public class LocalDesktopDataSourceConfig {
    @Bean
    public Path desktopDataDirectory(@Value("${app.desktop.data-directory:}") String configured) throws IOException {
        Path working = Path.of(System.getProperty("user.dir"));
        Path directory = resolveDataDirectory(configured, System.getenv("LOCALAPPDATA"),
                Path.of(System.getProperty("user.home")), working);
        Files.createDirectories(directory);
        Path resolved = directory.toRealPath();
        if (resolved.startsWith(working.toRealPath())) {
            throw new IllegalArgumentException("Desktop data directory must be outside the working/install directory");
        }
        return resolved;
    }

    static Path resolveDataDirectory(String configured, String localAppData, Path userHome, Path workingDirectory) {
        Path directory = configured != null && !configured.isBlank() ? Path.of(configured)
                : localAppData != null && !localAppData.isBlank()
                    ? Path.of(localAppData).resolve("PsychCounselor")
                    : userHome.resolve(".local/share/PsychCounselor");
        if (!directory.isAbsolute()) throw new IllegalArgumentException("Desktop data directory must be absolute");
        directory = directory.normalize();
        String text = directory.toString();
        if (directory.startsWith(workingDirectory.toAbsolutePath().normalize())
                || text.indexOf(';') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid desktop data directory");
        }
        return directory;
    }

    @Bean(destroyMethod = "close")
    public DataSource desktopDataSource(Path desktopDataDirectory, ConfigurableEnvironment environment) {
        // Only the desktop bootstrap may supply this process-local char[]; configuration strings are rejected.
        PropertySource<?> boundary = environment.getPropertySources().get("desktopProcessBoundary");
        Object credential = boundary == null ? null : boundary.getProperty("app.desktop.database-file-password");
        if (!(credential instanceof char[] filePassword) || filePassword.length != 64) {
            throw new IllegalStateException("Desktop database encryption requires a launcher-provided in-memory key");
        }
        for (char value : filePassword) {
            if (!(value >= '0' && value <= '9' || value >= 'a' && value <= 'f')) {
                throw new IllegalStateException("Invalid desktop database encryption key");
            }
        }
        char[] connectionPassword = Arrays.copyOf(filePassword, filePassword.length + 1);
        connectionPassword[filePassword.length] = ' '; // H2 file password + space + empty sa password.
        JdbcDataSource embedded = new JdbcDataSource();
        HikariDataSource source = new HikariDataSource() {
            @Override
            public void close() {
                try { super.close(); }
                finally {
                    Arrays.fill(connectionPassword, '\0');
                    embedded.setPasswordChars(new char[0]);
                }
            }
        };
        try {
            embedded.setURL("jdbc:h2:file:" + desktopDataDirectory.resolve("counseling").toString().replace('\\', '/')
                    + ";CIPHER=AES;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_ON_EXIT=FALSE");
            embedded.setUser("sa");
            embedded.setPasswordChars(connectionPassword);
            // Keep the password out of Hikari's String configuration and out of application properties.
            source.setDataSource(embedded);
            source.setMaximumPoolSize(8);
            source.setMinimumIdle(1);
            source.setConnectionTimeout(5000);
            source.setPoolName("local-desktop-storage");
            return source;
        } catch (RuntimeException | Error failure) {
            source.close();
            throw failure;
        }
    }
}
