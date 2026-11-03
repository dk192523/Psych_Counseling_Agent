package com.dk.dkaiagent.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

final class MutableAuthClock extends Clock {
    private final AtomicLong now = new AtomicLong(Instant.parse("2026-10-09T00:00:00Z").toEpochMilli());
    void advance(Duration duration) { now.addAndGet(duration.toMillis()); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    @Override public long millis() { return now.get(); }
}
