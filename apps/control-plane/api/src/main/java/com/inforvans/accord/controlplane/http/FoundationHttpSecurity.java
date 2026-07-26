package com.inforvans.accord.controlplane.http;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.inforvans.accord.platformkernel.CanonicalJson;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
class FoundationHttpSecurity {
    static final String ROUTE_PREFIX = "/v1/contract-validations/";
    static final RequestMatcher FOUNDATION_MUTATION = request -> {
        String path = applicationRelativePath(request);
        return "POST".equals(request.getMethod())
            && path.startsWith(ROUTE_PREFIX)
            && path.length() > ROUTE_PREFIX.length()
            && path.indexOf('/', ROUTE_PREFIX.length()) < 0;
    };

    static String applicationRelativePath(HttpServletRequest request) {
        String requestUri = java.util.Objects.requireNonNull(
            request.getRequestURI(), "requestURI");
        String contextPath = java.util.Objects.requireNonNull(
            request.getContextPath(), "contextPath");
        if (requestUri.isEmpty() || requestUri.charAt(0) != '/') {
            throw new IllegalArgumentException("request URI must be an absolute path");
        }
        if (contextPath.isEmpty()) {
            return requestUri;
        }
        if (contextPath.charAt(0) != '/'
                || contextPath.endsWith("/")
                || !requestUri.startsWith(contextPath)
                || (requestUri.length() > contextPath.length()
                    && requestUri.charAt(contextPath.length()) != '/')) {
            throw new IllegalArgumentException(
                "request URI is inconsistent with servlet context path");
        }
        return requestUri.substring(contextPath.length());
    }

    @Bean
    SecurityFilterChain foundationSecurityFilterChain(
            HttpSecurity http,
            FoundationProblemFactory problems,
            @Value("${accord.security.allowed-origin}") String allowedOrigin) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers(FOUNDATION_MUTATION))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(FOUNDATION_MUTATION)
                .access((authentication, context) -> {
                    var current = authentication.get();
                    return new AuthorizationDecision(
                        current != null
                            && current.isAuthenticated()
                            && current.getPrincipal() instanceof FoundationVerifiedPrincipal);
                })
                .anyRequest().permitAll())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, failure) ->
                    FoundationHttpResponseWriter.write(
                        response,
                        problems.authenticationRequired(
                            UUID.randomUUID(), request.getRequestURI())))
                .accessDeniedHandler((request, response, failure) ->
                    FoundationHttpResponseWriter.write(
                        response,
                        problems.authorizationDenied(
                            UUID.randomUUID(), request.getRequestURI()))))
            .requestCache(cache -> cache.disable())
            .addFilterAfter(
                new FoundationBrowserCsrfFilter(allowedOrigin, problems),
                AuthorizationFilter.class);
        return http.build();
    }
}

final class FoundationBrowserCsrfFilter extends OncePerRequestFilter {
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9._~-]{32,256}$");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String allowedOrigin;
    private final FoundationProblemFactory problems;

    FoundationBrowserCsrfFilter(String allowedOrigin, FoundationProblemFactory problems) {
        this.allowedOrigin = requireHttpsOrigin(allowedOrigin);
        this.problems = java.util.Objects.requireNonNull(problems, "problems");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !FoundationHttpSecurity.FOUNDATION_MUTATION.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof FoundationVerifiedPrincipal.Bearer) {
            chain.doFilter(request, response);
            return;
        }
        if (principal instanceof FoundationVerifiedPrincipal.BrowserSession browser
                && validBrowserRequest(request, browser)) {
            chain.doFilter(request, response);
            return;
        }

        FoundationHttpResponseWriter.write(
            response,
            problems.csrfValidationFailed(UUID.randomUUID(), request.getRequestURI()));
    }

    private boolean validBrowserRequest(
            HttpServletRequest request,
            FoundationVerifiedPrincipal.BrowserSession browser) {
        String origin = singletonHeader(request, "Origin");
        String fetchSite = singletonHeader(request, "Sec-Fetch-Site");
        String token = singletonHeader(request, "X-CSRF-Token");
        if (!allowedOrigin.equals(origin)
                || !"same-origin".equals(fetchSite)
                || token == null
                || !TOKEN.matcher(token).matches()) {
            return false;
        }

        String tokenDigest = rawSha256(token.getBytes(UTF_8));
        var binding = MAPPER.createObjectNode();
        binding.put("tenant_id", browser.tenantId().toString());
        binding.put("actor_id", browser.actorId());
        binding.put("session_id", browser.sessionId());
        binding.put("session_generation", browser.sessionGeneration());
        binding.put("token_digest", tokenDigest);
        try {
            String actual = CanonicalJson.sha256(MAPPER.writeValueAsBytes(binding));
            return MessageDigest.isEqual(
                actual.getBytes(UTF_8), browser.csrfBindingDigest().getBytes(UTF_8));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("CSRF binding cannot be serialized", error);
        }
    }

    private static String singletonHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }

    private static String rawSha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String requireHttpsOrigin(String value) {
        URI origin = URI.create(java.util.Objects.requireNonNull(value, "allowedOrigin"));
        if (!"https".equals(origin.getScheme())
                || origin.getHost() == null
                || origin.getRawUserInfo() != null
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                || origin.getRawQuery() != null
                || origin.getRawFragment() != null) {
            throw new IllegalArgumentException("allowedOrigin must be an HTTPS origin");
        }
        return value;
    }
}
