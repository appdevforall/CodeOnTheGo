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

package org.appdevforall.codeonthego.activities;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;

import org.appdevforall.codeonthego.app.IDEActivity;
import com.itsaky.androidide.databinding.ActivityCrashHandlerBinding;
import org.appdevforall.codeonthego.fragments.CrashReportFragment;

public class CrashHandlerActivity extends IDEActivity {

    public static final String REPORT_ACTION = "org.appdevforall.codeonthego.REPORT_CRASH";
    public static final String TRACE_KEY = "crash_trace";
    private ActivityCrashHandlerBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final var extra = getIntent().getExtras();
        if (extra == null) {
            finishAffinity();
            return;
        }

        final var fragment = CrashReportFragment.newInstance();

        getSupportFragmentManager()
                .beginTransaction()
                .replace(binding.getRoot().getId(), fragment, "crash_report_fragment")
                .addToBackStack(null)
                .commit();
    }

    @Override
    @NonNull
    protected View bindLayout() {
        binding = ActivityCrashHandlerBinding.inflate(getLayoutInflater());
        return binding.getRoot();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
