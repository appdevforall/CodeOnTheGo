package app.payload.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.payload.R;
import app.payload.data.Response;

/**
 * Custom BaseAdapter for the home ListView (stressor 3): per-row payload
 * layout (row_response.xml) + the classic view-holder pattern. The nested
 * ViewHolder class also feeds stressor 12 (inner classes through d8).
 */
public final class ResponseAdapter extends BaseAdapter {

    /** Row view cache — set as the row's tag once, reused on recycle. */
    static final class ViewHolder {
        final TextView site;
        final TextView date;
        final TextView score;

        ViewHolder(View row) {
            site = row.findViewById(R.id.row_site);
            date = row.findViewById(R.id.row_date);
            score = row.findViewById(R.id.row_score);
        }
    }

    private final List<Response> items = new ArrayList<>();

    public void setItems(List<Response> responses) {
        items.clear();
        items.addAll(responses);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Response getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            // row_response.xml contains only framework widgets, so this works
            // even without the payload-aware inflater — but going through the
            // Activity's inflater keeps the code path uniform.
            row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_response, parent, false);
            row.setTag(new ViewHolder(row));
        }
        ViewHolder h = (ViewHolder) row.getTag();
        Response r = items.get(position);
        h.site.setText(r.siteName);
        h.date.setText(r.dateLabel());
        h.score.setText(r.scorePercent() + "%");
        return row;
    }
}
