@echo off
setlocal
if defined JAVA_HOME (set "WIESLER_JAVA=%JAVA_HOME%\bin\java.exe") else (set "WIESLER_JAVA=java.exe")
"%WIESLER_JAVA%" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
