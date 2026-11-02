package com.pocketbanker.contracts;

public class BackendException extends RuntimeException {
  private final int status;

  public BackendException(int status, String code) {
    super(code);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
