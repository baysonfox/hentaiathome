#!/bin/bash -e

cd build
cp -R ../src/hath/base/META-INF .
jar cvfm HentaiAtHome.jar ../src/hath/base/HentaiAtHome.manifest hath/base META-INF
jar cvfm HentaiAtHomeGUI.jar ../src/hath/gui/HentaiAtHomeGUI.manifest ../src/hath/gui/*.png hath/gui
cd ..
