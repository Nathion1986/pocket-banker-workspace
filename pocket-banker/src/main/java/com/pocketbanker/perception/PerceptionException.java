package com.pocketbanker.perception;

/** 仅固定错误码；禁止把原文、凭据、供应商异常消息写入响应。 */
public class PerceptionException extends RuntimeException {
  private final int status;

  public PerceptionException(int status, String code) {
    super(code);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
