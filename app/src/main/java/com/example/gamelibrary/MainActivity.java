package com.example.gamelibrary;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.UriPermission;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_PICK_FOLDER = 1;
    private static final int REQ_PICK_COVER = 2;
    private static final String PREFS = "library";
    private static final String KEY_FOLDER = "folder_uri";
    private static final String KEY_FAV = "favorites";

    private static final int BLUE = Color.parseColor("#0A9BFF");
    private static final int PANEL = Color.parseColor("#CC041233");

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
    private final Set<String> favs = new HashSet<>();
    private GameAdapter adapter;
    private TextView status;
    private TextView tabGame;
    private TextView tabFav;
    private EditText searchBox;
    private JSONObject profiles = new JSONObject();
    private Game pendingCover;
    private Uri currentFolder;
    private int currentTab = 0;

    // ---------- Latar belakang ----------

    static class BackgroundView extends View {
        private final Paint fill = new Paint();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<float[]> shapes = new ArrayList<>();

        BackgroundView(android.content.Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(5f);
            line.setColor(Color.parseColor("#661B6FE0"));
            Random r = new Random(7);
            for (int i = 0; i < 34; i++) {
                shapes.add(new float[]{r.nextFloat(), r.nextFloat(),
                        r.nextInt(4), 24 + r.nextInt(30)});
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            fill.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                    Color.parseColor("#020A2C"), Color.parseColor("#0A55E6"),
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, fill);
            for (float[] s : shapes) {
                float x = s[0] * w;
                float y = s[1] * h;
                float r = s[3];
                int type = (int) s[2];
                if (type == 0) {
                    canvas.drawCircle(x, y, r, line);
                } else if (type == 1) {
                    canvas.drawRect(x - r, y - r, x + r, y + r, line);
                } else if (type == 2) {
                    Path p = new Path();
                    p.moveTo(x, y - r);
                    p.lineTo(x + r, y + r);
                    p.lineTo(x - r, y + r);
                    p.close();
                    canvas.drawPath(p, line);
                } else {
                    canvas.drawLine(x - r, y - r, x + r, y + r, line);
                    canvas.drawLine(x + r, y - r, x - r, y + r, line);
                }
            }
        }
    }

    // ---------- onCreate ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadProfiles();
        favs.addAll(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(KEY_FAV, new HashSet<String>()));

        FrameLayout frame = new FrameLayout(this);
        frame.addView(new BackgroundView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

        // ----- Kolom kiri -----
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(Color.parseColor("#B3041233"));
        tabGame = makeTab("Game", 0);
        tabFav = makeTab("Favorit", 1);
        tabs.addView(tabGame);
        tabs.addView(tabFav);
        left.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        searchBox = new EditText(this);
        searchBox.setHint("Cari game...");
        searchBox.setHintTextColor(Color.parseColor("#9FB4D8"));
        searchBox.setTextColor(Color.WHITE);
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.getBackground().setTint(BLUE);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(6);
        left.addView(searchBox, sp);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9FB4D8"));
        status.setTextSize(11);
        status.setText("Belum ada folder dipilih");
        status.setPadding(0, dp(4), 0, dp(4));
        left.addView(status);

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(120));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new GameAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> showProfile(shown.get(pos)));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            showGameMenu(shown.get(pos));
            return true;
        });
        left.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // ----- Kolom kanan -----
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), 0, 0, 0);

        right.addView(buildLogo(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(10);
        right.addView(menu, mp);

        menu.addView(menuItem("▶", "Pilih Folder Game", true, v -> pickFolder()));
        menu.addView(menuItem("⚙", "Pengaturan", false, v -> showSettings()));
        menu.addView(menuItem("ⓘ", "Tentang", false, v -> showAbout()));
        menu.addView(menuItem("✕", "Keluar", false, v -> finish()));

        row.addView(right, new LinearLayout.LayoutParams(dp(230),
                ViewGroup.LayoutParams.MATCH_PARENT));

        frame.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);

        selectTab(0);
        restoreFolder();
    }

    // ---------- Komponen UI ----------

    private TextView makeTab(String text, final int index) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(18), dp(8), dp(18), dp(8));
        t.setOnClickListener(v -> selectTab(index));
        return t;
    }

    private void selectTab(int index) {
        currentTab = index;
        tabGame.setBackgroundColor(index == 0 ? BLUE : Color.TRANSPARENT);
        tabFav.setBackgroundColor(index == 1 ? BLUE : Color.TRANSPARENT);
        if (adapter != null && status != null) applyFilter();
    }

    private View buildLogo() {
        Bitmap logo = null;
        try (InputStream in = getAssets().open("logo.png")) {
            logo = BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
        }
        if (logo != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(logo);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(Color.parseColor("#0B1F7A"));
            iv.setBackground(bg);
            iv.setClipToOutline(true);
            return iv;
        }
        TextView t = new TextView(this);
        t.setText("EXE EMULATOR");
        t.setTextColor(Color.WHITE);
        t.setTextSize(22);
        t.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD_ITALIC));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private View menuItem(String icon, String label, boolean highlight, View.OnClickListener l) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        item.setBackgroundColor(highlight ? BLUE : Color.TRANSPARENT);
        item.setOnClickListener(l);

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextColor(Color.WHITE);
        ic.setTextSize(20);
        ic.setGravity(Gravity.CENTER);
        item.addView(ic, new LinearLayout.LayoutParams(dp(34),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tx = new TextView(this);
        tx.setText(label);
        tx.setTextColor(Color.WHITE);
        tx.setTextSize(16);
        tx.setPadding(dp(10), 0, 0, 0);
        item.addView(tx);
        return item;
    }

    // ---------- Dialog gelap ----------

    private void showDark(AlertDialog.Builder b) {
        AlertDialog d = b.create();
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        }
    }

    private AlertDialog.Builder darkBuilder() {
        return new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    }

    private void showGameMenu(final Game g) {
        final boolean isFav = favs.contains(g.uri.toString());
        showDark(darkBuilder()
                .setTitle(g.name)
                .setItems(new String[]{"Pilih gambar cover", "Hapus cover",
                                isFav ? "Hapus dari favorit" : "Tambah ke favorit"},
                        (d, which) -> {
                            if (which == 0) {
                                pickCover(g);
                            } else if (which == 1) {
                                coverFile(g).delete();
                                coverCache.remove(coverKey(g));
                                adapter.notifyDataSetChanged();
                            } else {
                                if (isFav) favs.remove(g.uri.toString());
                                else favs.add(g.uri.toString());
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                        .putStringSet(KEY_FAV, new HashSet<>(favs)).apply();
                                applyFilter();
                            }
                        }));
    }

    private void showSettings() {
        showDark(darkBuilder()
                .setTitle("Pengaturan")
                .setItems(new String[]{"Pindai ulang folder", "Hapus semua cover"},
                        (d, which) -> {
                            if (which == 0) {
                                if (currentFolder != null) scan(currentFolder);
                                else Toast.makeText(this, "Pilih folder dulu",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                File[] files = getFilesDir().listFiles();
                                if (files != null) {
                                    for (File f : files) {
                                        if (f.getName().startsWith("cover_")) f.delete();
                                    }
                                }
                                coverCache.clear();
                                adapter.notifyDataSetChanged();
                            }
                        })
                .setNegativeButton("Tutup", null));
    }

    private void showAbout() {
        showDark(darkBuilder()
                .setTitle("EXE Emulator")
                .setMessage("Pustaka game PC.\nVersi 0.2\n\nTombol Play belum terhubung ke Winlator.")
                .setPositiveButton("OK", null));
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

    private void scan(final Uri treeUri) {
        currentFolder = treeUri;
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
            if (currentTab == 1 && !favs.contains(g.uri.toString())) continue;
            if (q.isEmpty() || g.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(g);
        }
        adapter.notifyDataSetChanged();
        status.setText(shown.size() + " dari " + all.size()
                + " game  |  tahan lama untuk cover / favorit");
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
        t.setTextColor(Color.WHITE);
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

    private void showProfile(final Game g) {
        JSONObject p = profiles.optJSONObject(g.uri.toString());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);

        final Spinner sPreset = addSpinner(box, "Preset CPU (Box64)", PRESETS,
                p == null ? null : p.optString("preset"));
        final Spinner sRes = addSpinner(box, "Resolusi", RESOLUTIONS,
                p == null ? null : p.optString("resolution"));
        final Spinner sDrv = addSpinner(box, "Driver grafis", DRIVERS,
                p == null ? null : p.optString("driver"));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        showDark(darkBuilder()
                .setTitle(g.name)
                .setView(sv)
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
                .setNegativeButton("Batal", null));
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
            tile.setPadding(dp(6), dp(8), dp(6), dp(8));

            int hue = ((g.name.hashCode() % 360) + 360) % 360;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(Color.HSVToColor(new float[]{hue, 0.5f, 0.6f}));

            LinearLayout.LayoutParams boxSize =
                    new LinearLayout.LayoutParams(dp(100), dp(100));

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
                icon.setTextSize(30);
                icon.setTypeface(Typeface.DEFAULT_BOLD);
                icon.setGravity(Gravity.CENTER);
                icon.setBackground(bg);
                tile.addView(icon, boxSize);
            }

            TextView label = new TextView(MainActivity.this);
            label.setText((favs.contains(g.uri.toString()) ? "★ " : "") + g.name);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(0, dp(6), 0, 0);
            tile.addView(label);

            JSONObject p = profiles.optJSONObject(g.uri.toString());
            if (p != null) {
                TextView sub = new TextView(MainActivity.this);
                sub.setText(p.optString("preset") + " | " + p.optString("resolution"));
                sub.setTextColor(Color.parseColor("#9FB4D8"));
                sub.setTextSize(10);
                sub.setGravity(Gravity.CENTER);
                tile.addView(sub);
            }

            return tile;
        }
    }
}
