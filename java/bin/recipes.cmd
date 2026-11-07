@echo off
rem Windows launcher for the recipes; see bin/recipes.
set "JAR=%~dp0..\build\recipes\recipes.jar"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
"%JAVA%" --add-opens=java.base/java.nio=ALL-UNNAMED --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dio.netty.noUnsafe=false %JAVA_OPTS% -jar "%JAR%" %*
