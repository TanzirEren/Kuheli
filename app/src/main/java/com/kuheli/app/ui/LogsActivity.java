package com.kuheli.app.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.kuheli.app.R;
import com.kuheli.app.util.AppLog;
import com.kuheli.app.util.U;

public class LogsActivity extends BaseActivity {
    private TextView text;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        setupToolbar(tb, true);
        tb.setTitle("Logs");
        tb.setSubtitle("Connection and tool events. No keys or messages.");
        text = findViewById(R.id.log_text);
        scroll = findViewById(R.id.log_scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        String log = AppLog.dump();
        text.setText(log.isEmpty() ? "Nothing logged yet." : log);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.logs_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) load();
        else if (id == R.id.action_share) U.shareText(this, "Kuheli log", AppLog.dump());
        else if (id == R.id.action_clear) {
            AppLog.clear();
            load();
        } else return super.onOptionsItemSelected(item);
        return true;
    }
}
