/*
 This file is part of Privacy Friendly 2048. This app implements the functions of the
 game 2048 in a privacy friendly version.

 Privacy Friendly 2048 is free software:
 you can redistribute it and/or modify it under the terms of the
 GNU General Public License as published by the Free Software Foundation,
 either version 3 of the License, or any later version.

 Privacy Friendly App Example is distributed in the hope
 that it will be useful, but WITHOUT ANY WARRANTY; without even
 the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Privacy Friendly App Example. If not, see <http://www.gnu.org/licenses/>.
 */

package org.secuso.privacyfriendly2048.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.secuso.privacyfriendly2048.database.PFASQLiteHelper;

/**
 * @author Karola Marky
 * @version 20161214
 * Class structure taken from tutorial at http://www.androidhive.info/2016/05/android-build-intro-slider-app/
 */
public class FirstLaunchManager {
    private PFASQLiteHelper dbHandler;
    private SharedPreferences pref;
    private SharedPreferences.Editor editor;

    // shared pref mode
    private int PRIVATE_MODE = 0;

    // Shared preferences file name
    private static final String PREF_NAME = "privacy_friendly_apps";

    private static final String IS_FIRST_TIME_LAUNCH = "IsFirstTimeLaunch";

    public FirstLaunchManager(Context context) {
        pref = context.getSharedPreferences(PREF_NAME, PRIVATE_MODE);
        dbHandler = new PFASQLiteHelper(context);
        editor = pref.edit();
    }

    public void setFirstTimeLaunch(boolean isFirstTime) {
        editor.putBoolean(IS_FIRST_TIME_LAUNCH, isFirstTime);
        editor.commit();
    }

    public boolean isFirstTimeLaunch() {
        return pref.getBoolean(IS_FIRST_TIME_LAUNCH, true);
    }

    public void initFirstTimeLaunch() {
        if (pref.getBoolean(IS_FIRST_TIME_LAUNCH, true)) {
            android.util.Log.d("QB_2048_INIT_MARKER_V2", "first-time setup ran");
            setFirstTimeLaunch(false);
        }
    }

}
