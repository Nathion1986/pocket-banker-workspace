package com.pocketbanker.channels.web;

import lombok.Data;

@Data
public class VerifyRequest {
  private String code;
}
