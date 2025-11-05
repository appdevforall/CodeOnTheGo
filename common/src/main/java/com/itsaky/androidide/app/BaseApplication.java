/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.app;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;
import com.blankj.utilcode.util.ThrowableUtils;
import com.itsaky.androidide.buildinfo.BuildInfo;
import com.itsaky.androidide.common.R;
import com.itsaky.androidide.managers.PreferenceManager;
import com.itsaky.androidide.managers.ToolsManager;
import com.itsaky.androidide.utils.Environment;
import com.itsaky.androidide.utils.FileUtil;
import com.itsaky.androidide.utils.FlashbarUtilsKt;
import com.itsaky.androidide.utils.JavaCharacter;
import com.itsaky.androidide.utils.UrlManager;
import com.itsaky.androidide.utils.VMUtils;
import java.io.File;

public class BaseApplication extends Application {

  public static final String NOTIFICATION_GRADLE_BUILD_SERVICE = "17571";
  private static BaseApplication instance;
  private PreferenceManager mPrefsManager;

  public static BaseApplication getBaseInstance() {
    return instance;
  }

  @Override
  public void onCreate() {
    instance = this;
    Environment.init(this);
    super.onCreate();

    mPrefsManager = new PreferenceManager(this);
    JavaCharacter.initMap();

    if (!VMUtils.isJvm() || isInstrumentedTest()) {
      ToolsManager.init(this, null);
    }
  }

  public void writeException(Throwable th) {
    FileUtil.writeFile(new File(FileUtil.getExternalStorageDir(), "idelog.txt").getAbsolutePath(),
        ThrowableUtils.getFullStackTrace(th));
  }

  public PreferenceManager getPrefManager() {
    return mPrefsManager;
  }

  public File getProjectsDir() {
    return Environment.PROJECTS_DIR;
  }

  public void openTelegramGroup() {
    openTelegram(getString(R.string.telegram_group_url));
  }

  public void openTelegramChannel() {
    openTelegram(getString(R.string.telegram_channel_url));
  }

  public void openGitHub() {
    openUrl(BuildInfo.REPO_URL);
  }

  public void openWebsite() {
    openUrl(BuildInfo.PROJECT_SITE);
  }

  public void openDonationsPage() {
    openUrl(getString(R.string.sponsor_url));
  }

  public void emailUs() {
    openUrl(getString(R.string.mail_to_adfa));
  }

  public void openUrl(String url) {
      openUrl(url, null);
  }

  public void openTelegram(String url) {
    openUrl(url, "org.telegram.messenger");
  }

  public void openUrl(String url, String pkg) {
    UrlManager.INSTANCE.openUrl(url, pkg, this);
  }

  private boolean isInstrumentedTest() {
    try {
      InstrumentationRegistry.getInstrumentation();
      return true;
    } catch (IllegalStateException e) {
      return false;
    }
  }
}
