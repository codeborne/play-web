package util;

import java.io.IOException;

public class UrlEncoder {
  public static String safeUrlEncode(String url) {
    try {
      byte[] bytes = url.getBytes("UTF-8");
      StringBuilder sb = new StringBuilder(url.length() * 2);
      for (byte b : bytes) {
        if (b == ' ') sb.append('+');
        else if ((b & 0xFF) < 0x7F) sb.append((char) b);
        else sb.append(String.format("%%%02X", b));
      }
      return sb.toString();
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
