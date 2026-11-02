package com.pocketbanker.channels.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayDeque;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PrivateApiHeaders extends OncePerRequestFilter {
  private final ArrayDeque<Long> authAttempts = new ArrayDeque<>();

  private synchronized boolean allowAuth() {
    long now = System.nanoTime();
    while (!authAttempts.isEmpty() && now - authAttempts.peek() >= 60_000_000_000L)
      authAttempts.remove();
    if (authAttempts.size() >= 60) return false;
    authAttempts.add(now);
    return true;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (req.getRequestURI().startsWith("/api/")) {
      res.setHeader("Cache-Control", "no-store");
      res.setHeader("Pragma", "no-cache");
      res.setHeader("X-Content-Type-Options", "nosniff");
      res.setHeader("Referrer-Policy", "no-referrer");
    }
    // Bound expensive password work even for unknown usernames; one Mac instance is the scope.
    if ("POST".equals(req.getMethod())
        && req.getRequestURI().startsWith("/api/auth/")
        && !req.getRequestURI().endsWith("/logout")
        && !allowAuth()) {
      res.setStatus(429);
      res.setHeader("Retry-After", "60");
      res.setContentType("application/json");
      res.getWriter().write("{\"error\":\"authentication_rate_limited\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}
