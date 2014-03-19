package ext;

import play.templates.JavaExtensions;

import static java.lang.Character.isWhitespace;
import static org.apache.commons.lang.StringUtils.*;

public class WebJavaExtensions extends JavaExtensions {
  public static String wrap(String s, int maxLineLength, String delimiter, String noWrapChars) {
    if (isEmpty(s)) return s;

    int wordLen = 0;
    StringBuilder result = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      result.append(ch);

      boolean isNoWrapChar = noWrapChars.indexOf(ch) != -1;
      if (isWhitespace(ch) || isNoWrapChar)
        wordLen = 0;
      else if (++wordLen >= maxLineLength && i+1 < s.length()) {
        result.append(delimiter);
        wordLen = 0;
      }
    }

    return result.toString();
  }

  public static String wrap(String s, int maxLineLength) {
    String zeroWidthSpace = "\u200B";
    return wrap(s, maxLineLength, zeroWidthSpace, "-");
  }

  public static String wrap(String s) {
    return wrap(s, 24);
  }
}
