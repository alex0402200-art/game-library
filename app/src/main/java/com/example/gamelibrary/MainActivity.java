package com.example.gamelibrary;

import android.app.Activity;
import android.content.Intent;
import android.content.UriPermission;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK_FOLDER = 1;
    private static final String PREFS = "library";
    private static final String KEY_FOLDER = "folder_uri";

    static class Game {
        final String name;
        final Uri uri;

        Game(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    private final List<Game> games = new ArrayList<>();
    private GameAdapter adapter;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));
        root.setPadding(dp(16), dp(32), dp(16), dp(8));

        TextView title = new TextView(this);
        title.setText("Game Library");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        root.addView(title);

        Button pick = new Button(this);
        pick.setText("Pilih folder game");
        pick.setOnClickListener(v -> pickFolder());
        root.addView(pick);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setText("Belum ada folder dipilih");
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        adapter = new GameAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) ->
                Toast.makeText(this,
                        "Play: " + games.get(pos).name + " (belum terhubung ke Winlator)",
                        Toast.LENGTH_SHORT).show());
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        restoreFolder();
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_FOLDER && res == RESULT_OK
                && data != null && data.getData() != null) {
            Uri uri = data.getData();
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_FOLDER, uri.toString()).apply();
            scan(uri);
        }
    }

    private void restoreFolder() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_FOLDER, null);
        if (saved == null) return;
        Uri uri = Uri.parse(saved);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(uri)) {
                scan(uri);
                return;
            }
        }
    }

    private void scan(Uri treeUri) {
        status.setText("Memindai folder...");
        new Thread(() -> {
            List<Game> found = new ArrayList<>();
            DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
            if (root != null) walk(root, 0, found);
            runOnUiThread(() -> {
                games.clear();
                games.addAll(found);
                adapter.notifyDataSetChanged();
                status.setText(found.size() + " file .exe ditemukan");
            });
        }).start();
    }

    private void walk(DocumentFile dir, int depth, List<Game> out) {
        if (depth > 4) return;
        for (DocumentFile f : dir.listFiles()) {
            String name = f.getName();
            if (name == null) continue;
            if (f.isDirectory()) {
                walk(f, depth + 1, out);
            } else if (isGameExe(name)) {
                out.add(new Game(name.substring(0, name.length() - 4), f.getUri()));
            }
        }
    }

    private boolean isGameExe(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.endsWith(".exe")) return false;
        return !(n.startsWith("unins") || n.startsWith("vc_redist")
                || n.startsWith("dxsetup") || n.startsWith("crashhandler"));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    class GameAdapter extends BaseAdapter {
        @Override public int getCount() { return games.size(); }
        @Override public Object getItem(int i) { return games.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convertView, ViewGroup parent) {
            Game g = games.get(i);

            LinearLayout tile = new LinearLayout(MainActivity.this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(6), dp(10), dp(6), dp(10));

            int hue = ((g.name.hashCode() % 360) + 360) % 360;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(Color.HSVToColor(new float[]{hue, 0.5f, 0.6f}));

            TextView icon = new TextView(MainActivity.this);
            icon.setText(g.name.isEmpty() ? "?" : g.name.substring(0, 1).toUpperCase(Locale.ROOT));
            icon.setTextColor(Color.WHITE);
            icon.setTextSize(28);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(bg);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));

            TextView label = new TextView(MainActivity.this);
            label.setText(g.name);
            label.setTextColor(Color.WHITE);
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(0, dp(6), 0, 0);
            tile.addView(label);

            return tile;
        }
    }
      }
