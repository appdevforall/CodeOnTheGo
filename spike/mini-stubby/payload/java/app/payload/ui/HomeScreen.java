package app.payload.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.payload.App;
import app.payload.R;
import app.payload.Screen;
import app.payload.data.Response;

/**
 * Home: sparkline trend card + search box + response list + "new visit" entry
 * point. Inflating screen_home.xml is where the custom-view-in-XML stressor
 * fires (see SparklineView) — this screen only works under the shell's
 * payload-classloader-aware inflater.
 */
public final class HomeScreen extends Screen {

    private static final String KEY_QUERY = "home.query";

    private final App app;

    private ResponseAdapter adapter;
    private SparklineView sparkline;
    private TextView trendCaption;
    private TextView emptyHint;

    /** Every visit, unfiltered — the search box narrows this into the adapter. */
    private final List<Response> allVisits = new ArrayList<>();
    /** Current site-name filter; survives hot-reload via onSaveState/restoreQuery. */
    private String query = "";

    public HomeScreen(App app) {
        this.app = app;
    }

    /** Called before the view exists, when a new generation restores our state. */
    public void restoreQuery(String saved) {
        query = saved == null ? "" : saved;
    }

    @Override
    public String route() {
        return ROUTE_HOME;
    }

    @Override
    public void onSaveState(Bundle out) {
        out.putString(KEY_QUERY, query);
    }

    @Override
    protected View createView() {
        Activity host = app.host();
        View v = LayoutInflater.from(host).inflate(R.layout.screen_home, null);

        sparkline = (SparklineView) v.findViewById(R.id.home_sparkline);
        sparkline.setColors(
                host.getColor(R.color.fs_accent),
                host.getColor(R.color.fs_accent_fill),
                host.getColor(R.color.fs_accent_dark));
        trendCaption = v.findViewById(R.id.home_trend_caption);
        emptyHint = v.findViewById(R.id.home_empty);

        adapter = new ResponseAdapter();
        ListView list = v.findViewById(R.id.home_list);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                app.nav().push(new DetailScreen(app, adapter.getItem(position).id)));

        EditText search = v.findViewById(R.id.home_search);
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                applyFilter();
            }
        });

        Button newButton = v.findViewById(R.id.home_new_button);
        newButton.setOnClickListener(x -> showNewSurveyDialog());
        return v;
    }

    /** Refreshes data every time we surface (initial push AND pops back to home). */
    @Override
    public void onShow() {
        allVisits.clear();
        allVisits.addAll(app.store().all());
        applyFilter();

        int n = allVisits.size();
        trendCaption.setText(app.host().getString(R.string.fs_trend_label)
                + (n > 0 ? " · " + n + (n == 1 ? " visit" : " visits") : ""));
        // The trend always covers every visit — the search box narrows the list only.
        sparkline.setValues(app.store().scoreHistory(), true);
    }

    /** Pushes the visits whose site name contains {@link #query} into the adapter. */
    private void applyFilter() {
        String needle = query.trim().toLowerCase(Locale.getDefault());
        List<Response> matches;
        if (needle.isEmpty()) {
            matches = allVisits;
        } else {
            matches = new ArrayList<>();
            for (Response r : allVisits) {
                if (r.siteName != null
                        && r.siteName.toLowerCase(Locale.getDefault()).contains(needle)) {
                    matches.add(r);
                }
            }
        }
        adapter.setItems(matches);

        // Two different empty states: nothing recorded yet vs. nothing matching.
        emptyHint.setText(allVisits.isEmpty() ? R.string.fs_empty : R.string.fs_no_matches);
        emptyHint.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** AlertDialog "add" flow (stressor 7a): name the site, then start the survey. */
    private void showNewSurveyDialog() {
        Activity host = app.host();
        EditText input = new EditText(host);
        input.setHint(R.string.fs_site_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(1);

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * host.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(host)
                .setTitle(R.string.fs_new_survey_title)
                .setView(box)
                .setPositiveButton(R.string.fs_start, (d, w) -> {
                    String site = input.getText().toString().trim();
                    if (site.isEmpty()) {
                        Toast.makeText(host, R.string.fs_site_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    app.nav().push(new SurveyScreen(app, site));
                })
                .setNegativeButton(R.string.fs_cancel, null)
                .show();
    }
}
