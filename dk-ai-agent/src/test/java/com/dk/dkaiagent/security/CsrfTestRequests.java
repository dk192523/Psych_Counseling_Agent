package com.dk.dkaiagent.security;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
public final class CsrfTestRequests {
    public static MockHttpServletRequestBuilder post(String url, Object... args) {
        return MockMvcRequestBuilders.post(url, args).with(csrf());
    }
}
