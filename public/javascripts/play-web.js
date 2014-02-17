var playWeb = {
  showModalDialog: function(url, selector, callback) {
    var dialog = $(selector ? selector : '#default-dialog');
    dialog.empty();
    dialog.load(url, null, function(text, status) {
      if (status=='error') return;
      else dialog.closest('.modal').modal();
      if (callback) callback();
    });
  }
};

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
