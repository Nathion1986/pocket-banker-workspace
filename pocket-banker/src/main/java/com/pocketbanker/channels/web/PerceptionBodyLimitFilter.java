package com.pocketbanker.channels.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在 JSON 解码前限制原文体积，也覆盖没有 Content-Length 的分块请求。 */
@Component
public class PerceptionBodyLimitFilter extends OncePerRequestFilter {
  static final int MAX_BYTES = 131072;

  public static class BodyTooLarge extends IOException {}

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > MAX_BYTES) {
      response.setStatus(413);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"request_body_too_large\"}");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = request.getInputStream();
            return new ServletInputStream() {
              int count;

              @Override
              public int read() throws IOException {
                int result = delegate.read();
                if (result >= 0 && ++count > MAX_BYTES) throw new BodyTooLarge();
                return result;
              }

              @Override
              public int read(byte[] bytes, int offset, int length) throws IOException {
                int n = delegate.read(bytes, offset, Math.min(length, MAX_BYTES - count + 1));
                if (n > 0 && (count += n) > MAX_BYTES) throw new BodyTooLarge();
                return n;
              }

              @Override
              public boolean isFinished() {
                return delegate.isFinished();
              }

              @Override
              public boolean isReady() {
                return delegate.isReady();
              }

              @Override
              public void setReadListener(ReadListener listener) {
                delegate.setReadListener(listener);
              }
            };
          }
        },
        response);
  }
}
