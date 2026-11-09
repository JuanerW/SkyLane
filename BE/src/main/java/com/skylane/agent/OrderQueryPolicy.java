package com.skylane.agent;

import java.util.Locale;
import java.util.regex.Pattern;

/** Explicit read-only list requests must have fresh database evidence before model generation. */
final class OrderQueryPolicy {
 private static final Pattern ENGLISH=Pattern.compile(
  "(?s)^(?:please\\s+)?(?:show|list|view|find|check|get|display|tell me|can (?:you|i) (?:see|view|check)|do i have|what are|where are)\\b.*\\borders\\b.*");
 private static final Pattern SHORT_ENGLISH=Pattern.compile("(?s)^(?:my\\s+)?(?:recent|latest|past|previous|all)?\\s*orders[?.!\\s]*$");
 private static final Pattern CHINESE=Pattern.compile(
  "(?s)^(?:请帮我|请|帮我|我想|我要|能不能|可以|能否|麻烦)?\\s*(?:查看|查询|查一下|看一下|看看|显示|列出|查查|查|看).*订单.*");
 private static final Pattern SHORT_CHINESE=Pattern.compile("(?s)^(?:我(?:的)?\\s*)?(?:最近|历史|全部|所有)(?:的)?订单.*");
 static boolean requestsList(String text) {
  String normalized=text.strip().toLowerCase(Locale.ROOT);
  return ENGLISH.matcher(normalized).matches() || SHORT_ENGLISH.matcher(normalized).matches()
   || CHINESE.matcher(normalized).matches() || SHORT_CHINESE.matcher(normalized).matches();
 }
 private OrderQueryPolicy() {}
}
