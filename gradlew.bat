@rem Gradle wrapper for Windows
@echo off
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set CLASSPATH=%DIRNAME%\gradle\wrapper\gradle-wrapper.jar

if not exist "%CLASSPATH%" (
    echo Gradle wrapper JAR not found at %CLASSPATH%
    exit /b 1
)

@rem Find java
set JAVA_EXE=
if not "%JAVA_HOME%"=="" (
    if exist "%JAVA_HOME%\bin\java.exe" set JAVA_EXE=%JAVA_HOME%\bin\java.exe
    if exist "%JAVA_HOME%\java.exe" set JAVA_EXE=%JAVA_HOME%\java.exe
)
if "%JAVA_EXE%"=="" set JAVA_EXE=java.exe

@rem Execute Gradle
"%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*