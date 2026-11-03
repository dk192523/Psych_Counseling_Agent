package com.dk.dkaiagent.account;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;

/** Atomic IP + normalized-username admission; active windows are never evicted for new keys. */
@Service
public class RegisterThrottleService {
    public static final int IP_WINDOW_MAX = 10;
    public static final int USERNAME_WINDOW_MAX = 3;
    public static final long WINDOW_MINUTES = 15;
    public static final int MAX_KEYS_PER_DIMENSION = 10_000;
    private final Clock clock;
    private final BoundedRateWindow ipHits;
    private final BoundedRateWindow usernameHits;

    public RegisterThrottleService() {
        this(Clock.systemUTC(), MAX_KEYS_PER_DIMENSION, Duration.ofMinutes(WINDOW_MINUTES).toMillis());
    }

    RegisterThrottleService(Clock clock, int maxKeys, long windowMillis) {
        this.clock = clock;
        ipHits = new BoundedRateWindow(maxKeys, IP_WINDOW_MAX, windowMillis);
        usernameHits = new BoundedRateWindow(maxKeys, USERNAME_WINDOW_MAX, windowMillis);
    }

    /** Check and consume both budgets under one lock; rejected requests consume neither. */
    public synchronized boolean tryBeginRegister(String clientIp, String rawUsername) {
        String username = AuthValidation.normalizeUsername(rawUsername);
        if (username.length() > AuthValidation.USERNAME_MAX_LENGTH) return false;
        long now = clock.millis();
        if (!ipHits.canConsume(clientIp, now) || !usernameHits.canConsume(username, now)) return false;
        ipHits.consume(clientIp, now);
        usernameHits.consume(username, now);
        return true;
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void expireIdleWindows() {
        long now = clock.millis();
        ipHits.cleanup(now);
        usernameHits.cleanup(now);
    }

    synchronized int ipCount() { return ipHits.size(); }
    synchronized int usernameCount() { return usernameHits.size(); }
}
