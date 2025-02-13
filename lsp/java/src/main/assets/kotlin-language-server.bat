@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  kotlin-language-server startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and KOTLIN_LANGUAGE_SERVER_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-DkotlinLanguageServer.version=1.3.13"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\server-1.3.13.jar;%APP_HOME%\lib\shared-1.3.13.jar;%APP_HOME%\lib\kotlin-scripting-jvm-host-unshaded-2.1.0.jar;%APP_HOME%\lib\kotlin-scripting-compiler-2.1.0.jar;%APP_HOME%\lib\ktfmt-b5d31d1.jar;%APP_HOME%\lib\kotlin-compiler-2.1.0.jar;%APP_HOME%\lib\exposed-dao-0.37.3.jar;%APP_HOME%\lib\exposed-jdbc-0.37.3.jar;%APP_HOME%\lib\exposed-core-0.37.3.jar;%APP_HOME%\lib\kotlin-scripting-compiler-impl-2.1.0.jar;%APP_HOME%\lib\kotlin-reflect-2.1.0.jar;%APP_HOME%\lib\kotlin-scripting-jvm-2.1.0.jar;%APP_HOME%\lib\kotlin-scripting-common-2.1.0.jar;%APP_HOME%\lib\kotlinx-coroutines-core-jvm-1.6.4.jar;%APP_HOME%\lib\kotlin-stdlib-jdk8-2.1.0.jar;%APP_HOME%\lib\kotlin-stdlib-jdk7-2.1.0.jar;%APP_HOME%\lib\kotlin-stdlib-2.1.0.jar;%APP_HOME%\lib\org.eclipse.lsp4j-0.21.2.jar;%APP_HOME%\lib\org.eclipse.lsp4j.jsonrpc-0.21.2.jar;%APP_HOME%\lib\kotlin-sam-with-receiver-compiler-plugin-2.1.0.jar;%APP_HOME%\lib\fernflower-1.0.jar;%APP_HOME%\lib\h2-1.4.200.jar;%APP_HOME%\lib\jcommander-1.78.jar;%APP_HOME%\lib\sqlite-jdbc-3.41.2.1.jar;%APP_HOME%\lib\google-java-format-1.8.jar;%APP_HOME%\lib\guava-33.3.0-jre.jar;%APP_HOME%\lib\annotations-13.0.jar;%APP_HOME%\lib\gson-2.10.1.jar;%APP_HOME%\lib\kotlin-script-runtime-2.1.0.jar;%APP_HOME%\lib\trove4j-1.0.20200330.jar;%APP_HOME%\lib\jline-3.24.1.jar;%APP_HOME%\lib\slf4j-api-1.7.25.jar;%APP_HOME%\lib\jna-4.2.2.jar;%APP_HOME%\lib\failureaccess-1.0.2.jar;%APP_HOME%\lib\listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar;%APP_HOME%\lib\jsr305-3.0.2.jar;%APP_HOME%\lib\checker-qual-3.43.0.jar;%APP_HOME%\lib\error_prone_annotations-2.28.0.jar


@rem Execute kotlin-language-server
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %KOTLIN_LANGUAGE_SERVER_OPTS%  -classpath "%CLASSPATH%" org.javacs.kt.MainKt %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable KOTLIN_LANGUAGE_SERVER_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%KOTLIN_LANGUAGE_SERVER_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
