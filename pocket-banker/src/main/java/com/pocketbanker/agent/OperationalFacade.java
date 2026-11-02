package com.pocketbanker.agent;

import com.pocketbanker.security.PerceptionAccess;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OperationalFacade {
  private final PerceptionAccess access;
  private final ProactiveCoordinator proactive;

  public Map<String, Object> risk(String token) {
    return proactive.riskCheck(access.requireUser(token));
  }
}
