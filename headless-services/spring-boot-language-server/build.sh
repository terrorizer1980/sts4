#!/bin/bash
set -e
# modules=spring-boot-language-server,:org.springframework.tooling.jdt.ls.extension,:org.springframework.tooling.jdt.ls.commons,:org.springframework.tooling.jdt.ls.commons.test
modules=spring-boot-language-server,:org.springframework.tooling.jdt.ls.extension,:org.springframework.tooling.jdt.ls.commons
cd ../jdt-ls-extension
if command -v xvfb-run ; then
    echo "Using xvfb to run in headless environment..."
    xvfb-run ../mvnw \
    	-DskipTests \
        -DtrimStackTrace=false \
        -f ../pom.xml \
        -pl $modules \
        -am \
        clean install
else
    ../mvnw \
    	-DskipTests \
        -DtrimStackTrace=false \
        -f ../pom.xml \
        -pl $modules \
        -am \
        clean install
fi
cd ../xml-ls-extension
    ../mvnw \
    	-DskipTests \
        -DtrimStackTrace=false \
        -f ../pom.xml \
        -pl xml-ls-extension \
        -am \
        clean install
