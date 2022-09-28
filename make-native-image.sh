#!/bin/bash -e
cd build
native-image -jar HentaiAtHome.jar -H:+ReportExceptionStackTraces -H:SerializationConfigurationResources=META-INF/native-image/serialization-config.json --enable-url-protocols=https,http --install-exit-handlers
cd -
