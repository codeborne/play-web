package models;

import java.util.List;

import static java.util.Arrays.asList;

public class MetadataField {
  public static List<MetadataField> ALL = asList(
      new MetadataField("title", "заголовок страницы, видимый пользователю."),
      new MetadataField("template", "шаблон страницы, список ниже. Если не указан, то будет использоваться <b>custom</b> - страница со свободным контентом."),
      new MetadataField("order", "порядок отображения в меню и на карте сайта, целое число. Страницы с указанным порядком будут всегда перед теми, для которых он не указан. Другие страницы будут сортироваться в алфавитном порядке названий их папок.", "number", null),
      new MetadataField("description", "описание страницы для поисковиков. Будет вставлено в html meta-тег."),
      new MetadataField("keywords", "ключевые слова для поисковиков. Будут вставлены в html meta-тег."),
      new MetadataField("tags", "разделы для новостей и аналитики, например: <i>Частным клиентам, Private Banking</i>"),
      new MetadataField("alias", "ссылка, которая делает редирект на эту страницу, например, 1025 - для обратной совместимости со старым сайтом"),
      new MetadataField("mainMenu", "страница показывается в главном меню (только для страниц верхнего уровня)", "checkbox", false),
      new MetadataField("hidden", "<b>true</b> - чтобы спрятать страницу из меню", "checkbox", false),
      new MetadataField("submenuColumns", "позволяет переопределить количество колонок подменю (по умолчанию, 5)", "number", 5),
      new MetadataField("redirect", "чтобы страница перенаправляла на другую указанную страницу"),
      new MetadataField("contentFrom", "если у страницы нет своего содержания и надо показывать содержание другой страницы (например, в английской версии показывается русская страница)"),
      new MetadataField("contentFromNewestChild", "<b>true</b>, если у страницы нет своего содержания и надо показывать содержание от самой новой под-страницы (например, когда под-страницы - это годы)"),
      new MetadataField("sitemap", "<b>false</b>, чтобы страница не показывалась на карте сайта", "checkbox", true)
  );

  public String name;
  public String help;
  public String type = "text";
  public Object defaultValue;

  public MetadataField(String name, String help) {
    this.name = name;
    this.help = help;
  }

  public MetadataField(String name, String help, String type, Object defaultValue) {
    this.name = name;
    this.help = help;
    this.type = type;
    this.defaultValue = defaultValue;
  }
}
