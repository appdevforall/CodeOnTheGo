package app.payload;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import app.payload.data.ResponseStore;
import app.payload.ui.DetailScreen;
import app.payload.ui.HomeScreen;
import app.payload.ui.SurveyScreen;

/**
 * Per-generation application controller: owns the navigator and the response
 * store, translates saved state into an initial screen stack and back.
 */
public final class App {

    private final Activity host;
    private final Nav nav;
    private final ResponseStore store;

    public App(Activity host) {
        this.host = host;
        this.store = new ResponseStore(host);
        this.nav = new Nav(host);
    }

    public Activity host() {
        return host;
    }

    public Nav nav() {
        return nav;
    }

    public ResponseStore store() {
        return store;
    }

    /** Builds the initial screen stack (home, plus a restored screen on top). */
    public View start(Bundle saved) {
        HomeScreen home = new HomeScreen(this);
        if (saved != null) {
            // Only the top screen contributes state, so a query is present iff home was on top.
            home.restoreQuery(saved.getString("home.query", ""));
        }
        nav.reset(home);
        if (saved != null) {
            String route = saved.getString("route", Screen.ROUTE_HOME);
            if (Screen.ROUTE_SURVEY.equals(route)) {
                nav.push(SurveyScreen.restore(this, saved));
            } else if (Screen.ROUTE_DETAIL.equals(route)) {
                long id = saved.getLong("detail.id", -1L);
                if (store.byId(id) != null) {
                    nav.push(new DetailScreen(this, id));
                }
                // else: the visit was deleted between save and restore — stay home.
            }
        }
        return nav.root();
    }

    /** Snapshot of the current UI state, framework types only. */
    public Bundle captureState() {
        Bundle out = new Bundle();
        Screen top = nav.top();
        out.putString("route", top == null ? Screen.ROUTE_HOME : top.route());
        if (top != null) {
            top.onSaveState(out);
        }
        return out;
    }
}
