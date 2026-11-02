package com.pocketbanker.contracts;

/** Confirmation can wake only a previously authorized task; it cannot create a financial action. */
public interface ConfirmedEventTrigger {
  void confirmedEvent(String user, String eventId, String kind);
}
