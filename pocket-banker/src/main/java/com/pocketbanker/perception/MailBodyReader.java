package com.pocketbanker.perception;

import jakarta.mail.*;
import jakarta.mail.internet.ContentType;
import java.io.*;
import java.nio.charset.Charset;
import org.springframework.stereotype.Component;

/** 仅 MIME 正文；不下载附件，不执行 HTML，也不跟随外部链接。 */
@Component
public class MailBodyReader {
  public static class ContentLimit extends IOException {
    public ContentLimit() {
      super("mail_content_limit");
    }
  }

  private static final int MAX_BYTES = 131072;
  private static final int MAX_CHARS = 32768;

  public String read(Part part) throws MessagingException, IOException {
    return read(part, 0, new int[] {0});
  }

  private String read(Part part, int depth, int[] parts) throws MessagingException, IOException {
    if (depth > 8 || ++parts[0] > 40) throw new ContentLimit();
    if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null)
      return "";
    if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
      if (part.getSize() > MAX_BYTES) throw new ContentLimit();
      byte[] bytes;
      try (var input = part.getInputStream()) {
        bytes = input.readNBytes(MAX_BYTES + 1);
      }
      if (bytes.length > MAX_BYTES) throw new ContentLimit();
      String charset = new ContentType(part.getContentType()).getParameter("charset");
      String text =
          new String(
              bytes,
              charset == null ? java.nio.charset.StandardCharsets.UTF_8 : Charset.forName(charset));
      if (part.isMimeType("text/html")) {
        text =
            text.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", " ")
                .replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
      }
      if (text.length() > MAX_CHARS) throw new ContentLimit();
      return text.strip();
    }
    if (part.isMimeType("multipart/*")) {
      Multipart multi = (Multipart) part.getContent();
      if (multi.getCount() > 40) throw new ContentLimit();
      if (part.isMimeType("multipart/alternative")) {
        for (int i = 0; i < multi.getCount(); i++) {
          if (multi.getBodyPart(i).isMimeType("text/plain"))
            return read(multi.getBodyPart(i), depth + 1, parts);
        }
      }
      StringBuilder content = new StringBuilder();
      for (int i = 0; i < multi.getCount(); i++) {
        content.append(read(multi.getBodyPart(i), depth + 1, parts)).append('\n');
        if (content.length() > MAX_CHARS) throw new ContentLimit();
      }
      return content.toString().strip();
    }
    return "";
  }
}
