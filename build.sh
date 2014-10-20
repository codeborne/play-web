#!/bin/bash
MODULE="web"
VERSION=`grep self conf/dependencies.yml | sed "s/.*$MODULE //"`
DESTINATION=/var/www/repo/play-$MODULE
TARGET=$DESTINATION/$MODULE-$VERSION.zip

rm -fr dist
play dependencies --sync || exit $?
play build-module || exit $?
zip --delete dist/*.zip "modules/secure"

if [ -d $DESTINATION ]; then
  if [ -e $TARGET ]; then
      echo "Not publishing, $MODULE-$VERSION.zip already exists"
  else
      cp dist/*.zip $TARGET || exit $?
      echo "Package is available at http://repo.codeborne.com/play-$MODULE/$MODULE-$VERSION.zip"
  fi
fi
