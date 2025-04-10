@echo off
setlocal

set REPO_ROOT=%CD%
set HOOK_SOURCE=%REPO_ROOT%\.githooks\pre-commit
set HOOK_DEST=%REPO_ROOT%\.git\hooks\pre-commit

echo Installing pre-commit hook...
echo Source: %HOOK_SOURCE%
echo Destination: %HOOK_DEST%

if exist %HOOK_SOURCE% (
  copy /Y "%HOOK_SOURCE%" "%HOOK_DEST%"
  echo Git pre-commit hook copied.
  echo ✅ Git pre-commit hook installed.
) else (
  echo ❌ Error: %HOOK_SOURCE% not found.
  echo Make sure your pre-commit hook file exists at .githooks\pre-commit
  exit /b 1
)
