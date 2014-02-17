$(function() {
  $('a.email').each(function() {
    var href = $(this).attr('href');
    if (href.indexOf('cryptmail:') != 0) return;
    href = href.replace('cryptmail:', '');
    var email = unescape(href.replace(/([0-9a-z]{2})/g, '%$1'));
    var text = $(this).text();
    if (text == href) text = email;
    $(this).attr('href', 'mailto:' + email).text(text);
  });
});
