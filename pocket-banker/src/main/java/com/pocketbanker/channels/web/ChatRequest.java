package com.pocketbanker.channels.web;

import lombok.Data;

@Data
public class ChatRequest {
  private String text;
  private String sessionId;
}
