package controllers;

import org.junit.Before;
import org.junit.Test;
import play.Play;
import play.mvc.Controller;
import play.mvc.Http;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static play.Play.Mode.DEV;
import static play.Play.Mode.PROD;

public class WebTest {
  Http.Response response;
  Http.Request request;

  @Before
  public void setUp() {
    if (Play.configuration == null) Play.configuration = new Properties();

    new Controller() {{
      request = WebTest.this.request = new Http.Request();
      response = WebTest.this.response = new Http.Response();
    }};
  }

  @Test
  public void frontPagesMustBeCachedByProxyAndBrowserFor12h() throws NoSuchMethodException {
    Play.mode = PROD;
    request.action = "Web." + Web.class.getMethod("serveContentCached").getName();
    Web.setHeaders();
    assertEquals("max-age=43200", response.getHeader("Cache-Control"));
  }

  @Test
  public void otherPagesMustHaveDefaultCaching() throws NoSuchMethodException {
    Play.mode = PROD;
    request.action = "Web." + Web.class.getMethod("serveContent").getName();
    Web.setHeaders();
    assertNull(response.getHeader("Cache-Control"));
  }

  @Test
  public void cacheMustBeDisabledInDev() throws NoSuchMethodException {
    Play.mode = DEV;
    request.action = "Web." + Web.class.getMethod("serveContentCached").getName();
    Web.setHeaders();
    assertNull(response.getHeader("Cache-Control"));
  }
}
