package com.dk.dkaiagent.account;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Clock;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Per-process admission for every login and every BCrypt operation, including dummy hashes. */
@Service
public class AuthResourceLimiter {
    private final Clock clock;
    private final BoundedRateWindow sources;
    private final BoundedRateWindow logins;
    private final BoundedRateWindow passwordWork;
    private final Semaphore workers;

    public AuthResourceLimiter() { this(Clock.systemUTC(), 4096, 30, 120, 120, 4, 60_000); }

    @Autowired
    public AuthResourceLimiter(@Value("${app.auth.limits.max-source-keys:4096}") int maxKeys,
                               @Value("${app.auth.limits.source-logins-per-minute:30}") int sourceLimit,
                               @Value("${app.auth.limits.global-logins-per-minute:120}") int loginLimit,
                               @Value("${app.auth.limits.bcrypt-per-minute:120}") int workLimit,
                               @Value("${app.auth.limits.bcrypt-concurrency:4}") int concurrency) {
        this(Clock.systemUTC(), maxKeys, sourceLimit, loginLimit, workLimit, concurrency, 60_000);
    }

    AuthResourceLimiter(Clock clock, int maxKeys, int sourceLimit, int loginLimit,
                        int workLimit, int concurrency, long windowMillis) {
        if (concurrency < 1) throw new IllegalArgumentException("BCrypt concurrency must be positive");
        this.clock = clock;
        sources = new BoundedRateWindow(maxKeys, sourceLimit, windowMillis);
        logins = new BoundedRateWindow(1, loginLimit, windowMillis);
        passwordWork = new BoundedRateWindow(1, workLimit, windowMillis);
        workers = new Semaphore(concurrency);
    }

    public synchronized boolean tryBeginLogin(String clientIp) {
        long now = clock.millis();
        String source = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        if (!logins.canConsume("global", now) || !sources.canConsume(source, now)) return false;
        logins.consume("global", now);
        sources.consume(source, now);
        return true;
    }

    public <T> T withPasswordWork(Supplier<T> action) {
        // No waiting queue: rejected work never retains a request thread waiting for BCrypt.
        if (!workers.tryAcquire()) throw new CapacityExceededException();
        try {
            synchronized (this) {
                long now = clock.millis();
                if (!passwordWork.canConsume("global", now)) throw new CapacityExceededException();
                passwordWork.consume("global", now);
            }
            return action.get();
        } finally {
            workers.release();
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void expireIdleWindows() {
        long now = clock.millis();
        sources.cleanup(now);
        logins.cleanup(now);
        passwordWork.cleanup(now);
    }

    synchronized int sourceCount() { return sources.size(); }

    @ResponseStatus(value = HttpStatus.TOO_MANY_REQUESTS, reason = "请求过于频繁，请稍后再试")
    public static class CapacityExceededException extends RuntimeException { }
}
