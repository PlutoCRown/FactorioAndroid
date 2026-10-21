@echo off
setlocal
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%~dp0.cache\gradle"
if defined JAVA_HOME (set "TASK_JAVA=%JAVA_HOME%\bin\java.exe") else (set "TASK_JAVA=java.exe")
"%TASK_JAVA%" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
