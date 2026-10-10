package com.tinyme.auth.service;

import com.tinyme.auth.model.Owner;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public Owner requireOwner() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Owner owner) return owner;
        throw new IllegalStateException("No authenticated owner in the security context");
    }
}
