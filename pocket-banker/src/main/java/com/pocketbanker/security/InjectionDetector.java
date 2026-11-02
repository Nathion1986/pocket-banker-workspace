package com.pocketbanker.security;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 注入防御——检测 prompt injection 攻击（§2.3 安全五件套之一）。 本地模式匹配；BackendFacade接入模型信号复核，BackendCircuit负责持久熔断。
 */
@Component
public class InjectionDetector {

  // 已知注入模式
  private static final List<Pattern> INJECTION_PATTERNS =
      List.of(
          Pattern.compile(
              "忽略.{0,20}(权限|规则|指令|限制|设置)|绕过.{0,12}(权限|验证)|扮演.{0,12}(管理员|系统)|泄露.{0,12}(提示词|密钥)"),
          Pattern.compile("(?i)ignore\\s+(all\\s+)?previous\\s+(instructions?|prompts?|rules?)"),
          Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|above|prior)"),
          Pattern.compile("(?i)you\\s+are\\s+now\\s+(an?|the)\\s*(admin|root|developer|superuser)"),
          Pattern.compile("(?i)system\\s*:\\s*", Pattern.MULTILINE),
          Pattern.compile("(?i)\\[INST\\].*\\[/INST\\]"),
          Pattern.compile("(?i)jailbreak|DAN\\s+mode|developer\\s+mode"),
          Pattern.compile("(?i)reveal\\s+(your|the)\\s+(system\\s+)?prompt"),
          Pattern.compile("(?i)print\\s+(your|the)\\s+(instructions?|prompt|rules?)"),
          // SQL 注入式
          Pattern.compile("('|\\\")\\s*(OR|AND)\\s*\\d+\\s*=\\s*\\d+", Pattern.CASE_INSENSITIVE),
          Pattern.compile("(?i)(DROP|DELETE|TRUNCATE)\\s+(TABLE|FROM)", Pattern.CASE_INSENSITIVE),
          // 命令注入式
          Pattern.compile("(?i)(rm\\s+-rf|sudo\\s|curl\\s|wget\\s|nc\\s+-)"));

  /**
   * 检测输入是否包含注入攻击。
   *
   * @return null=安全；非null=检测到的注入模式描述
   */
  public String detect(String input) {
    if (input == null || input.isBlank()) return null;
    for (Pattern p : INJECTION_PATTERNS) {
      if (p.matcher(input).find()) {
        return "检测到注入模式: " + p.pattern().substring(0, Math.min(30, p.pattern().length())) + "...";
      }
    }
    return null;
  }
}
