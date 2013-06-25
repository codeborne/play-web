package models;

import ext.CustomExtensions;
import org.junit.BeforeClass;
import org.junit.Test;
import play.Play;
import play.vfs.VirtualFile;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class WebPageTest {
  private static String currentDirName;

  @BeforeClass
  public static void setUp() throws Exception {
    Play.configuration = new Properties();
    currentDirName = new File(".").getCanonicalFile().getName();
    Play.configuration.setProperty("web.content", "../" + currentDirName);
    WebPage.ROOT = new WebPage();
  }

  @Test
  public void rootHasCanonicalPath() throws Exception {
    assertEquals(new File("../" + currentDirName).getCanonicalPath(), new WebPage().dir.getRealFile().getPath());
  }

  @Test(expected = SecurityException.class)
  public void cantGoOutsideOfRoot() throws Exception {
    WebPage.forPath("../../");
  }

  @Test
  public void allPathsEndWithSlash() throws Exception {
    WebPage root = WebPage.ROOT;
    assertEquals("/", root.path);
    assertEquals(0, root.level);
    assertNull(root.parent());

    WebPage page = WebPage.forPath("/test");
    assertTrue(page.path.endsWith("/"));
    assertEquals(1, page.level);
    assertNotNull(page.parent());

    assertEquals(2, page.children().get(0).level);
  }

  @Test
  public void preprocessDownloadableFileLinks() throws Exception {
    VirtualFile dir = mock(VirtualFile.class, RETURNS_DEEP_STUBS);
    when(dir.getRealFile().getPath()).thenReturn("/page");
    when(dir.child("document.pdf").length()).thenReturn(197133L);
    when(dir.child("document.pdf").exists()).thenReturn(true);
    when(dir.child("big.zip").length()).thenReturn(197336500L);
    when(dir.child("big.zip").exists()).thenReturn(true);
    when(dir.child("привет.zip").length()).thenReturn(197336500L);
    when(dir.child("привет.zip").exists()).thenReturn(true);
    when(dir.child("white space.zip").length()).thenReturn(197336500L);
    when(dir.child("white space.zip").exists()).thenReturn(true);
    WebPage page = new WebPage(dir);

    assertEquals("<a class=\"download pdf\" href=\"/page/document.pdf\">Document (PDF, 193 Kb)</a>",
                 page.processContent("<a href=\"document.pdf\">Document</a>"));

    assertEquals("<a class=\"download zip\" href=\"/page/big.zip\">Download (ZIP, 188.2 Mb)</a>",
                 page.processContent("<a href=\"big.zip\">Download</a>"));

    assertEquals("<a class=\"download zip unavailable\" href=\"/page/absolute.zip\">Download (ZIP, 0 Kb)</a>",
                 page.processContent("<a href=\"/page/absolute.zip\">Download</a>"));

    assertEquals("<a class=\"download zip\" href=\"/page/%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82.zip\">Download (ZIP, 188.2 Mb)</a>",
                 page.processContent("<a href=\"привет.zip\">Download</a>"));

    assertEquals("<a class=\"download zip\" href=\"/page/white+space.zip\">Download (ZIP, 188.2 Mb)</a>",
                 page.processContent("<a href=\"white space.zip\">Download</a>"));

    assertEquals("<a href=\"document.pdf\"><img src=\"something.png\"></a>",
                 page.processContent("<a href=\"document.pdf\"><img src=\"something.png\"></a>"));

    assertEquals("<a class=\"download pdf\" href=\"/page/document.pdf\" target=\"_blank\">Document (PDF, 193 Kb)</a>",
                 page.processContent("<a href=\"document.pdf\" target=\"_blank\">Document</a>"));

    assertEquals("<a target=\"_blank\" class=\"download pdf\" href=\"/page/document.pdf\">Document (PDF, 193 Kb)</a>",
                 page.processContent("<a target=\"_blank\" href=\"document.pdf\">Document</a>"));

    assertEquals("<a\thref=\"/about/\">Simple Link</a>",
                 page.processContent("<a\thref=\"/about/\">Simple Link</a>"));

    assertEquals("<a class=\"external\" href=\"http://www.something.com/document.pdf\">Simple PDF Link</a>",
                 page.processContent("<a href=\"http://www.something.com/document.pdf\">Simple PDF Link</a>"));

    assertEquals("<a class=\"email\" href=\"mailto:privet@gmail.com\">privet@gmail.com</a>",
                 page.processContent("<a href=\"mailto:privet@gmail.com\">privet@gmail.com</a>"));

    assertEquals("<a\nclass=\"email\" href=\"mailto:privet@gmail.com\">privet@gmail.com</a>",
                 page.processContent("<a\nhref=\"mailto:privet@gmail.com\">privet@gmail.com</a>"));

    assertEquals("<a\nclass=\"email\" href=\"mailto:privet@gmail.com\">privet@\ngmail\n.com</a>",
                 page.processContent("<a\nhref=\"mailto:privet@gmail.com\">privet@\ngmail\n.com</a>"));

    assertEquals("<div><a class=\"download pdf\" href=\"/page/document.pdf\">Document 1 (PDF, 193 Kb)</a> Hello <a class=\"download pdf\" href=\"/page/document.pdf\">Document 2 (PDF, 193 Kb)</a></div>",
                 page.processContent("<div><a href=\"document.pdf\">Document 1</a> Hello <a href=\"document.pdf\">Document 2</a></div>"));

    assertEquals("<a class=\"download pdf unavailable\" href=\"/page/document2.pdf\">Document (PDF, 0 Kb)</a>",
        page.processContent("<a href=\"document2.pdf\">Document</a>"));
  }

  @Test
  public void cyrillicsInLinksAreFixedForIE() throws Exception {
    VirtualFile dir = mock(VirtualFile.class, RETURNS_DEEP_STUBS);
    when(dir.getRealFile().getPath()).thenReturn("/page");
    WebPage page = new WebPage(dir);
    assertEquals("<a href=\"/map?branch=%D0%A6%D0%B5%D0%BD%D1%82%D1%80%D0%B0%D0%BB%D1%8C%D0%BD%D1%8B%D0%B9\">Центральный</a>",
        page.processContent("<a href=\"/map?branch=Центральный\">Центральный</a>"));
  }

  @Test
  public void removeBOM() {
    assertEquals(
        new WebPage().removeBOM(new String(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, (byte) 0x2B})),
        new String(new byte[] {(byte) 0x2B}));
  }

  @Test
  public void removeTags() throws Exception {
    assertEquals("test link", CustomExtensions.removeTags("test <a>link</a>"));
  }

  @Test
  public void dateForNews() throws Exception {
    WebPage.News news = WebPage.forPath("/news");

    news.path = "/hello/news/2013/05/13/";
    assertEquals(date("13.05.2013"), news.date());

    news.path = "/analytics/2012/05/10/";
    assertEquals(date("10.05.2012"), news.date());

    news.path = "/analytics/2012/05/10-2/";
    assertEquals(date("10.05.2012"), news.date());

    news.path = "/analytics/2012/05/";
    assertEquals(date("1.05.2012"), news.date());
  }

  private Date date(String ddMMyyyy) throws ParseException {
    return new SimpleDateFormat("dd.MM.yyyy").parse(ddMMyyyy);
  }
}
