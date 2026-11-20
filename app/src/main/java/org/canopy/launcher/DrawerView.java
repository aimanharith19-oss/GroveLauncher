/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen app drawer: search, your own tabs, drawer folders, app/web shortcuts, widgets placed
 * between apps, multi-select, frequent apps, wave A–Z scrubber, pinch to resize, swipe down to close.
 */
public class DrawerView extends FrameLayout {
    private final LauncherActivity launcher;
    private final LinearLayout content;
    private final EditText search;
    private final ScrollView scroll;
    private final DrawerGrid grid;
    private String[] sections = new String[0];
    private int[] sectionItems = new int[0];
    private final TextView empty, searchEverywhere;
    private final FrameLayout box, stack;
    private TextView untabButton;
    private final LinearLayout searchBar, tabRow;
    private final ImageView miniSearch;
    private float collapse;              // 0 = full search bar, 1 = folded into the icon
    private android.animation.ValueAnimator collapseAnim;
    private float animTarget = -1f;
    private int barFullH;
    private final AcrylicBar acrylic;
    private final LinearLayout scrollContent;
    private final ImageView clearBtn;
    private boolean swipingTab, downOnTabs;
    /** Live results (apps, actions in other apps, contacts, settings, calendar, web) right under the search field. */
    private final SearchOverlay inline;
    private boolean searchMode;
    private final AlphabetWave wave;
    private final LinearLayout tabBar, frequent, selectBar;
    private final TextView selectCount;
    private final HorizontalScrollView tabScroll;
    private final Ui.Palette pal;
    private final int slop;
    private boolean open;
    private float downX, downY;
    private boolean draggingClose, pinching;
    private VelocityTracker vt;
    private int insetBottom;
    /** "all", "personal", "work" or "t" + tab id */
    private String tab = "all";
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selecting;
    private List<Object> flat = new ArrayList<>();

