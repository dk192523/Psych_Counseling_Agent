package com.dk.dkaiagent.account;

import org.springframework.security.crypto.password.PasswordEncoder;

/** All users of the password bean share the same process-wide BCrypt work limits. */
final class ResourceBoundPasswordEncoder implements PasswordEncoder {
    private final PasswordEncoder delegate;
    private final AuthResourceLimiter limiter;

    ResourceBoundPasswordEncoder(PasswordEncoder delegate, AuthResourceLimiter limiter) {
        this.delegate = delegate;
        this.limiter = limiter;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return limiter.withPasswordWork(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return limiter.withPasswordWork(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) { return delegate.upgradeEncoding(encodedPassword); }
}
