package controllers;

public class Security {
  public static String currentRole = null;

  public static boolean isConnected() {
    return false;
  }

  public static boolean check(String role) {
    return role.equals(currentRole);
  }

  public static String connected() {
    return null;
  }
}