    public DrawerView(LauncherActivity c) {
        super(c);
        launcher = c;
        pal = Ui.palette(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setBackgroundColor(Ui.alpha(pal.bg, Math.round(Prefs.drawerOpacity(c) * 2.55f)));

        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, new LayoutParams(-1, -1));
        // Fluent layout: the lists fill the drawer, and the search bar + tabs float over them on an acrylic bar.
        stack = new FrameLayout(c);
        content.addView(stack, new LinearLayout.LayoutParams(-1, 0, 1));
        acrylic = new AcrylicBar(c, pal.bg);
        acrylic.setClickable(true); // taps on the bar never fall through to apps scrolled beneath it

        // Google-style pill search bar: search icon, field, clear, voice, menu — all inside the pill.
        final LinearLayout header = new LinearLayout(c);
        searchBar = header;
        header.setGravity(Gravity.CENTER_VERTICAL);
        // Same design as the tab names: a flat soft pill, tab colours and type
        header.setBackground(Ui.round(Ui.alpha(pal.accent, 0x1F), Ui.dp(c, 20)));
        header.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 4), 0);
        ImageView glass = new ImageView(c);
        glass.setImageDrawable(Ui.tinted(c, R.drawable.ic_search, pal.accent));
        header.addView(glass, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
        search = new EditText(c);
        Ui.tintField(c, search);
        search.setHint("Search");
        search.setTextColor(pal.text);
        search.setHintTextColor(pal.subtext);
        search.setTextSize(15);
        search.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        search.setBackground(null);
        search.setSingleLine(true);
        search.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 4), 0);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        header.addView(search, new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1));
        int bs = Ui.dp(c, 38);
        clearBtn = Ui.iconButton(c, R.drawable.ic_close, pal.subtext, new OnClickListener() {
            @Override public void onClick(View v) { search.setText(""); }
        });
        clearBtn.setVisibility(GONE);
        header.addView(clearBtn, new LinearLayout.LayoutParams(bs, bs));
        ImageView mic = Ui.iconButton(c, R.drawable.ic_mic, pal.subtext, new OnClickListener() {
            @Override public void onClick(View v) { hideIme(); launcher.startVoiceSearch(); }
        });
        mic.setContentDescription("Voice search and commands");
        header.addView(mic, new LinearLayout.LayoutParams(bs, bs));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 44));
        hlp.setMargins(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
        acrylic.addView(header, hlp);

        searchEverywhere = Ui.text(c, "", 14, pal.accent);
        searchEverywhere.setPadding(Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 2));
        searchEverywhere.setVisibility(GONE);
        searchEverywhere.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { launcher.openSearch(search.getText().toString()); }
        });
        acrylic.addView(searchEverywhere);

        tabScroll = new HorizontalScrollView(c);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFillViewport(true);
        tabBar = new LinearLayout(c);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
        tabScroll.addView(tabBar, new LayoutParams(-1, -2));
        // Tab row: when you scroll down the search bar folds into a search icon at the start of this row.
        tabRow = new LinearLayout(c);
        tabRow.setGravity(Gravity.CENTER_VERTICAL); // search icon, tab names and ⋮ all on one centre line
        tabRow.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 2));
        miniSearch = Ui.iconButton(c, R.drawable.ic_search, pal.accent, new OnClickListener() {
            @Override public void onClick(View v) { expandSearch(true); }
        });
        // Same design as the tabs: a rounded pill of the same height, with the icon in the tab text colour.
        miniSearch.setImageDrawable(Ui.tinted(c, R.drawable.ic_search, pal.subtext));
        miniSearch.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        miniSearch.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
        miniSearch.setBackground(Ui.pressable(c, 0x00000000, Ui.dp(c, 20))); // just the icon (ripple on tap)
        miniSearch.setContentDescription("Search");
        miniSearch.setVisibility(GONE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
        mlp.gravity = Gravity.CENTER_VERTICAL;
        mlp.setMargins(Ui.dp(c, 6), 0, 0, 0);
        tabRow.addView(miniSearch, mlp);
        tabRow.addView(tabScroll, new LinearLayout.LayoutParams(0, -2, 1));
        // ⋮ drawer options, always at the right edge
        ImageView more = Ui.iconButton(c, R.drawable.ic_more, pal.subtext, new OnClickListener() {
            @Override public void onClick(View v) { createMenu(); }
        });
        more.setContentDescription("Drawer options");
        // matching pill, like the search icon and the tabs
        more.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        more.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
        more.setBackground(Ui.pressable(c, 0x00000000, Ui.dp(c, 20))); // just the icon
        LinearLayout.LayoutParams molp = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
        molp.gravity = Gravity.CENTER_VERTICAL;
        molp.setMargins(0, 0, Ui.dp(c, 6), 0);
        tabRow.addView(more, molp);
        acrylic.addView(tabRow, new LinearLayout.LayoutParams(-1, -2));

        empty = Ui.text(c, "", 14, pal.subtext);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(c, 24), 0, Ui.dp(c, 8));
        empty.setVisibility(GONE);
        acrylic.addView(empty);

        inline = new SearchOverlay(c, search, new Runnable() {
            @Override public void run() { close(true); }
        });
        inline.setVisibility(GONE);
        inline.setScrollListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                if (searchMode) acrylic.setShadow(y / (float) Ui.dp(getContext(), 24));
            }
        });
        stack.addView(inline, new LayoutParams(-1, -1));

        box = new FrameLayout(c);
        scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        scrollContent = new LinearLayout(c);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        frequent = new LinearLayout(c);
        frequent.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        scrollContent.addView(frequent, new LinearLayout.LayoutParams(-1, -2));
        grid = new DrawerGrid(c, gridHost);
        scrollContent.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(scrollContent, new LayoutParams(-1, -2));
        box.addView(scroll, new LayoutParams(-1, -1));
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                acrylic.setShadow(y / (float) Ui.dp(getContext(), 24));
                // the search bar stays folded into its icon; if it was opened but left empty, scrolling folds it again
                if (searchMode || search.length() > 0) return;
                if (collapse == 0f && y > oy + 2) collapseSearch();
            }
        });
        wave = new AlphabetWave(c, pal.subtext, pal.accent);
        wave.setListener(new AlphabetWave.Listener() {
            @Override public void onLetter(int section) {
                if (sectionItems.length == 0) return;
                int i = sectionItems[Math.max(0, Math.min(sectionItems.length - 1, section))];
                scroll.scrollTo(0, grid.getTop() + grid.topOfItem(i));
            }
        });
        wave.setVisibility(Prefs.drawerAlphabet(c) ? VISIBLE : GONE);
        box.addView(wave, new LayoutParams(-1, -1));
        scroll.setVerticalScrollBarEnabled(false);
        stack.addView(box, new LayoutParams(-1, -1));
        stack.addView(acrylic, new LayoutParams(-1, -2, Gravity.TOP));
        acrylic.setSource(box);
        // Lists start below the floating bar, then scroll underneath it.
        acrylic.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                // ignore size changes while the search bar folds / unfolds, so the list doesn't jump
                if (b - t != ob - ot && collapseAnim == null) applyListPadding();
            }
        });

        // Multi-select action bar (Nova-style batch actions)
        selectBar = new LinearLayout(c);
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setBackground(Ui.round(pal.surface, Ui.dp(c, 24)));
        selectBar.setElevation(Ui.dp(c, 8));
        selectBar.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        selectCount = Ui.text(c, "", 15, pal.text);
        selectBar.addView(selectCount, new LinearLayout.LayoutParams(0, -2, 1));
        selectBar.addView(barButton("Home", new OnClickListener() { @Override public void onClick(View v) { bulk(0); } }));
        selectBar.addView(barButton("Tab", new OnClickListener() { @Override public void onClick(View v) { bulk(1); } }));
        selectBar.addView(barButton("Folder", new OnClickListener() { @Override public void onClick(View v) { bulk(2); } }));
        selectBar.addView(barButton("Hide", new OnClickListener() { @Override public void onClick(View v) { bulk(3); } }));
        untabButton = barButton("Untab", new OnClickListener() { @Override public void onClick(View v) { bulk(4); } });
        untabButton.setContentDescription("Remove the selected apps from this tab");
        selectBar.addView(untabButton);
        selectBar.addView(Ui.iconButton(c, R.drawable.ic_close, pal.text, new OnClickListener() {
            @Override public void onClick(View v) { endSelection(); }
        }), new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        selectBar.setVisibility(GONE);
        LayoutParams sblp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        sblp.setMargins(Ui.dp(c, 12), 0, Ui.dp(c, 12), Ui.dp(c, 16));
        addView(selectBar, sblp);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                for (Object o : flat) if (o instanceof AppInfo) { launcher.launchApp((AppInfo) o, null); return true; }
                if (search.length() > 0) launcher.openSearch(search.getText().toString());
                return true;
            }
        });
        setVisibility(INVISIBLE);
        foldNow(); // search starts as an icon next to the tabs
    }

    private TextView barButton(String label, OnClickListener l) {
        TextView t = Ui.text(getContext(), label, 14, pal.accent);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setPadding(Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), Ui.dp(getContext(), 10));
        t.setOnClickListener(l);
        return t;
    }

    public boolean isOpen() { return open; }

    private int lastTop;

    public void setInsets(int top, int bottom) {
        insetBottom = bottom;
        lastTop = top;
        int h = Ui.dp(getContext(), 6);
        content.setPadding(0, 0, 0, 0);
        acrylic.setPadding(h, top + Ui.dp(getContext(), 8), h, Ui.dp(getContext(), 4));
        scrollContent.setPadding(h, 0, h, 0);
        grid.setPadding(0, Ui.dp(getContext(), 6), gridRightPad(), bottom + Ui.dp(getContext(), 8));
        applyListPadding();
        ((LayoutParams) selectBar.getLayoutParams()).bottomMargin = bottom + Ui.dp(getContext(), 16);
        inline.setBottomInset(bottom);
    }

    public void setImeInset(int imeBottom) {
        inline.setBottomInset(Math.max(imeBottom, insetBottom));
        grid.setPadding(0, Ui.dp(getContext(), 6), gridRightPad(), Math.max(imeBottom, insetBottom) + Ui.dp(getContext(), 8));
    }

    // ------------------------------------------------------------------ search bar folds into an icon on scroll

    private void collapseSearch() { animateCollapse(1f); }

    /** focus: also put the cursor in the search field and show the keyboard. */
    public void expandSearch(boolean focus) {
        animateCollapse(0f);
        if (focus) {
            scroll.smoothScrollTo(0, 0);
            search.requestFocus();
            postDelayed(new Runnable() {
                @Override public void run() {
                    InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 120);
        }
    }

    /**
     * Folding uses only transforms while it animates (no relayout per frame, which made it sluggish):
     * one layout when it starts, one when it ends.
     */
    private void animateCollapse(final float target) {
        if (collapseAnim != null && animTarget == target) return; // already heading there: don't restart on every scroll tick
        if (collapseAnim != null) { collapseAnim.cancel(); collapseAnim = null; }
        if (collapse == target) return;
        animTarget = target;
        if (target == 1f && search.hasFocus()) { search.clearFocus(); hideIme(); }
        if (target == 1f && collapse == 0f) {
            barFullH = searchBar.getHeight() > 0 ? searchBar.getHeight() : Ui.dp(getContext(), 44);
            miniSearch.setVisibility(VISIBLE); // the single layout at the start
            miniSearch.setAlpha(0f);
            updateTabRow();
        }
        if (target == 0f && searchBar.getVisibility() == GONE) {
            searchBar.setVisibility(VISIBLE); // bar takes its space back once; tabs are held up by translation
            setCollapse(collapse);
        }
        collapseAnim = android.animation.ValueAnimator.ofFloat(collapse, target);
        collapseAnim.setDuration(Anim.dur(getContext(), 220));
        collapseAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        collapseAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) { setCollapse((Float) a.getAnimatedValue()); }
        });
        collapseAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled) settleCollapse(target); }
        });
        collapseAnim.start();
    }

    private int foldDistance() { return barFullH + Ui.dp(getContext(), 4); }

    private void setCollapse(float f) {
        collapse = f;
        float cut = foldDistance() * f;
        boolean barInLayout = searchBar.getVisibility() != GONE;
        searchBar.setAlpha(Math.max(0f, 1f - f * 1.8f));
        searchBar.setScaleX(1f - 0.12f * f);
        searchBar.setScaleY(1f - 0.12f * f);
        searchBar.setPivotX(Ui.dp(getContext(), 28));
        searchBar.setPivotY(0);
        // While the bar is still in the layout, slide the tab row up over it and trim the acrylic to match.
        tabRow.setTranslationY(barInLayout ? -cut : 0);
        acrylic.setCut(barInLayout ? cut : 0);
        miniSearch.setAlpha(Math.min(1f, f * 1.5f));
        miniSearch.setScaleX(0.6f + 0.4f * f);
        miniSearch.setScaleY(0.6f + 0.4f * f);
    }

    /** Final state: swap transforms for the real layout in one step. */
    private void settleCollapse(float target) {
        collapse = target;
        if (target == 1f) {
            searchBar.setVisibility(GONE);
            tabRow.setTranslationY(0);
            acrylic.setCut(0);
        } else {
            tabRow.setTranslationY(0);
            acrylic.setCut(0);
            searchBar.setAlpha(1f);
            searchBar.setScaleX(1f);
            searchBar.setScaleY(1f);
            miniSearch.setVisibility(GONE);
            updateTabRow();
        }
        collapseAnim = null;
        post(new Runnable() { @Override public void run() { applyListPadding(); } });
    }

    private void updateTabRow() {
        tabRow.setVisibility(VISIBLE); // always: search icon, tabs and ⋮
    }

    /** Resting state: the search bar is just an icon next to the tabs. */
    private void foldNow() {
        if (barFullH <= 0) barFullH = Ui.dp(getContext(), 44);
        collapse = 1f;
        searchBar.setVisibility(GONE);
        searchBar.setAlpha(1f);
        searchBar.setScaleX(1f);
        searchBar.setScaleY(1f);
        tabRow.setTranslationY(0);
        acrylic.setCut(0);
        miniSearch.setVisibility(VISIBLE);
        miniSearch.setAlpha(1f);
        miniSearch.setScaleX(1f);
        miniSearch.setScaleY(1f);
        updateTabRow();
        post(new Runnable() { @Override public void run() { applyListPadding(); } });
    }

    private void applyListPadding() {
        int top = Math.max(0, acrylic.barHeight());
        if (top == 0) top = lastTop + Ui.dp(getContext(), 8) + Ui.dp(getContext(), 64);
        scroll.setPadding(0, top, 0, 0);
        inline.setTopInset(top, Ui.dp(getContext(), 6));
        wave.setPadding(0, top + Ui.dp(getContext(), 8), Ui.dp(getContext(), 12), Ui.dp(getContext(), 40));
    }

    /** Full width, unless the optional A–Z scrubber needs room on the right. */
    private int gridRightPad() { return wave.getVisibility() == VISIBLE ? Ui.dp(getContext(), 18) : 0; }

    // ------------------------------------------------------------------ keys & labels

    static String labelOf(Object o) {
        if (o instanceof AppInfo) return ((AppInfo) o).label;
        if (o instanceof DrawerGroups.Group) return ((DrawerGroups.Group) o).name;
        if (o instanceof DrawerShortcuts.Entry) return ((DrawerShortcuts.Entry) o).title;
        return "";
    }

    static String keyOf(Object o) {
        if (o instanceof AppInfo) return ((AppInfo) o).key;
        if (o instanceof DrawerGroups.Group) return "g" + ((DrawerGroups.Group) o).id;
        if (o instanceof DrawerShortcuts.Entry) return "s" + ((DrawerShortcuts.Entry) o).pkg + "/" + ((DrawerShortcuts.Entry) o).id;
        return "";
    }

    private static long parseId(String t) {
        try { return Long.parseLong(t.substring(1)); } catch (Exception e) { return -1; }
    }

    private static String initials(String l) {
        StringBuilder sb = new StringBuilder();
        for (String w : l.split("[\\s\\-_.]+")) if (!w.isEmpty()) sb.append(w.charAt(0));
        return sb.toString();
    }

    // ------------------------------------------------------------------ content

    /** Text heard by voice search goes into the search field (results show underneath). */
    public void setQuery(String q) {
        if (!open) open(false);
        search.setText(q);
        search.setSelection(search.length());
    }

    /** Contacts permission etc. changed: redraw the live results. */
    public void refreshSearch() { if (searchMode) inline.queryChanged(); }

    /** The tab whose folders are shown / made now: your own tab's id, or 0 for All (and Personal / Work). */
    long folderTab() { return tab.startsWith("t") ? parseId(tab) : 0; }

    /** Apps shown in the "new" spot at the top (they get a small "New" dot). */
    private Set<String> newApps = new HashSet<>();

    boolean isNewApp(String key) { return newApps.contains(key); }

    public void refresh() {
        Context c = getContext();
        final AppRepository repo = AppRepository.get(c);
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        List<DrawerGroups.Group> tabs = DrawerGroups.tabs(c), allFolders = DrawerGroups.folders(c);
        if (tab.startsWith("t") && DrawerGroups.find(tabs, parseId(tab)) == null) tab = "all";
        // folders belong to a tab: the All tab's, or one of your own tabs'
        List<DrawerGroups.Group> folders = DrawerGroups.foldersIn(allFolders, folderTab());
        if ((tab.equals("work") || tab.equals("personal")) && !repo.hasWorkProfile()) tab = "all";

        List<AppInfo> apps = new ArrayList<>();
        Set<String> inTab = null;
        if (tab.startsWith("t")) inTab = new HashSet<>(DrawerGroups.find(tabs, parseId(tab)).apps);
        for (AppInfo a : repo.visible()) {
            if ("personal".equals(tab) && a.work) continue;
            if ("work".equals(tab) && !a.work) continue;
            if (inTab != null && !inTab.contains(a.key)) continue;
            apps.add(a);
        }
        String sort = Prefs.drawerSort(c);
        if ("usage".equals(sort)) {
            Collections.sort(apps, new Comparator<AppInfo>() {
                @Override public int compare(AppInfo a, AppInfo b) { return repo.usage(b.key) - repo.usage(a.key); }
            });
        } else if ("installed".equals(sort)) {
            Collections.sort(apps, new Comparator<AppInfo>() {
                @Override public int compare(AppInfo a, AppInfo b) { return Long.compare(b.installTime, a.installTime); }
            });
        }

        List<Object> items = new ArrayList<>();
        int listStart = 0; // where the A–Z part begins (folders and new apps come first)
        List<DrawerShortcuts.Entry> shortcuts = "all".equals(tab) ? DrawerShortcuts.all(c) : new ArrayList<DrawerShortcuts.Entry>();
        if (!q.isEmpty()) {
            List<Object> starts = new ArrayList<>(), contains = new ArrayList<>();
            for (AppInfo a : apps) {
                String l = a.label.toLowerCase(Locale.getDefault());
                if (l.startsWith(q) || initials(l).startsWith(q)) starts.add(a);
                else if (l.contains(q) || a.pkg().contains(q)) contains.add(a);
            }
            for (DrawerShortcuts.Entry e : shortcuts) {
                String l = e.title.toLowerCase(Locale.getDefault());
                if (l.startsWith(q)) starts.add(e); else if (l.contains(q)) contains.add(e);
            }
            items.addAll(starts);
            items.addAll(contains);
        } else if (inTab == null) {
            Set<String> hiddenHere = new HashSet<>();
            for (DrawerGroups.Group f : folders) hiddenHere.addAll(f.apps);
            if (Prefs.tabsHideFromAll(c)) for (DrawerGroups.Group t : tabs) hiddenHere.addAll(t.apps);
            final java.text.Collator col = java.text.Collator.getInstance();
            col.setStrength(java.text.Collator.PRIMARY);
            Comparator<Object> byName = new Comparator<Object>() {
                @Override public int compare(Object a, Object b) { return col.compare(labelOf(a), labelOf(b)); }
            };
            // 1) folders, at the very top
            List<Object> top = new ArrayList<>();
            for (DrawerGroups.Group f : folders) if (!f.apps.isEmpty()) top.add(f);
            Collections.sort(top, byName);
            // 2) new apps right after them: installed in the last 3 days and not opened yet (newest first)
            long since = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
            List<AppInfo> fresh = new ArrayList<>();
            for (AppInfo a : apps) if (!hiddenHere.contains(a.key) && a.installTime >= since && repo.usage(a.key) == 0) fresh.add(a);
            Collections.sort(fresh, new Comparator<AppInfo>() {
                @Override public int compare(AppInfo a, AppInfo b) { return Long.compare(b.installTime, a.installTime); }
            });
            // 2) favourites next to the folders (in the order you added them)
            Set<String> favKeys = new HashSet<>();
            java.util.Map<String, AppInfo> byKey = new java.util.HashMap<>();
            for (AppInfo a : apps) byKey.put(a.key, a);
            for (String k : Prefs.drawerFavs(c)) {
                AppInfo a = byKey.get(k);
                if (a != null && !hiddenHere.contains(k)) { top.add(a); favKeys.add(k); }
            }
            for (int i = fresh.size() - 1; i >= 0; i--) if (favKeys.contains(fresh.get(i).key)) fresh.remove(i);
            // 3) then new apps
            top.addAll(fresh);
            newApps = new HashSet<>();
            for (AppInfo a : fresh) newApps.add(a.key);
            // 4) everything else
            List<Object> rest = new ArrayList<>();
            for (AppInfo a : apps) if (!hiddenHere.contains(a.key) && !newApps.contains(a.key) && !favKeys.contains(a.key)) rest.add(a);
            rest.addAll(shortcuts);
            if ("az".equals(sort)) Collections.sort(rest, byName);
            items.addAll(top);
            listStart = top.size();
            items.addAll(rest);
        } else {
            // your own tab: its folders first, then its apps (minus those in its folders)
            final java.text.Collator col = java.text.Collator.getInstance();
            col.setStrength(java.text.Collator.PRIMARY);
            Set<String> inFolders = new HashSet<>();
            List<Object> top = new ArrayList<>();
            for (DrawerGroups.Group f : folders) if (!f.apps.isEmpty()) { top.add(f); inFolders.addAll(f.apps); }
            Collections.sort(top, new Comparator<Object>() {
                @Override public int compare(Object a, Object b) { return col.compare(labelOf(a), labelOf(b)); }
            });
            // then your favourites that are in this tab, as in All
            Set<String> favKeys = new HashSet<>();
            java.util.Map<String, AppInfo> byKey = new java.util.HashMap<>();
            for (AppInfo a : apps) byKey.put(a.key, a);
            for (String k : Prefs.drawerFavs(c)) {
                AppInfo a = byKey.get(k);
                if (a != null && !inFolders.contains(k)) { top.add(a); favKeys.add(k); }
            }
            items.addAll(top);
            listStart = top.size();
            for (AppInfo a : apps) if (!inFolders.contains(a.key) && !favKeys.contains(a.key)) items.add(a);
            newApps = new HashSet<>();
        }
        flat = items;
        boolean withWidgets = q.isEmpty();
        buildSections(items, q.isEmpty() && "az".equals(sort), listStart);
        if (!withWidgets && grid.isEditing()) grid.finishEdit();
        // While searching the grid is hidden (results show instead), so don't rebuild it on every keystroke.
        if (q.isEmpty() || selecting) grid.setData(items, withWidgets ? widgets() : new ArrayList<DrawerGrid.W>(), cols(), rowHeight());
        empty.setText(q.isEmpty() ? (inTab != null ? "This tab is empty — long-press the tab to add apps" : "No apps")
                : "No apps match “" + q + "”");
        empty.setVisibility(items.isEmpty() && q.isEmpty() ? VISIBLE : GONE);
        searchEverywhere.setVisibility(GONE);
        wave.setLetters(q.isEmpty() && "az".equals(sort) ? sections : new String[0]);
        buildTabs(tabs, repo.hasWorkProfile());
        buildFrequent();
        updateSelectBar();
        clearBtn.setVisibility(search.length() > 0 ? VISIBLE : GONE);
        boolean nowSearching = !q.isEmpty() && !selecting;
        acrylic.setSource(nowSearching ? inline : box);
        acrylic.setShadow(nowSearching ? (inline.canScrollUp() ? 1f : 0f) : scroll.getScrollY() / (float) Ui.dp(getContext(), 24));
        if (nowSearching) {
            inline.setVisibility(VISIBLE);
            box.setVisibility(GONE);
            tabScroll.setVisibility(GONE);
            frequent.setVisibility(GONE);
            if (!searchMode) inline.scrollToTop();
            inline.queryChanged();
        } else {
            inline.setVisibility(GONE);
            box.setVisibility(VISIBLE);
        }
        searchMode = nowSearching;
        if (nowSearching && collapse > 0f) animateCollapse(0f);
        updateTabRow();
    }

    private void buildSections(List<Object> items, boolean on, int start) {
        List<String> names = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        if (on && start > 0) { names.add("\u2605"); idx.add(0); } // ★ = folders & new apps, at the top
        if (on) for (int i = start; i < items.size(); i++) {
            String l = labelOf(items.get(i));
            String k = l.isEmpty() ? "#" : l.substring(0, 1).toUpperCase(Locale.getDefault());
            if (!Character.isLetter(k.charAt(0))) k = "#";
            if (names.isEmpty() || !names.get(names.size() - 1).equals(k)) { names.add(k); idx.add(i); }
        }
        sections = names.toArray(new String[0]);
        sectionItems = new int[idx.size()];
        for (int i = 0; i < idx.size(); i++) sectionItems[i] = idx.get(i);
    }

    private void buildTabs(List<DrawerGroups.Group> tabs, boolean work) {
        tabBar.removeAllViews();
        boolean show = !tabs.isEmpty() || work;
        tabScroll.setVisibility(show ? VISIBLE : GONE);
        if (!show) return;
        addChip(tabName("all", "All"), "all", null);
        if (work) { addChip(tabName("personal", "Personal"), "personal", null); addChip(tabName("work", "Work"), "work", null); }
        for (DrawerGroups.Group g : tabs) addChip(g.name, "t" + g.id, g);
    }

    private void addChip(String name, final String key, final DrawerGroups.Group g) {
        Context c = getContext();
        boolean on = key.equals(tab);
        // Natural, easy-to-hit tabs: the current one is a soft accent pill with bold text, the others plain text.
        TextView t = Ui.text(c, name, 15, on ? pal.accent : pal.subtext);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(c, 64));
        t.setPadding(Ui.dp(c, 18), Ui.dp(c, 9), Ui.dp(c, 18), Ui.dp(c, 9));
        t.setBackground(Ui.pressable(c, on ? Ui.alpha(pal.accent, 0x2E) : 0x00000000, Ui.dp(c, 20)));
        t.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { tab = key; refresh(); revealList(); }
        });
        // Hold any tab (All / Personal / Work too) to rename it.
        if (!locked()) t.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { renameTab(key, g); return true; }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = lp.rightMargin = Ui.dp(c, 3);
        tabBar.addView(t, lp);
        if (on) {
            // the current tab is always shown in full: scroll the strip so it sits in view (it was cut off at the edge)
            final View cur = t;
            tabScroll.post(new Runnable() {
                @Override public void run() {
                    int target = cur.getLeft() - (tabScroll.getWidth() - cur.getWidth()) / 2;
                    int max = Math.max(0, tabBar.getWidth() - tabScroll.getWidth());
                    tabScroll.scrollTo(Math.max(0, Math.min(max, target)), 0);
                }
            });
        }
    }

    private String tabName(String key, String def) {
        String n = Prefs.sp(getContext()).getString("tabname_" + key, null);
        return n == null || n.trim().isEmpty() ? def : n;
    }

    private void renameTab(final String key, final DrawerGroups.Group g) {
        final Context c = getContext();
        if (Anim.haptics(c)) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        final EditText name = Ui.tintField(c, new EditText(c));
        name.setSingleLine(true);
        String def = "all".equals(key) ? "All" : "personal".equals(key) ? "Personal" : "Work";
        name.setText(g != null ? g.name : tabName(key, def));
        name.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
        wrap.addView(name);
        android.app.AlertDialog.Builder b = Ui.dialog(c).setTitle("Tab name").setView(wrap)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        if (g != null) renameGroup(g.id, true, n);
                        else if (n.isEmpty()) Prefs.sp(c).edit().remove("tabname_" + key).apply();
                        else Prefs.sp(c).edit().putString("tabname_" + key, n).apply();
                    }
                }).setNegativeButton("Cancel", null);
        if (g != null) b.setNeutralButton("More\u2026", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { groupMenu(g, true); }
        });
        final android.app.AlertDialog dlg = b.create();
        dlg.show();
        name.requestFocus();
        name.postDelayed(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);
    }

    private void buildFrequent() {
        frequent.removeAllViews();
        boolean show = Prefs.drawerFrequent(getContext()) && search.length() == 0 && "az".equals(Prefs.drawerSort(getContext()))
                && "all".equals(tab) && !selecting;
        List<AppInfo> top = show ? AppRepository.get(getContext()).topUsed(cols()) : new ArrayList<AppInfo>();
        frequent.setVisibility(top.size() >= 2 ? VISIBLE : GONE);
        if (top.size() < 2) return;
        for (AppInfo a : top) {
            IconView v = new IconView(getContext(), launcher, IconView.STYLE_DRAWER);
            v.bind(a.icon, a.label);
            v.setBadgePackage(a.pkg());
            v.setTag(a);
            frequent.addView(v, new LinearLayout.LayoutParams(0, v.desiredHeight(), 1));
        }
        for (int i = top.size(); i < cols(); i++) frequent.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
    }

    // ------------------------------------------------------------------ multi-select

    public boolean isSelecting() { return selecting; }

    public void startSelection(Object first) {
        selecting = true;
        selected.clear();
        if (first instanceof AppInfo) selected.add(((AppInfo) first).key);
        hideIme();
        refresh();
    }

    public void toggleSelect(Object o) {
        if (!(o instanceof AppInfo)) return;
        String k = ((AppInfo) o).key;
        if (!selected.remove(k)) selected.add(k);
        grid.rebindIcons();
        updateSelectBar();
    }

    public boolean isSelected(Object o) { return o instanceof AppInfo && selected.contains(((AppInfo) o).key); }

    public void endSelection() {
        selecting = false;
        selected.clear();
        refresh();
    }

    private void updateSelectBar() {
        selectBar.setVisibility(selecting ? VISIBLE : GONE);
        if (untabButton != null) untabButton.setVisibility(currentCustomTab() != null ? VISIBLE : GONE);
        selectCount.setText(selected.size() + " selected");
    }

    private List<AppInfo> selectedApps() {
        List<AppInfo> out = new ArrayList<>();
        for (AppInfo a : AppRepository.get(getContext()).all()) if (selected.contains(a.key)) out.add(a);
        return out;
    }

    /** The custom tab being shown, or null on All / Personal / Work. */
    public DrawerGroups.Group currentCustomTab() {
        if (!tab.startsWith("t")) return null;
        return DrawerGroups.find(DrawerGroups.tabs(getContext()), parseId(tab));
    }

    /** Takes apps out of the current tab; they go back to showing in All. */
    public void removeFromCurrentTab(List<AppInfo> apps) {
        Context c = getContext();
        List<DrawerGroups.Group> all = DrawerGroups.tabs(c);
        DrawerGroups.Group g = tab.startsWith("t") ? DrawerGroups.find(all, parseId(tab)) : null;
        if (g == null || apps.isEmpty()) return;
        for (AppInfo a : apps) g.apps.remove(a.key);
        DrawerGroups.saveTabs(c, all);
        Ui.toast(c, (apps.size() == 1 ? apps.get(0).label : apps.size() + " apps") + " moved back to All");
        refresh();
    }

    /** 0 home, 1 tab, 2 folder, 3 hide, 4 remove from this tab */
    private void bulk(int action) {
        final List<AppInfo> apps = selectedApps();
        if (apps.isEmpty()) { Ui.toast(getContext(), "Tap apps to select them"); return; }
        if (action == 4) { removeFromCurrentTab(apps); endSelection(); return; }
        final Context c = getContext();
        if (action == 0) {
            launcher.addAppsToHome(apps);
            endSelection();
        } else if (action == 3) {
            Set<String> h = Prefs.hidden(c);
            for (AppInfo a : apps) h.add(a.key);
            Prefs.setHidden(c, h);
            Ui.toast(c, apps.size() + " apps hidden. Unhide them in Grove settings.");
            endSelection();
        } else {
            final boolean isTab = action == 1;
            final List<DrawerGroups.Group> store = isTab ? DrawerGroups.tabs(c) : DrawerGroups.folders(c);
            final List<DrawerGroups.Group> all = isTab ? store : DrawerGroups.foldersIn(store, folderTab());
            String[] names = new String[all.size() + 1];
            for (int i = 0; i < all.size(); i++) names[i] = all.get(i).name;
            names[all.size()] = isTab ? "+ New tab…" : "+ New folder…";
            Ui.dialog(c).setTitle(isTab ? "Add " + apps.size() + " apps to tab" : "Move " + apps.size() + " apps to folder")
                    .setItems(names, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            List<String> keys = new ArrayList<>();
                            for (AppInfo a : apps) keys.add(a.key);
                            if (w == all.size()) { askName(isTab, keys); return; }
                            DrawerGroups.Group g = all.get(w);
                            if (!isTab) for (DrawerGroups.Group o : all) o.apps.removeAll(keys);
                            for (String k : keys) if (!g.apps.contains(k)) g.apps.add(k);
                            if (isTab) DrawerGroups.saveTabs(c, store); else DrawerGroups.saveFolders(c, store);
                            Ui.toast(c, keys.size() + " apps added to “" + g.name + "”");
                            endSelection();
                        }
                    }).show();
        }
    }

    private void askName(final boolean isTab, final List<String> keys) {
        final Context c = getContext();
        final EditText name = new EditText(c);
        Ui.tintField(c, name);
        name.setHint(isTab ? "Tab name" : "Folder name");
        name.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
        wrap.addView(name);
        Ui.dialog(c).setTitle(isTab ? "New drawer tab" : "New drawer folder").setView(wrap)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        DrawerGroups.Group g = DrawerGroups.create(c, isTab, name.getText().toString(), keys, folderTab());
                        if (isTab) tab = "t" + g.id;
                        endSelection();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ drawer widgets (between apps)

    public boolean locked() { return Prefs.sp(getContext()).getBoolean("drawer_locked", false); }

    /** Saved as [{id, col, row, sx, sy}]; 1.7 entries ({anchor, size}) are migrated on the fly. */
    /** Every drawer widget on every tab. */
    private List<DrawerGrid.W> allWidgets() {
        List<DrawerGrid.W> out = new ArrayList<>();
        AppWidgetManager awm = AppWidgetManager.getInstance(getContext());
        int cols = cols();
        boolean migrated = false;
        Set<String> tabKeys = new HashSet<>(tabKeys());
        try {
            JSONArray a = new JSONArray(Prefs.sp(getContext()).getString("drawer_widgets", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                DrawerGrid.W w = new DrawerGrid.W();
                w.id = o.optInt("id", -1);
                if (w.id < 0 || awm.getAppWidgetInfo(w.id) == null) continue;
                w.tab = o.optString("tab", "all");
                if (!tabKeys.contains(w.tab)) w.tab = "all"; // its tab was deleted: fall back to All
                if (o.has("sx")) {
                    w.col = o.optInt("col");
                    w.row = o.optInt("row");
                    w.sx = o.optInt("sx", cols);
                    w.sy = o.optInt("sy", 2);
                } else {
                    int idx = Math.max(0, indexOfKey(o.optString("anchor")));
                    w.col = 0;
                    w.row = idx / cols + i * 2;
                    w.sx = cols;
                    w.sy = 1 + Math.max(0, Math.min(2, o.optInt("size", 1)));
                    migrated = true;
                }
                out.add(w);
            }
        } catch (Exception ignored) { }
        if (migrated) saveAll(out);
        return out;
    }

    /** The widgets on the tab you're looking at. */
    private List<DrawerGrid.W> widgets() {
        List<DrawerGrid.W> out = new ArrayList<>();
        for (DrawerGrid.W w : allWidgets()) if (tab.equals(w.tab)) out.add(w);
        return out;
    }

    /** Saves this tab's widgets, keeping the other tabs' widgets as they are. */
    private void saveWidgets(List<DrawerGrid.W> current) {
        List<DrawerGrid.W> all = new ArrayList<>();
        for (DrawerGrid.W w : allWidgets()) if (!tab.equals(w.tab)) all.add(w);
        for (DrawerGrid.W w : current) { w.tab = tab; all.add(w); }
        saveAll(all);
    }

    private void saveAll(List<DrawerGrid.W> l) {
        JSONArray a = new JSONArray();
        try {
            for (DrawerGrid.W w : l) a.put(new JSONObject().put("id", w.id).put("tab", w.tab).put("col", w.col).put("row", w.row).put("sx", w.sx).put("sy", w.sy));
        } catch (Exception ignored) { }
        Prefs.sp(getContext()).edit().putString("drawer_widgets", a.toString()).apply();
    }

    /** Called by the launcher after a widget was bound for the drawer: drops it at the anchor app's row, then lets you place it. */
    public void addWidget(int id, String anchorKey) {
        if (search.length() > 0) search.setText(""); // the widget goes on the tab you're on
        List<DrawerGrid.W> l = widgets();
        DrawerGrid.W w = new DrawerGrid.W();
        w.id = id;
        w.tab = tab;
        int cols = cols();
        int idx = indexOfKey(anchorKey);
        w.row = idx < 0 ? 0 : grid.rowOfItem(idx);
        w.col = 0;
        w.sx = cols;
        w.sy = 2;
        // push widgets that would overlap further down
        for (DrawerGrid.W o : l) if (o.row + o.sy > w.row && o.row < w.row + w.sy) o.row += w.sy;
        l.add(w);
        saveWidgets(l);
        refresh();
        final DrawerGrid.W target = w;
        post(new Runnable() {
            @Override public void run() {
                for (DrawerGrid.W x : grid.widgets()) if (x.id == target.id) { grid.startEdit(x); scroll.smoothScrollTo(0, Math.max(0, grid.getTop() + x.row * grid.cellHeight() - grid.cellHeight())); }
            }
        });
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < flat.size(); i++) if (keyOf(flat.get(i)).equals(key)) return i;
        return -1;
    }

    private final DrawerGrid.Host gridHost = new DrawerGrid.Host() {
        @Override public IconView newIcon() {
            IconView v = new IconView(getContext(), launcher, IconView.STYLE_DRAWER);
            return v;
        }

        @Override public void bindIcon(IconView v, Object o) {
            Context c = getContext();
            v.setVisibility(VISIBLE);
            v.setNew(false);
            if (o instanceof AppInfo) {
                AppInfo a = (AppInfo) o;
                v.bind(a.icon, a.label);
                v.setBadgePackage(a.pkg());
                v.setNew(isNewApp(a.key));
            } else if (o instanceof DrawerShortcuts.Entry) {
                DrawerShortcuts.Entry e = (DrawerShortcuts.Entry) o;
                v.bind(e.icon, e.title);
                v.setBadgePackage(null);
            } else if (o instanceof DrawerGroups.Group) {
                DrawerGroups.Group g = (DrawerGroups.Group) o;
                List<Drawable> previews = new ArrayList<>();
                List<String> pkgs = new ArrayList<>();
                for (AppInfo a : AppRepository.get(c).all()) if (g.apps.contains(a.key)) { previews.add(a.icon); pkgs.add(a.pkg()); }
                v.bindFolder(g.name, previews);
                v.setBadgePackages(pkgs);
            }
            v.setTag(o);
            v.folderParent = null;
            v.setChecked(selecting ? (isSelected(o) ? 1 : 0) : -1);
        }

        @Override public View widgetView(final DrawerGrid.W w) {
            AppWidgetProviderInfo info = AppWidgetManager.getInstance(getContext()).getAppWidgetInfo(w.id);
            LauncherWidgetView wv = info == null ? null : launcher.feedWidgetView(w.id, info);
            if (wv == null) return null;
            wv.host = new LauncherWidgetView.Host() {
                @Override public void onWidgetLongPress(LauncherWidgetView v) {
                    if (locked()) { Ui.toast(getContext(), "Drawer layout is locked. Unlock it in Grove settings → App drawer."); return; }
                    for (DrawerGrid.W x : grid.widgets()) if (x.id == w.id) grid.startEdit(x);
                }
                @Override public void onWidgetDragStart(LauncherWidgetView v) {
                    if (!locked()) for (DrawerGrid.W x : grid.widgets()) if (x.id == w.id) grid.startEdit(x);
                }
            };
            return wv;
        }

        @Override public void onWidgetsChanged() { saveWidgets(grid.widgets()); }

        @Override public void onWidgetRemove(DrawerGrid.W w) {
            List<DrawerGrid.W> l = new ArrayList<>(grid.widgets());
            for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id == w.id) l.remove(i);
            saveWidgets(l);
            launcher.deleteFeedWidget(w.id);
            refresh();
        }

        @Override public boolean editingAllowed() { return !locked(); }
    };

    // ------------------------------------------------------------------ tabs & folders

    private void createMenu() {
        if (locked()) {
            Ui.dialog(getContext()).setTitle("App drawer (layout locked)").setItems(new String[]{"Grove settings"}, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { launcher.openSettings(); }
            }).show();
            return;
        }
        Ui.dialog(getContext()).setTitle("App drawer")
                .setItems(new String[]{"New tab", "New folder", "Select multiple apps", "Add widget to drawer", "Grove settings"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 4) { launcher.openSettings(); return; }
                        if (w <= 1) newGroup(w == 0, null);
                        else if (w == 2) startSelection(null);
                        else launcher.showDrawerWidgetPicker(flat.isEmpty() ? "" : keyOf(flat.get(0)));
                    }
                }).show();
    }

    public void newGroup(final boolean isTab, final AppInfo seed) {
        final Context c = getContext();
        final EditText name = new EditText(c);
        Ui.tintField(c, name);
        name.setHint(isTab ? "Tab name (e.g. Work, Games)" : "Folder name");
        name.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
        wrap.addView(name);
        Ui.dialog(c).setTitle(isTab ? "New drawer tab" : "New drawer folder").setView(wrap)
                .setPositiveButton("Next", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        List<String> start = new ArrayList<>();
                        if (seed != null) start.add(seed.key);
                        DrawerGroups.Group g = DrawerGroups.create(c, isTab, name.getText().toString(), start, folderTab());
                        if (isTab) tab = "t" + g.id;
                        pickApps(g, isTab);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    public void pickApps(final DrawerGroups.Group g, final boolean isTab) {
        final Context c = getContext();
        final List<AppInfo> apps = AppRepository.get(c).visible();
        String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            checked[i] = g.apps.contains(apps.get(i).key);
        }
        Ui.dialog(c).setTitle("Apps in “" + g.name + "”")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean on) { checked[which] = on; }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        List<DrawerGroups.Group> all = isTab ? DrawerGroups.tabs(c) : DrawerGroups.folders(c);
                        DrawerGroups.Group live = DrawerGroups.find(all, g.id);
                        if (live == null) return;
                        live.apps.clear();
                        for (int i = 0; i < apps.size(); i++) if (checked[i]) live.apps.add(apps.get(i).key);
                        if (!isTab) for (DrawerGroups.Group o : all) if (o != live && o.tab == live.tab) o.apps.removeAll(live.apps);
                        if (isTab) DrawerGroups.saveTabs(c, all); else DrawerGroups.saveFolders(c, all);
                        refresh();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    public void groupMenu(final DrawerGroups.Group g, final boolean isTab) {
        final Context c = getContext();
        Ui.dialog(c).setTitle(g.name)
                .setItems(isTab ? new String[]{"Choose apps", "Rename", "Move left", "Delete tab"}
                                : new String[]{"Choose apps", "Rename", "Open", "Delete folder (apps stay in drawer)", "Folder colours"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                List<DrawerGroups.Group> all = isTab ? DrawerGroups.tabs(c) : DrawerGroups.folders(c);
                                DrawerGroups.Group live = DrawerGroups.find(all, g.id);
                                if (live == null) return;
                                if (w == 0) { pickApps(live, isTab); return; }
                                if (w == 1) { rename(live, isTab); return; }
                                if (w == 4) { launcher.folderColours(); return; }
                                if (w == 2) {
                                    if (!isTab) { launcher.openDrawerFolder(live); return; }
                                    int i = all.indexOf(live);
                                    if (i > 0) { Collections.swap(all, i, i - 1); DrawerGroups.saveTabs(c, all); }
                                } else {
                                    all.remove(live);
                                    if (isTab) {
                                        DrawerGroups.saveTabs(c, all);
                                        tab = "all";
                                        // the tab's folders aren't lost: they move to the All tab
                                        List<DrawerGroups.Group> fs = DrawerGroups.folders(c);
                                        boolean moved = false;
                                        for (DrawerGroups.Group f : fs) if (f.tab == live.id) { f.tab = 0; moved = true; }
                                        if (moved) DrawerGroups.saveFolders(c, fs);
                                    } else DrawerGroups.saveFolders(c, all);
                                }
                                refresh();
                            }
                        }).show();
    }

    /** Same design as "Edit icon & name": the folder's icon on top, its name under it. */
    private void rename(final DrawerGroups.Group g, final boolean isTab) {
        View src = null;
        if (!isTab) for (int i = 0; i < grid.getChildCount(); i++) if (grid.getChildAt(i).getTag() == g) src = grid.getChildAt(i);
        if (src == null && !isTab) { // the tag may be an older copy of the same folder
            for (int i = 0; i < grid.getChildCount(); i++) {
                Object t = grid.getChildAt(i).getTag();
                if (t instanceof DrawerGroups.Group && ((DrawerGroups.Group) t).id == g.id) src = grid.getChildAt(i);
            }
        }
        IconEditor.nameDialog(getContext(), isTab ? "Edit tab" : "Edit folder", IconEditor.snapshot(src), g.name, null, new IconEditor.Named() {
            @Override public void save(String name) { if (!name.isEmpty()) renameGroup(g.id, isTab, name); }
        });
    }

    public void renameGroup(long id, boolean isTab, String newName) {
        Context c = getContext();
        if (newName == null || newName.trim().isEmpty()) return;
        List<DrawerGroups.Group> all = isTab ? DrawerGroups.tabs(c) : DrawerGroups.folders(c);
        DrawerGroups.Group live = DrawerGroups.find(all, id);
        if (live == null) return;
        live.name = newName.trim();
        if (isTab) DrawerGroups.saveTabs(c, all); else DrawerGroups.saveFolders(c, all);
        refresh();
    }

    public void addToGroup(final AppInfo a, final boolean isTab) {
        final Context c = getContext();
        final List<DrawerGroups.Group> store = isTab ? DrawerGroups.tabs(c) : DrawerGroups.folders(c);
        final List<DrawerGroups.Group> all = isTab ? store : DrawerGroups.foldersIn(store, folderTab());
        String[] names = new String[all.size() + 1];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).name;
        names[all.size()] = isTab ? "+ New tab…" : "+ New folder…";
        Ui.dialog(c).setTitle(isTab ? "Add to tab" : "Move to drawer folder").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (w == all.size()) { newGroup(isTab, a); return; }
                DrawerGroups.Group g = all.get(w);
                if (!isTab) for (DrawerGroups.Group o : all) o.apps.remove(a.key);
                if (!g.apps.contains(a.key)) g.apps.add(a.key);
                if (isTab) DrawerGroups.saveTabs(c, store); else DrawerGroups.saveFolders(c, store);
                Ui.toast(c, "Added to “" + g.name + "”");
                refresh();
            }
        }).show();
    }

    public void removeFromFolder(AppInfo a) {
        Context c = getContext();
        List<DrawerGroups.Group> all = DrawerGroups.folders(c);
        long t = folderTab();
        for (DrawerGroups.Group g : all) if (g.tab == t) g.apps.remove(a.key); // only this tab's folder
        DrawerGroups.saveFolders(c, all);
        refresh();
    }

    // ------------------------------------------------------------------ open / close

    public void open(boolean focusSearch) {
        if (open) return;
        open = true;
        Context c = getContext();
        launcher.setOverlay(true);
        setVisibility(VISIBLE);
        float h = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        animate().cancel();
        if (Anim.minimal(c)) {
            setTranslationY(0);
            setAlpha(0f);
            animate().alpha(1f).setDuration(Anim.dur(c, 160)).start();
        } else {
            setTranslationY(Anim.fluid(c) ? h * 0.35f : h);
            setAlpha(Anim.fluid(c) ? 0f : 0.6f);
            animate().translationY(0).alpha(1f).setDuration(Anim.dur(c, Anim.fluid(c) ? 520 : 260))
                    .setInterpolator(Anim.open(c)).start();
        }
        scroll.scrollTo(0, 0);
        if (Prefs.drawerRows(c) > 0) post(new Runnable() { @Override public void run() { refresh(); } });
        revealList();
        if (focusSearch || Prefs.sp(c).getBoolean("drawer_keyboard", false)) expandSearch(true);
    }

    private void revealList() {
        if (!Anim.fluid(getContext())) return;
        post(new Runnable() {
            @Override public void run() {
                int n = 0, limit = scroll.getHeight() + scroll.getScrollY();
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View v = grid.getChildAt(i);
                    if (v.getTop() > limit) continue;
                    Anim.stagger(v, n++);
                }
                for (int i = 0; i < frequent.getChildCount(); i++) Anim.stagger(frequent.getChildAt(i), i);
            }
        });
    }

    public void close(boolean animate) {
        if (!open && getVisibility() != VISIBLE) return;
        open = false;
        if (grid.isEditing()) grid.finishEdit();
        if (collapseAnim != null) { collapseAnim.cancel(); collapseAnim = null; }
        foldNow(); // next time it opens with just the search icon
        if (selecting) { selecting = false; selected.clear(); selectBar.setVisibility(GONE); }
        hideIme();
        search.setText("");
        search.clearFocus();
        Context c = getContext();
        launcher.setOverlay(false);
        if (!animate) {
            animate().cancel();
            setVisibility(INVISIBLE);
            return;
        }
        float target = Anim.minimal(c) ? 0 : Anim.fluid(c) ? getHeight() * 0.4f : getHeight();
        animate().translationY(target).alpha(Anim.fluid(c) || Anim.minimal(c) ? 0f : 0.6f)
                .setDuration(Anim.dur(c, Anim.fluid(c) ? 240 : 220)).setInterpolator(Anim.close(c))
                .withEndAction(new Runnable() {
                    @Override public void run() { if (!open) setVisibility(INVISIBLE); }
                }).start();
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ grid size

    public void applyGrid() {
        probeH = -1;
        post(new Runnable() { @Override public void run() { setInsets(lastTop, insetBottom); } });
        wave.setVisibility(Prefs.drawerAlphabet(getContext()) ? VISIBLE : GONE);
        refresh();
    }

    private int probeH = -1;

    /** Tallest the app area has been at this width: the keyboard (search) must not shrink the grid. */
    private int stableH, stableW;

    private int cols() { return Math.max(1, Prefs.drawerCols(getContext())); }

    private int rowHeight() {
        if (probeH < 0) probeH = new IconView(getContext(), launcher, IconView.STYLE_DRAWER).desiredHeight();
        int rows = Prefs.drawerRows(getContext());
        if (scroll.getWidth() != stableW) { stableW = scroll.getWidth(); stableH = 0; }
        stableH = Math.max(stableH, scroll.getHeight());
        int h = stableH - scroll.getPaddingTop() - grid.getPaddingTop() - insetBottom - Ui.dp(getContext(), 8);
        if (rows <= 0 || h <= 0) return probeH + Ui.dp(getContext(), 4);
        return Math.max(Ui.dp(getContext(), 48), h / rows);
    }

    private final ScaleGestureDetector pinch = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        float total = 1f;
        @Override public boolean onScaleBegin(ScaleGestureDetector d) { total = 1f; return true; }
        @Override public boolean onScale(ScaleGestureDetector d) {
            total *= d.getScaleFactor();
            int cols = cols();
            int next = total > 1.25f ? cols - 1 : total < 0.8f ? cols + 1 : cols;
            next = Math.max(3, Math.min(8, next));
            if (next != cols) {
                total = 1f;
                Prefs.sp(getContext()).edit().putInt("drawer_cols", next).apply();
                refresh();
                if (Anim.haptics(getContext())) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
            return true;
        }
    });

    // ------------------------------------------------------------------ swipe left / right to change tab

    private List<String> tabKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("all");
        if (AppRepository.get(getContext()).hasWorkProfile()) { keys.add("personal"); keys.add("work"); }
        for (DrawerGroups.Group g : DrawerGroups.tabs(getContext())) keys.add("t" + g.id);
        return keys;
    }

    private boolean shouldStartTabSwipe(float dx, float dy) {
        return !searchMode && !selecting && !downOnTabs && !grid.isEditing() && !wave.isActive()
                && Math.abs(dx) > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.5f && tabKeys().size() > 1;
    }

    private void switchTab(final int dir) {
        List<String> keys = tabKeys();
        int i = keys.indexOf(tab);
        final int next = i + dir;
        final float w = Math.max(1, box.getWidth());
        if (i < 0 || next < 0 || next >= keys.size()) {
            box.animate().translationX(0).alpha(1f).setDuration(Anim.dur(getContext(), 260)).setInterpolator(Anim.gentle(getContext())).start();
            return;
        }
        final String key = keys.get(next);
        if (Anim.haptics(getContext())) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        box.animate().translationX(-dir * w * 0.35f).alpha(0f).setDuration(Anim.dur(getContext(), 120))
                .setInterpolator(Anim.close(getContext())).withEndAction(new Runnable() {
                    @Override public void run() {
                        tab = key;
                        refresh();
                        scroll.scrollTo(0, 0);
                        box.setTranslationX(dir * w * 0.35f);
                        box.animate().translationX(0).alpha(1f).setDuration(Anim.dur(getContext(), 260))
                                .setInterpolator(Anim.open(getContext())).start();
                        View chip = tabBar.getChildAt(Math.min(tabBar.getChildCount() - 1, Math.max(0, tabKeys().indexOf(key))));
                        if (chip != null) tabScroll.smoothScrollTo(Math.max(0, chip.getLeft() - Ui.dp(getContext(), 24)), 0);
                    }
                }).start();
    }

    // ------------------------------------------------------------------ swipe down to close, pinch to resize

    private boolean shouldStartClose(float dx, float dy) {
        return dy > slop && dy > Math.abs(dx) * 1.2f && !(searchMode ? inline.canScrollUp() : scroll.canScrollVertically(-1)) && !wave.isActive() && !grid.isEditing();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                draggingClose = false;
                swipingTab = false;
                pinching = false;
                if (vt != null) vt.clear();
                {
                    int[] loc = new int[2];
                    tabScroll.getLocationOnScreen(loc);
                    downOnTabs = tabScroll.getVisibility() == VISIBLE && ev.getRawY() >= loc[1] && ev.getRawY() <= loc[1] + tabScroll.getHeight();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() == 2 && !grid.isEditing()) { pinching = true; return true; }
                break;
            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() == 1 && shouldStartClose(ev.getRawX() - downX, ev.getRawY() - downY)) {
                    draggingClose = true;
                    hideIme();
                    return true;
                }
                if (ev.getPointerCount() == 1 && shouldStartTabSwipe(ev.getRawX() - downX, ev.getRawY() - downY)) {
                    swipingTab = true;
                    hideIme();
                    return true;
                }
                break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(ev);
        if (pinching || ev.getPointerCount() > 1) {
            pinching = true;
            pinch.onTouchEvent(ev);
            if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) pinching = false;
            return true;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!draggingClose && !swipingTab && shouldStartTabSwipe(ev.getRawX() - downX, ev.getRawY() - downY)) swipingTab = true;
                if (swipingTab) {
                    float dx = ev.getRawX() - downX;
                    box.setTranslationX(dx * 0.55f);
                    acrylic.invalidate();
                    box.setAlpha(1f - Math.min(0.5f, Math.abs(dx) / Math.max(1f, getWidth())));
                    return true;
                }
                if (!draggingClose && shouldStartClose(ev.getRawX() - downX, ev.getRawY() - downY)) {
                    draggingClose = true;
                    hideIme();
                }
                if (draggingClose) {
                    float dy = Math.max(0, ev.getRawY() - downY);
                    setTranslationY(dy);
                    if (Anim.fluid(getContext())) setAlpha(1f - Math.min(0.6f, dy / Math.max(1f, getHeight())));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swipingTab) {
                    swipingTab = false;
                    vt.computeCurrentVelocity(1000);
                    float dx = ev.getRawX() - downX, vx = vt.getXVelocity();
                    boolean go = ev.getActionMasked() == MotionEvent.ACTION_UP && (Math.abs(dx) > getWidth() * 0.18f || Math.abs(vx) > 900);
                    if (go) switchTab(dx < 0 ? 1 : -1);
                    else box.animate().translationX(0).alpha(1f).setDuration(Anim.dur(getContext(), 260)).setInterpolator(Anim.gentle(getContext())).start();
                    return true;
                }
                if (draggingClose) {
                    vt.computeCurrentVelocity(1000);
                    if (getTranslationY() > getHeight() * 0.15f || vt.getYVelocity() > 1000) close(true);
                    else animate().translationY(0).alpha(1f).setDuration(Anim.dur(getContext(), 300)).setInterpolator(Anim.gentle(getContext())).start();
                }
                draggingClose = false;
                return true;
        }
        return true;
    }
}
