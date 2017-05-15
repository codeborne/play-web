package controllers;

import org.junit.Test;
import play.Play;
import play.mvc.results.Redirect;

import static java.util.Arrays.asList;
import static org.junit.Assert.*;

public class WebTest {
  @Test
  public void redirectsToLocaleDefaultHomePage() {
    Play.configuration.setProperty("web.ru.home", "/retail/ru");

    Redirect redirect = new Web().locale("ru");

    assertEquals("/retail/ru", redirect.getUrl());
  }

  @Test
  public void usesDefaultLanguage_if_unknownLocaleIsGiven() {
    Play.configuration.setProperty("web.ru.home", "/retail/ru");
    Play.langs = asList("ru", "en");

    Redirect redirect = new Web().locale("x<qss>\"");

    assertEquals("/retail/ru", redirect.getUrl());
  }
}