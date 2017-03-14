play-web
========

Module for Play framework 1.5.x for adding of website capability based on git to your project with some WYSIWYG CMS features.

Adding to dependencies.yml
--------------------------

    require:
      - play 1.5.+
      - play-codeborne -> secure 0.6
      - play-codeborne -> rebel 0.6.5
      - play-codeborne -> web 2.7

    repositories:
      - codeborne:
        type: http
        artifact: https://repo.codeborne.com/[organization]/[module]-[revision].zip
        contains:
          - play-web

    repositories:
        - codeborne-modules-jars:
            type: http
            artifact: https://repo.codeborne.com/play-[module]/[module]-[revision].jar
            contains:
                - play-codeborne -> rebel
                - play-codeborne -> secure

Web content
-----------

This module expects that your web content is located in a separate git repository from your code (because it can get very
large and will probably will be committed to by non-developers).

The directory structure of web content will correspond to the URL structure.

    http://youdomain.com/en/mega-page  -> YOUR-WEB-CONTENT-DIR/en/mega-page

A directory under web content is a valid web page if it contains file metadata.properties, eg:

    title: Tartu Maraton
    order: 1
    template: custom

All of the properties are optional.

Configuration
--------------

Add these lines to your application.conf:

    web.content=../YOUR-WEB-CONTENT-DIR
    cron.webContentPull=0 */5 * * * ?
    web.en.home=/en
    web.ru.home=/ru

Web layout
----------

In order to specify how your web site will look like, you need to add some html files to app/views/Web.

First mandatory file is layout.html, which will be the base of all web content (it can extend main.html if you want
same look for web app and web site):

    #{extends 'main.html'/}
    #{doLayout /}

Each page may have a different page template, depending on the template name specified in metadata.properties.

Template files must reside in app/views/Web/templates

As a minimum, you need to have custom.html there, which is the default page template name.

If you want to just extend your project's main.html for your web content as well, then create custom.html like this:

    #{extends 'Web/layout.html'/}
    #{get 'content'/}

This will just display the content.html that is located in the page's directory without any additional layout.

Administration
--------------

Your project must use module play-secure (see https://repo.codeborne.com/play-secure/)

Make sure your Secure.check implementation will check for profile 'cms' in order to give some users cms editing permissions.

In your site's layout include these lines among the header:

    #{secure.check 'cms'}
      #{include 'WebAdmin/pageEditor.html'/}
      <a class="btn btn-primary" href="/webadmin/status">&{'web.admin'}</a>
    #{/secure.check}
