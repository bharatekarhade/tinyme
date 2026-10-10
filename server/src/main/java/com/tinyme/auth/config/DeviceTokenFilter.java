package com.tinyme.auth.config;

import com.tinyme.auth.service.DeviceTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class DeviceTokenFilter extends OncePerRequestFilter {
    private final DeviceTokenService tokens;

    public DeviceTokenFilter(DeviceTokenService tokens) { this.tokens = tokens; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String raw = header.substring(7).strip();
            tokens.verify(raw).ifPresent(owner -> {
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        owner, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
