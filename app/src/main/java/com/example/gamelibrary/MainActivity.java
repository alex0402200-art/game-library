package com.example.gamelibrary;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.UriPermission;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_PICK_FOLDER = 1;
    private static final int REQ_PICK_COVER = 2;
    private static final String PREFS = "library";
    private static final String KEY_FOLDER = "folder_uri";

    private static final String[] PRESETS = {"Stabil", "Intermediate", "Performance"};
    private static final String[] RESOLUTIONS = {"800x600", "1280x720", "1920x1080"};
    private static final String[] DRIVERS = {"DXVK", "VKD3D", "WineD3D"};

    static class Game {
        final String name;
        final Uri uri;

        Game(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    private final List<Game> all = new ArrayList<>();
    private final List<Game> shown = new ArrayList<>();
    private final Map<String, Bitmap> coverCache = new HashMap<>();
    private GameAdapter adapter;
    private TextView status;
    private EditText searchBox;
    private JSONObject profiles = new JSONObject();
    private Game pendingCover;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadProfiles();

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

        searchBox = new EditText(this);
        searchBox.setHint("Cari game...");
        searchBox.setHintTextColor(Color.GRAY);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        root.addView(searchBox);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setText("Belum ada folder dipilih");
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        adapter = new GameAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> showProfile(shown.get(pos)));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            final Game g = shown.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle(g.name)
                    .setItems(new String[]{"Pilih gambar cover", "Hapus cover"},
                            (d, which) -> {
                                if (which == 0) {
                                    pickCover(g);
                                } else {
                                    coverFile(g).delete();
                                    coverCache.remove(coverKey(g));
                                    adapter.notifyDataSetChanged();
                                }
                            })
                    .show();
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        restoreFolder();
    }

    // ---------- Folder ----------

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_FOLDER);
    }

    private void pickCover(Game g) {
        pendingCover = g;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "Pilih gambar cover"), REQ_PICK_COVER);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;

        if (req == REQ_PICK_FOLDER) {
            Uri uri = data.getData();
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_FOLDER, uri.toString()).apply();
            scan(uri);
        } else if (req == REQ_PICK_COVER && pendingCover != null) {
            final Game g = pendingCover;
            final Uri img = data.getData();
            new Thread(() -> {
                boolean ok = saveCover(g, img);
                runOnUiThread(() -> {
                    coverCache.remove(coverKey(g));
                    adapter.notifyDataSetChanged();
                    if (!ok) {
                        Toast.makeText(this, "Gagal memuat gambar", Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
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
            Collections.sort(found, (a, b) -> a.name.compareToIgnoreCase(b.name));
            runOnUiThread(() -> {
                all.clear();
                all.addAll(found);
                applyFilter();
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

    private void applyFilter() {
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Game g : all) {
            if (q.isEmpty() || g.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(g);
        }
        adapter.notifyDataSetChanged();
        status.setText(shown.size() + " dari " + all.size()
                + " game  |  tahan lama untuk ganti cover");
    }

    // ---------- Cover ----------

    private String coverKey(Game g) {
        return Integer.toHexString(g.uri.toString().hashCode());
    }

    private File coverFile(Game g) {
        return new File(getFilesDir(), "cover_" + coverKey(g) + ".jpg");
    }

    private boolean saveCover(Game g, Uri img) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(img)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(img)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return false;
            try (FileOutputStream out = new FileOutputStream(coverFile(g))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            }
            bmp.recycle();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Bitmap getCover(Game g) {
        String k = coverKey(g);
        if (coverCache.containsKey(k)) return coverCache.get(k);
        File f = coverFile(g);
        Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        coverCache.put(k, b);
        return b;
    }

    // ---------- Profil ----------

    private File profileFile() {
        return new File(getFilesDir(), "profiles.json");
    }

    private void loadProfiles() {
        try {
            File f = profileFile();
            if (!f.exists()) return;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int r = in.read(buf, off, buf.length - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            profiles = new JSONObject(new String(buf, StandardCharsets.UTF_8));
        } catch (Exception e) {
            profiles = new JSONObject();
        }
    }

    private void saveProfiles() {
        try (FileOutputStream out = new FileOutputStream(profileFile())) {
            out.write(profiles.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Toast.makeText(this, "Gagal menyimpan profil", Toast.LENGTH_SHORT).show();
        }
    }

    private void storeProfile(Game g, Spinner preset, Spinner res, Spinner drv) {
        try {
            JSONObject p = new JSONObject();
            p.put("preset", (String) preset.getSelectedItem());
            p.put("resolution", (String) res.getSelectedItem());
            p.put("driver", (String) drv.getSelectedItem());
            profiles.put(g.uri.toString(), p);
            saveProfiles();
            adapter.notifyDataSetChanged();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal menyimpan profil", Toast.LENGTH_SHORT).show();
        }
    }

    private Spinner addSpinner(LinearLayout box, String label, String[] items, String selected) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(0, dp(10), 0, 0);
        box.addView(t);
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, items));
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(selected)) s.setSelection(i);
        }
        box.addView(s);
        return s;
    }

    private void showProfile(Game g) {
        JSONObject p = profiles.optJSONObject(g.uri.toString());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);

        Spinner sPreset = addSpinner(box, "Preset CPU (Box64)", PRESETS,
                p == null ? null : p.optString("preset"));
        Spinner sRes = addSpinner(box, "Resolusi", RESOLUTIONS,
                p == null ? null : p.optString("resolution"));
        Spinner sDrv = addSpinner(box, "Driver grafis", DRIVERS,
                p == null ? null : p.optString("driver"));

        new AlertDialog.Builder(this)
                .setTitle(g.name)
                .setView(box)
                .setPositiveButton("Simpan", (d, w) -> storeProfile(g, sPreset, sRes, sDrv))
                .setNeutralButton("Play", (d, w) -> {
                    storeProfile(g, sPreset, sRes, sDrv);
                    Toast.makeText(this,
                            "Play " + g.name + " | " + sPreset.getSelectedItem()
                                    + ", " + sRes.getSelectedItem()
                                    + ", " + sDrv.getSelectedItem()
                                    + " (belum terhubung ke Winlator)",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- Grid ----------

    class GameAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convertView, ViewGroup parent) {
            Game g = shown.get(i);

            LinearLayout tile = new LinearLayout(MainActivity.this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(6), dp(10), dp(6), dp(10));

            int hue = ((g.name.hashCode() % 360) + 360) % 360;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(Color.HSVToColor(new float[]{hue, 0.5f, 0.6f}));

            LinearLayout.LayoutParams boxSize =
                    new LinearLayout.LayoutParams(dp(84), dp(112));

            Bitmap cover = getCover(g);
            if (cover != null) {
                ImageView iv = new ImageView(MainActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(cover);
                iv.setBackground(bg);
                iv.setClipToOutline(true);
                tile.addView(iv, boxSize);
            } else {
                TextView icon = new TextView(MainActivity.this);
                icon.setText(g.name.isEmpty() ? "?" : g.name.substring(0, 1).toUpperCase(Locale.ROOT));
                icon.setTextColor(Color.WHITE);
                icon.setTextSize(28);
                icon.setGravity(Gravity.CENTER);
                icon.setBackground(bg);
                tile.addView(icon, boxSize);
            }

            TextView label = new TextView(MainActivity.this);
            label.setText(g.name);
            label.setTextColor(Color.WHITE);
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(0, dp(6), 0, 0);
            tile.addView(label);

            JSONObject p = profiles.optJSONObject(g.uri.toString());
            if (p != null) {
                TextView sub = new TextView(MainActivity.this);
                sub.setText(p.optString("preset") + " | " + p.optString("resolution"));
                sub.setTextColor(Color.GRAY);
                sub.setTextSize(10);
                sub.setGravity(Gravity.CENTER);
                tile.addView(sub);
            }

            return tile;
        }
    }
            }
