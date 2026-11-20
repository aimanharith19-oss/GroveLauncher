# Grove Launcher (formerly Canopy Launcher)

An open-source Android home screen. It re-creates **Nova Launcher**'s features and blends in a **Microsoft Launcher-style feed**.
It has no trackers and no ads. It only goes online for web tabs you add to the feed yourself.

* Licence: GNU GPL v3.0 or later (see `LICENSE`, `NOTICE`). All original code. Nova and Microsoft Launcher are closed source, so no code from either is included.
* Android 8.0+ (API 26), targets Android 14 (API 34)
* About 150 KB. The only dependency is the Android framework.

## Home screen (Nova-style)
- Pages with an adjustable grid (3–8 columns, 3–9 rows), icon size, label size, and labels on or off.
- **Page transitions**: slide, cube, card stack, zoom, rotate and flip. **Infinite scroll** is optional, and the wallpaper scrolls with the pages.
- Drag and drop. Drop one app on another to make a **folder**, and drag apps out again. Folders and apps can be renamed.
- **Widgets**: add and move them, and **resize by dragging handles** (long-press → Resize). Optionally let widgets **overlap** each other.
- **Pinned shortcuts** from other apps, and long-press **app shortcuts**.
- **Icon packs** (ADW/Nova `appfilter.xml`). **Icon shapes** (circle, squircle, rounded, teardrop, square), plus reshaping of legacy square icons.
- **Notification badges**, shown as dots or numbers.
- **Swipe actions on icons**: swipe up on a home icon to open a second app.
- **Gestures** for swipe up/down, two-finger swipe up/down, double-tap and pinch. Actions include the drawer, search, notifications, quick settings, lock screen, recents, power menu, the feed and settings.
- **Lock desktop**, hide status bar, and a search bar at the top, above the dock, or hidden (also from long-press on empty home → Hide search bar).
- Back up and restore your layout and settings.

### Animations
- **Fluid** (default) is springy and physical: icons squish when pressed and spring back, the drawer rises with a spring and its icons ripple in one after another, pages settle with a slight overshoot, and apps launch by scaling up from their icon. It's an original take on the feel of Niagara Launcher, which is closed source.
- **Classic** gives Nova-like slides. **Minimal** reduces motion.
- Plus an animation speed setting (50–200%) and a haptics on/off switch.

### Dock (Microsoft Launcher-style)
- Swipe the dock up to expand it. You get **quick toggles** (Wi‑Fi, Internet, Bluetooth, torch, auto-rotate, Do Not Disturb, lock, settings), a **brightness slider** and a **second row of apps**.

### What's new in 1.53.6
- **Drawer tabs: the selected pill is always shown whole.** The scroll to the selected tab used to run before the strip had been laid out, so it never moved. It now waits for the layout, and only scrolls when the pill isn't already fully in view.

### What's new in 1.53.5
- **Drawer tabs scroll properly.** Tabs look as before (the slimmer pills are gone). The strip is now exactly as wide as its tabs, so you can always scroll to the last one and see it in full, and it jumps to the selected tab.

### What's new in 1.53.4
- **Drawer tabs: no more cut-off.** Tabs named with just an emoji or a letter or two get a slimmer pill, so more fit; the strip also has a little room at the end and scrolls to the selected tab.

### What's new in 1.53.3
- **The current drawer tab is never cut off.** When there are many tabs, the strip now scrolls so the selected one sits fully in view.

### What's new in 1.53.2
- **Fixes the bare strip at the top.** With the status bar hidden (feed and app drawer), a band of undimmed wallpaper showed above the content. The launcher now draws under the top of the screen at all times.

### What's new in 1.53.1
- **Status bar icons follow the screen.** Dark icons on a light screen and white icons on a dark one: on the home screen it follows your wallpaper, in the feed and app drawer it follows the theme.

### What's new in 1.53.0
- **The 3D wallpaper is gone.** The tilting wallpaper, its depth slider, the wallpaper subject layer and "put widget behind wallpaper subject" are removed, along with the permission prompt to read your wallpaper (the "All files access" permission is dropped too). Your wallpaper is drawn by the system as normal, which also makes dragging apps and widgets smoother.

### What's new in 1.52.8
- **Dragging an app or widget keeps up with your finger.** The 3D wallpaper's constant redrawing is paused while you drag, which was making the dragged item trail behind.

### What's new in 1.52.7
- **Icons and widgets lift faster.** The hold needed before an app or widget can be moved is now about 0.25 s (it was your phone's long-press delay, often 0.5 s). Scrolling past them still doesn't trigger it.

### What's new in 1.52.6
- **The feed keeps 3 days again.** Anything older than 3 days moves to `Documents/Grove/Notification history.xlsx` (the week limit in 1.52.0 is gone).

### What's new in 1.52.5
- **The notification spreadsheet now exists from the start.** It's created in `Documents/Grove/Notification history.xlsx` when the launcher starts (before, it appeared only once something was older than a week). Settings, under the notification history switch, has **Notification spreadsheet**: tap to save it now with everything so far, including the last 7 days.

### What's new in 1.52.4
- **App icons in the notification spreadsheet.** `Documents/Grove/Notification history.xlsx` now has an Icon column with each app's icon beside its notifications (the first 30,000 rows get icons).

### What's new in 1.52.3
- **Fixes a blank browser.** The feed's web tabs could stay frozen after the launcher restarted itself or a tab was opened while they were asleep. They are now always woken when the feed opens or a new tab is created.

### What's new in 1.52.2
- **The launcher clears its cache every week.** A little after start-up, once every 7 days, cached files (thumbnails, web page data, temporary files) are deleted. Your layout, settings, notifications and bookmarks are not touched.

### What's new in 1.52.1
- **Notification card is a scrollable list.** The feed's notification card now lists your notifications, newest first, with each app's icon beside it. Five show at a time and the list scrolls inside the card. Tap one to open it, long-press to copy, delete or see everything from that app, or tap the icon for that app's full list.

### What's new in 1.52.0
- **Notification history keeps one week; older ones go to a spreadsheet.** Anything older than 7 days is moved out of the feed into `Documents/Grove/Notification history.xlsx` (date & time, app, title, text, package), updated automatically and openable in any spreadsheet app. The "Keep history for" setting is gone.

### What's new in 1.51.2
- **Web tabs are frozen unless the feed is on screen.** Before, the feed's websites kept running scripts and ad filters behind the home screen and the app drawer, which could make the drawer hang and taps land late (opening apps by accident). Now they only run while you're looking at the feed; playing media is spared.

### What's new in 1.51.1
- **Crop handles fixed.** When a widget was cropped small, the four handles piled on top of each other and you couldn't drag it back out. They now sit just outside the frame, the nearest one is grabbed, and you can crop much smaller.

### What's new in 1.51.0
- **Crop widgets by dragging.** Long-press a widget → **Crop** (new crop icon). The handles now hide or bring back that side of the widget as you drag, like resizing, but the content stays where it is. A dashed line shows the whole widget. The sliders are gone.

### What's new in 1.50.1
- **Fixes the launcher freezing ("not responding") at start-up.** Nothing on the main thread waits for Grove Guard any more: its status card, page filters, history and controls are all fetched in the background.

### What's new in 1.50.0
- **Crop widgets.** Long-press a widget → **Crop** and slide how much to hide on the left, top, right and bottom (live preview, Reset to undo). Use just one part of a big widget, such as only the clock or only the search bar. The widget is drawn at full size and only the part left is shown, so it keeps working and keeps your crop.

### What's new in 1.49.1
- **Ad blocking is now only in Grove Guard.** Grove's own filter lists are gone (about 1.4 MB smaller), so Settings → Feed browser → "Block ads & trackers" asks Grove Guard. Without Grove Guard nothing is blocked. Lists, your rules and updates are in Grove Guard → web.
- **Website history in Grove Guard** (web → History) for pages opened in the feed's browser. Needs Grove Guard 1.6.1.
- **Notification history keeps 3 days at most.**

### What's new in 1.49
- **Groveguard web filtering**: with Groveguard installed, the feed browser asks Groveguard about every page and request — ad/tracker
  blocking (uBlock Origin / EasyList lists), 18+ lists, dangerous sites and your own allow/block rules are controlled and logged in
  Groveguard → web. Settings → Feed browser → "Use Groveguard for ad blocking & web protection" (on by default; Grove's own blocker
  is used without Groveguard).
- Groveguard feed card controls now use Groveguard's verified connection (works with Groveguard 1.2+, which refuses unsigned commands).

### What's new in 1.48.0
- **Windows Phone style removed completely** (the home style option, floating button, sliding home screens, app list, pivot tabs and its settings look). The launcher is back to one classic design.
- Faster: the colour palette is now built once instead of on every draw, and folder icons no longer read settings on every frame. Together with the earlier drawer fixes, scrolling and opening should be smoother.

### What's new in 1.47.1
- Windows Phone style, much further: the **app drawer is now a Windows Phone app list** (accent-tinted square tile with the icon, big light name beside it), and its **tabs are pivot headers** (big light lowercase, the current one bright, the others dim).
- The **feed** gets the accent-over-black tint all the way down, tinted tiles for cards with light lowercase titles, and pivot-style tabs. The home blur is off in this style.

### What's new in 1.47.0
- **Windows Phone style now redesigns the whole launcher**, following Grove Music and Grovebook: black with the accent glowing from the top, flat square shapes, light lowercase type, the 20 Windows Phone accent colours, and dark flat dialogs.
- **Settings** in Windows Phone style: a big "settings" title, a plain list of categories, plain text rows, square toggles, tilt-on-press and the "feather" slide-in. Switching home style reopens settings straight away in the new look.
- The app drawer, feed, folders and pop-ups follow the same black, flat, square look.

### What's new in 1.46.0
- **History** in the feed browser (tab menu → History): tap to open, Clear all.
- **Linked with Grove Browser**: bookmarks and history are shared both ways with Grove Browser (the Gecko / Firefox-engine browser). Nothing is deleted on either side. It syncs quietly while you use the launcher, or on demand in Settings. Needs the matching Grove Browser update.
- Tab menu → **Open in Grove Browser**; it is also offered in Settings → Open websites in. Grove can't carry the Gecko engine inside itself (it would be about 100 MB, with no trackers or extra dependencies being a rule here), so it hands pages to Grove Browser.

### What's new in 1.45.8
- Fixed the crash that stopped the launcher opening: the notification history could grow huge from one oversized notification and run the phone out of memory. Notification text is now capped, and an oversized history file is discarded.

### What's new in 1.45.7
- If Grove ever crashes, a crash screen now appears with the error, a Copy button, and options to reset the settings (or settings and home layout) so a crash can never lock you out of the launcher.

### What's new in 1.45.6
- App drawer scrolling is now fully smooth: the search bar uses a frosted tint instead of live blur, which re-drew the app list on every frame. Live blur is still available under Settings > App drawer if you want it.

### What's new in 1.45.5
- Smoother app drawer scrolling: the frosted search bar now only redraws the strip behind it instead of the whole app list on every frame.

### What's new in 1.45.4
- Fixed the app drawer animation freezing when you swipe into it. The home screen is no longer re-blurred on every frame (it is skipped on Windows Phone style), and the status bar change now waits until the animation finishes.

### What's new in 1.45.3
- Hardened what runs when you come back from an app (widgets, web tabs, wallpaper, feed, floating button, the keep-running service) so a failure in any of them can no longer take the launcher down.

### What's new in 1.45.2
- Fixed "Some settings couldn't be shown" (introduced by the new row icons).

### What's new in 1.45.1
- Every setting inside a category now has an icon, coloured like its category tile.

### What's new in 1.45.0
- Settings main page redesigned as a colourful two-column grid of tiles, with the search box on top.

### What's new in 1.44.4
- Much quicker return home: the home screen now fades in within a tenth of a second instead of Android's slow window animation, and wallpaper housekeeping waits until after the first frame.

### What's new in 1.44.3
- Coming back to the home screen from an app is quicker: widget updates, web tabs and the feed refresh now start just after the first frame instead of before it, and the status bar is no longer re-applied on every return.
- Keep Grove running now starts reliably after a phone restart (it is started when you open Grove).

### What's new in 1.44.2
- Swiping from the feed (browser) to the home screen now animates: the feed swings away on its right edge while shrinking and fading, as the home screen comes forward.

### What's new in 1.44.1
- Windows Phone style: the home screen is one seamless column that scrolls freely (no separate screens), opening the app list fades instead of sliding, the floating button is bottom right at home and bottom left in the app list, and this style keeps its own icons and widgets, separate from the classic home screen.

### What's new in 1.44.0
- **Dark mode**: follow the system, follow the time of day (you pick when it starts and ends), or always light / always dark. Settings pages and dialogs turn dark with it. The search box in Settings now shows readable text.
- **Status bar**: hide or show it separately on the home screen, the feed and the app drawer.
- **Free icons**: icons and folders can be placed anywhere and resized like widgets (Settings → Home screen, then long-press an icon → Move & resize freely).
- **Edit home screens**: zoom in with two fingers (or pick it for any gesture) to see every screen as a card. Add, delete, choose the main one, and tap a widget at the bottom to put it on the selected screen.
- **Folders**: choose the icon preview (grid, fan, stack, line) and how many columns the open folder has, on top of the existing colours and shape.
- **App lock**: fingerprint or face (PIN / pattern as backup) for Grove's settings, or for the whole launcher. While it is on, settings can't be opened from anywhere without unlocking.
- **Windows Phone style** (Settings → Home screen): screens slide down, swipe left for apps, swipe right to leave them, the feed stays on the right. A floating button opens the apps (swipe up on it opens an app of your choice) and moves to the bottom right inside the app list, where it goes home.
- **Feed browser**: typing words in the address box searches Google; icon-only tabs whose icon is kept on disk and never disappears while a page reloads; open websites in the feed, any installed browser or ask; send every download to a downloader app you choose; websites can be added to the home screen and put in folders; each tab remembers its last page; optional "Keep Grove running" so Android doesn't restart it (and tabs never reload).

### What's new in 1.43.1
- The feed browser refreshes itself after the app is installed/updated or the phone restarts: each web tab loads fresh from the internet (not an old cached copy) the first time it opens, and the tab you were on starts loading in the background right away.

### What's new in 1.43
- New name: **Grove Launcher** (was Canopy / 🌳🌲), matching Grove Guard and Grove Vault. Settings, gestures and badges are "Grove …" too. The package name stays `org.canopy.launcher` so this installs as an update and keeps your layout.
- New icon in the Grove family style: the Grove round tree inside a home, light mint on the Grove dark green (like Grove Guard's shield), with a themed-icon version.

### What's new in 1.42.2
- Favourites now also work in your own drawer tabs: favourite apps that are in a tab are pinned at its top, after the tab's folders.
- Folder icons no longer show a "+N" count of the apps inside; numbers on icons are only for notifications.

### What's new in 1.42.1
- Fixed: the icon-pack picker grid wasn't centred. Columns now have a fixed size and the spare width is spread evenly on both sides and between them.

### What's new in 1.42
- The antivirus card is now **Grove Guard** (your malware scanner + firewall app, `org.grove.guard`), replacing the Android Antivirus Project card. It reads Grove Guard's status live from `content://org.grove.guard.status/status` and shows: a one-line summary, a **Realtime scanning** switch (no notification needed — the status is on the feed), a **Firewall** switch (apps blocked / allow-list mode / profile, or "waiting for Shizuku"), web protection, signature count and last update, scan progress, and open threats (tap to review). Buttons: Scan, Update, Profile… (switch firewall profile), Open. Commands go to Grove Guard's `AutomationReceiver` (`org.grove.guard.REALTIME`, `.SCAN`, `.UPDATE`, `shizuwall.CONTROL`, `shizuwall.PROFILE`).

### What's new in 1.41
- New app name and icon to match the project: the app is now called **🌳🌲**, with a new original icon — a round-crowned tree beside an evergreen on a green hill, on a pale sky background.

### What's new in 1.40.1
- Renaming folders, shortcuts and drawer folders/tabs now uses the same design as *Edit icon & name*: the icon on top, the name under it, Save / Reset / Cancel.

### What's new in 1.40
- Feed: removed the Hypatia and ShizuWall cards. New **Android Antivirus** card for github.com/Sanjeet990/Android-Antivirus-Project (signature-based scanner): *Scan a file* hands a file to its scanner, *Open* opens it, and its alerts show on the card. If it isn't installed, the card links to its releases. No code from that project is included (it has no licence).

### What's new in 1.39
- Folder colours: choose the folder icon colour (default: accent) and the open folder's background colour and opacity — Settings > Look & feel (under Folder shape), or long-press any folder → *Folder colours*. Labels and the title switch to black or white to stay readable.

### What's new in 1.38.1
- Fixed: after searching in the icon-pack picker, tapping an icon picked the one below it. The keyboard closing made the dialog jump between your finger going down and up; the dialog now stays still, the keyboard closes as soon as you touch the icons, and each icon handles its own tap.

### What's new in 1.38
- Favourite apps in the drawer: long-press an app → *Add to favourites* and it's pinned at the top, right after the folders (then new apps, then the rest).
- Drawer folders in any tab: make a folder while you're in one of your own tabs and it lives in that tab (at the top, like in All). Deleting a tab moves its folders to All.
- Folders open like a chat bubble: the folder pops out of the icon you tapped, with a little tail pointing at it (above or below, whichever fits).
- No limit on folders: every app shows (up to 5 columns), scrolling only when a folder is taller than the screen.
- Folder icons pop more: glossy accent-tinted disc with a soft shadow and light rim, bigger previews, and a "+N" badge when there are more than 4 apps.
- Fixed: picking an icon from an icon pack could set a different icon (the list could shift under your finger while searching). Edited icons and names now show at once, and the same icon is used in the drawer, home screen, dock and folders.

### What's new in 1.37
- App drawer: folders are always at the top, then new apps (installed in the last 3 days and not opened yet, newest first, marked with a small dot), then everything else in your chosen order. The A–Z scroller gets a ★ for that top part.

### What's new in 1.36.2
- Websites show their name, not their address: tabs, the bar above a page and bookmarks read "Reddit", "YouTube Music", "Google News"… instead of reddit.com / https://… (names you gave a tab or bookmark are kept).

### What's new in 1.36.1
- "Your music" top artists shows up to 10 (scroll sideways).
- Fixed: in the notification history, *Open app*, *Clear app* and *Close* didn't respond. With a long list the dialog's own button bar could slide under the navigation bar; the buttons are now at the top of the dialog and the list scrolls in a limited height.

### What's new in 1.36
- Feed background options (Settings > Feed > Background): **Dark blur** (as before), **White blur** (the wallpaper blurred under a frosted white layer, with dark text) or **Plain colour** (pick a swatch or type #RRGGBB; text turns black or white to match, and the wallpaper isn't blurred, saving a little power).

### What's new in 1.36
- Feed background: a **Background** button at the bottom of the feed (next to *Edit feed*) picks Dark blur, White blur or a Plain colour (20 swatches or any #hex) — also in Settings > Feed. On phones where Android can't blur behind apps (setting off or unsupported), Canopy now blurs your wallpaper itself, so the blur really shows.
- "Your music" shows the top 3 songs and top 3 artists.
- Notification history is compact: one app icon per app with how many it sent; tap an icon to read all of that app's notifications in full (tap one to open it, long-press to copy or delete).

### What's new in 1.35.1
- Private DNS icons now show each provider's website icon (downloaded once from the provider's own site and cached; custom providers use their hostname's site, e.g. nextdns.io). Coloured initials show until it's downloaded or if the site can't be reached.
- Fixed: tapping things in the feed could throw you back to the home screen. Some feed cards save small settings, and the launcher rebuilt itself for them and started on the main page. Those settings no longer rebuild anything, and after any rebuild Canopy now stays on the page you were on.

### What's new in 1.35
- **Private DNS** card is back, compact: one scrollable row of round provider icons with short names (Off, Auto, AdGuard, AG Family, AG Plain, Cloudflare, CF Secure, CF Family, Google, Quad9, Quad9 Plain, OpenDNS, OD Family). Tap to switch; the one in use is ringed. **+ Add** saves your own provider (name + hostname, e.g. NextDNS); long-press an icon for details, or to remove one you added. Icons are initials in colour, no company logos. Needs Shizuku (Canopy asks once and grants itself WRITE_SECURE_SETTINGS) or a one-time `adb shell pm grant org.canopy.launcher android.permission.WRITE_SECURE_SETTINGS`. Provider list inspired by ADNS (github.com/eyalm2000/adns, MIT); no ADNS code is used.

### What's new in 1.33.2
- Fixed: the Bookmarks card never showed. An old check treated every feed id starting with "b" as a web page, so "bookmarks" was skipped (and moved into the browser tabs as a broken tab). The card is back at the top of the feed, and the stray tab is removed.

### What's new in 1.33.1
- Canopy is now licensed under the **GNU GPL v3.0 or later** (was Apache-2.0). `LICENSE` holds the full GPL text; every source file carries `SPDX-License-Identifier: GPL-3.0-or-later`. Third-party parts keep their own compatible licences (Material icons: Apache-2.0; Shizuku interop class: MIT; uBlock/EasyList filter lists: GPL-3.0 / CC BY-SA).

### What's new in 1.33
- **Notification history** feed card: every notification you get is kept — even after you dismiss it or tap *Clear all* in the shade. Grouped by day, filter by app, tap to open (the notification's own link while it's still available, otherwise the app), long-press to copy or delete. Chats keep each new message. Saved only in a file on the phone; kept 7 / 30 (default) / 90 days or forever (latest 2000) — Settings > Notification badges, where it can also be turned off.

### What's new in 1.32.1
- Removed Smartspacer support (the "+ Smartspacer" button, and its widget is taken out of the feed).

### What's new in 1.32
- **Hypatia** feed card (github.com/MaintainTeam/Hypatia, malware scanner): realtime-scanner status, its alerts (tap to open), *Open scanner* and *Scan a file* (pick a file, it's handed to Hypatia's scanner).
- **ShizuWall** feed card (github.com/AhmetCanArslan/ShizuWall, firewall): turn the firewall on/off and switch profiles through ShizuWall's documented automation broadcasts, see its alerts, open it.
- Both cards appear by themselves when the app is installed (show/hide in *Edit feed*). They talk to the apps only through Android intents/broadcasts and notifications — no code from these (GPL/AGPL) projects is included.

### What's new in 1.30.1
- "Your music" tracks music only: video apps (YouTube, Netflix, TikTok, Twitch…), browsers, video players and anything an app marks as a movie are ignored and no longer shown as "now playing". Videos counted by 1.29 are removed from the history.

### What's new in 1.30
- Pull to refresh in the feed browser: at the top of a page, drag down until the round arrow grows, let go and the page reloads. Works on sites that scroll an inner box (X, Reddit…) because the page reports whether the content under your finger is at its top; maps, games and drawing areas are left alone.

### What's new in 1.29
- Click-through widgets: long-press a home-screen widget → *Click-through* and taps/swipes pass to whatever is behind it (icons, or a KLWP live wallpaper). Long-press the same spot to get its menu back.
- New feed card **Your music**: what's playing now (with controls), your most played songs ("On repeat", last 30 days) and top artists — learned from the media sessions of any music app (needs notification access). A song counts after 30 s of playing. The history stays in a file on the phone; tap a song or artist to play it again in that app, or *Clear history*.

### What's new in 1.28
- Double-tap the home screen to lock (now the default). With [Shizuku](https://shizuku.rikka.app) running, Canopy locks through Shizuku (`input keyevent SLEEP` as the shell user) — no accessibility service needed; Canopy asks for Shizuku permission the first time. Without Shizuku it falls back to "Canopy gestures" (accessibility). Status and permission: Settings > Gestures > Shizuku.

### What's new in 1.27
- Removed the Niagara home screen style (and the "Home screen style" option). The home screen is the classic grid again; notification numbers and notifications in app menus stay.

### What's new in 1.26
- Niagara style is centred and has no app drawer: the home screen shows your favourites; scroll down and every app follows in one A–Z list over a dark tint. The A–Z letters jump through the list; Back / Home returns to the favourites; pull down at the top for notifications.
- Notification badges show the number by default, also next to app names in the Niagara style.
- Long-press an app to see its notifications at the top of its menu; tap one to open it (Settings > Notification badges; kept in memory only).
- Removed the iOS home screen style.

### What's new in 1.25 — Niagara style polish (Canopy's own implementation, no code or assets from other launchers)
- The letter list now pops up beside your thumb while you slide along the A–Z wave; the favourites fade back behind it, and it slides away 4 s after you let go.
- The latest notification shows as a small line under each favourite (toggle in Settings; needs notification access; kept in memory only).
- A glance line under the date: your next alarm (within 24 h) and the battery when charging or low.
- Motion: favourites drift in one after another and the clock settles each time you return home; letter rows slide in from the right with a springy letter heading. Respects "Minimal" animation style and Android's animation setting.

### What's new in 1.24 — home screen styles
- **Home screen style** (Settings > Home screen, or long-press the home screen): *Classic* grid, *Niagara* or *iOS*. The feed stays on the left in every style.
- **Niagara style**: big clock and date, your favourite apps as large names, and the A–Z wave on the right edge — slide along it to list every app with that letter. Add favourites from the drawer (long-press → Add to favourites, or drag an app onto home); long-press a favourite to move, edit or remove it. The app drawer becomes a single-column list with the A–Z wave.
- **iOS style**: 4 × 6 grid with labels, rounded-square icons, a frosted 4-app dock and a small "Search" pill above it.
- Settings now fill the whole screen, from under the status bar down under the navigation bar.
- Removed the app-open, icon-press and return-to-home animation options; Android's own animations play.

### What's new in 1.23 — battery
- Web tabs stay loaded (never closed or reloaded) but their scripts are frozen 2 minutes after you leave the home screen; anything playing, and the pop-up player, keep going. Settings > Feed browser > *Websites in the background* chooses: run 2 min then freeze (default) / keep running / freeze right away.
- 3D wallpaper: sensor at ~30 Hz instead of 50 Hz, nothing redrawn while the phone is still, sensor slows to ~5 Hz after a few still seconds, off while the drawer, feed or a folder covers home, and off in Battery Saver (toggle).
- Only home pages with widgets behind the wallpaper subject redraw as the wallpaper moves.
- The drawer's blurred header no longer re-blurs when the drawer is hidden.
- Feed cards rebuild at most once a minute when you return home.

### What's new in 1.22
- **App tabs in the feed are removed.** Android can't show another app inside the feed, so + only adds websites again.
- **Stronger uBlock Origin filtering** in web tabs:
  - Canopy now follows uBlock Origin's `!#include` sub-lists, like uBlock Origin does, so it gets all of uBlock filters 2020–2026, mobile and general.
  - It adds uBlock's and EasyList's **annoyance** lists.
  - It supports `:style()` rules and blocking by path.
- **Reddit stays in the browser:** the "use the app" prompts, bottom bars, app selectors and app-only content walls are hidden, and their scripts are blocked. Reddit's app prompt is told it was already shown (uBlock's own fix), and "Open in app" redirect links (`*.app.link`, OneLink) are stopped.

### What's new in 1.21
- **Feed browser**:
  - **✕ on each tab**.
  - **Tab groups like Chrome:** ⋮ → Add to group. A group shows as one pill of site icons side by side. Long-press the group to name it, ungroup it or close it.
  - **Pop-up pages:** a page that opens a new window (after you tap) opens as a tab grouped with the tab it came from.
  - **Bookmarks card:** tap a bookmark to open it in the feed browser, reusing that site's tab if one is open. Add pages from ⋮ → Bookmark this page, or with + Add bookmark.
  - **Feed widgets:** a new **Widget's own size** option, the same size as on your home screen.
  - The "+ Web tab" button under the cards is gone; use + in the tab strip.
- **Animations**:
  - Return-to-home plays after Android's own closing animation, so you can see it.
  - A quick tap shows the whole icon-press effect.
  - App-open plays after the press.
  - A page transition change applies at once.
  - Settings → Animations has a **Preview** and warns you if your phone has animations turned off. It also explains that page transitions need two or more home pages.
- **App drawer**: the search bar rests as a search icon next to the tabs, with ⋮ at the right edge. Tap the icon to search.
- **After installing**, the installer's "Open" button takes you to the home screen, not settings.
- **Settings** has the wallpaper showing through, blurred, like the feed.
- **KLWP and other live wallpapers**: page offsets count home pages only, one step per page, and taps on empty home-screen space are sent to the wallpaper so KLWP touch actions work. Canopy's 3D wallpaper turns itself off while a live wallpaper is set.

### What's new in 1.20
- **Pop-up player** (picture-in-picture): float a web tab's video in a small window over your other apps.
  - Start it from the **pop-up button** in the tab's toolbar, or **Pop-up player** in the media notification.
  - Only the video shows: Canopy asks the page to put just its video in full screen. If a site refuses, everything except the video is hidden.
  - The window has play/pause and back/forward buttons, can be moved and resized, and expands to full screen.
  - Close it and the page goes back to its tab.

### What's new in 1.19
- **Pop-up blocking** in web tabs:
  - Pages can't open windows on their own.
  - A link you tap that wants a new window opens as a new tab instead.
  - Ad redirects to blocked ad or tracker sites are stopped.
  - Pages can't launch other apps or the Play Store unless you tap something.
  - Sites can't use your camera, microphone or location.
  - You can turn this off in Settings → Feed browser.
- **Proper player notification** for videos and music in web tabs, like a music app or Netflix:
  - artwork, title and channel or artist
  - play/pause, previous/next (or skip 10 s back/forward), and a seek bar
  - controls on the lock screen and from headphone buttons
  - when paused, it stays with a Play button and can be swiped away

  It uses the information the site gives browsers, so YouTube, YouTube Music, Spotify Web and others show their real titles and artwork.

### What's new in 1.18
- **3D wallpaper**, on automatically for whatever wallpaper you set. Canopy draws your wallpaper slightly behind the screen and tilts it as you move your phone, with a soft spring. The icons float a little the other way, so the home screen has real depth.
  - **Turn it on:** Android 13+ only lets an app read your wallpaper through "All files access". Canopy asks once, and reads nothing but the wallpaper.
  - **When it updates:** change wallpaper and the 3D copy updates by itself.
  - **Live wallpapers** stay as they are.
  - **Settings → Look & feel:** 3D on or off, 3D depth, or a different picture just for 3D.
- **Widgets behind the wallpaper's subject** (like iOS depth wallpapers):
  1. Add a **wallpaper subject layer**: a transparent PNG of the person or object in your wallpaper. Most gallery apps make one ("Copy subject" or "Extract object").
  2. Long-press a widget → **Put behind wallpaper subject**.

  The subject now overlaps that widget, and it moves with the wallpaper as you tilt.
- The motion sensor runs only while the home screen is on screen.

### What's new in 1.17.2
- **Fixed: crash during background play.** The background-play service could be stopped before Android let it start, and Android treats that as a crash. It now starts as soon as something plays, stops itself safely, and shows the usual media notification with Stop.
- **Text follows the background.** Home labels, dock labels, the home search bar, the drag bar and the feed header turn near-black on light wallpapers and white on dark ones, and switch automatically when you change wallpaper. You can set this in Settings → Home label colour: Automatic, Always white or Always black. Dialogs and settings already follow their own light or dark background.

### What's new in 1.17
- **Feed browser**:
  - Videos **play automatically**.
  - Videos and music **keep playing in the background**, with a notification you can stop them from.
  - A video's **full-screen** button plays it full screen, sideways.
- **Ad & tracker blocking** in web tabs uses **uBlock Origin's filter lists** from GitHub: uBlock filters, EasyList, EasyPrivacy and Peter Lowe's list. A copy comes with the app, and Settings → Feed browser → **Update filter lists** downloads the latest. It blocks ad and tracker servers and hides known ad boxes. It's not the uBlock Origin extension itself, because Android's WebView can't run browser extensions, so ads served from a site's own servers (e.g. YouTube video ads) can still appear.
- **Feed header**: your picture and name (tap either to change them) plus a motivating line that changes each time you swipe to the feed. It replaces the clock and date.
- **Rename any tab by holding it**: All, Personal, Work, your own drawer tabs and the Feed tab.
- **Drawer widgets on any tab**: a widget you add goes on the tab you're looking at.
- **Shorter, smaller app menus**: compact pills of the same size everywhere. Widgets are no longer listed in the menu; add them from the widget page.
- **Fixes**:
  - The return-to-home animation and the other animation settings now work. The app-open animation also shows on the icon itself.
  - Text in "Edit app", rename and other dialogs is black on light dialogs, and white on dark ones.

### What's new in 1.16
- **The feed is now a browser with tabs.** The tab strip at the top shows **Feed** (your cards) and one tab per website.
  - **+** opens a new tab: X/Twitter, Reddit, YouTube, Instagram, Threads, Facebook, Google News or any address.
  - Each tab fills the page and has back, reload, the site's address (tap it to go somewhere else), start page, open in browser and ⋮.
  - Long-press a tab or use ⋮ to reload, change the start page, rename, move it left or right, or close it.
  - Tap the open tab to jump the page up. Back goes back inside the page.
  - Tabs keep their place and sign-ins. Web pages you added as cards in 1.15 become tabs automatically.
  - Swipes inside a website always go to the website, never the home screen. To leave, swipe on the tab strip or the toolbar, switch to the Feed tab, or press Home.

### What's new in 1.15
- **Web pages in the feed** (**+ Web page** at the bottom of the feed): put X/Twitter, Reddit, YouTube, Instagram, Threads, Facebook, Google News or any website in a feed card.
  - Sign in once and it stays signed in.
  - Scroll the page inside the card. Use back, start page and reload, open it **full screen**, or open it in your browser.
  - Change the address, rename the card or pick its height (⋮).
  - This is the only part of Canopy that uses the internet.
- App menus: every pill is the same width. A menu too long for the screen now scrolls.
- Widget previews (widget page and app menus): touch, hold a moment, then drag. A quick swipe scrolls the list again.

### What's new in 1.14
- **Remove apps from a tab**: in one of your own tabs, long-press an app → **Remove from "tab name"**. To remove several at once, use Select multiple apps → **Untab**. The apps go back to the All tab.
- **Android 12-style app menus**: every option is its own rounded pill with small gaps between them, grouped into shortcuts, widgets and app options.
  - App options (info, edit, add to home, tabs, folders, hide, uninstall…) are small icons side by side, up to 6 per pill, with more pills when needed. Long-press an icon to see its name.
  - The menu opens above the icon whenever there's room.

### What's new in 1.13
- **The search bar folds into an icon as you scroll** the drawer. It shrinks into a round search icon at the start of the tab row and comes back when you scroll to the top. Tap the icon to bring the bar back and start typing.

### What's new in 1.12
- **Fluent (acrylic) drawer header**: the search bar and tabs float over the app list. Apps scroll underneath and show through blurred and tinted, and a soft shadow appears once you scroll.
  - Blur needs Android 12+. Older versions get a frosted tint instead.
  - Search results scroll under the bar the same way.
  - Frequent apps now scroll with the list.
- **Fixed: swipe up right after coming back from the feed.** It was taken as a page swipe while the page was still settling.

### What's new in 1.11
- **Free widget placement on the home screen**: drag a widget anywhere, right up to the screen edges, with no grid snapping.
  - Resize mode: drag inside the frame to move the widget, or drag the handles to any size.
  - Turn this off in Settings → Home screen to snap widgets to the grid again.
- **Feed widgets show the app's name** (e.g. "Spotify") instead of the widget's name.
- **Bigger, centred drawer tabs**: the current tab is a soft accent pill.
- **Fixed**:
  - The drawer grid shrank after searching with the keyboard open.
  - Search results now show icons at the drawer's column size.
- **Fixed: dragging widgets out of an app's long-press menu.** The menu is now part of the launcher window, so the drag can start from it.
- **Review fixes**:
  - "Hide/Show search bar" now applies straight away.
  - Back closes an open menu first.
  - Opening an app from the search page closes the search page.
  - While you resize a widget, it's told its new size once you stop, not on every frame.
  - The drawer grid isn't rebuilt on every keystroke.

### What's new in 1.10
- **Swipe left or right in the app drawer to change tab**: All, Personal and Work, then your own tabs. The tab chip follows along.
- **Full-size drawer**:
  - The scroll bar is gone, and the A–Z scroller is off by default. You can turn it back on in Settings.
  - The app grid uses the full width and height of the screen.
- **Google-style search bar**: a rounded pill with the search icon, a clear button, a **microphone** and a **⋮ menu**, all inside it. The ⋮ menu replaces the old + and settings buttons and holds New tab, New folder, Select multiple apps, Add widget and Canopy settings.
- **Voice search and commands**: tap the mic and speak.
  - Say "open YouTube" to launch the app.
  - Say "call Ali" to find the contact.
  - Anything else is searched straight away.

  The phone's own speech recognizer does the listening. Canopy still has no internet permission and never handles the audio.

### What's new in 1.9
- **Results as you type, right under the drawer search bar.** You get apps, actions inside your other apps, saved shortcuts, contacts, phone settings, calendar events, a calculator, and one-tap searches in your browser, YouTube, YouTube Music and the Play Store.
  - Actions inside your apps are the apps' own shortcuts, such as "New message", "Navigate home" or "New incognito tab". Tap one to open it, or tap **+** to add it to the home screen or drawer.
- The home screen search bar shows the same live results. Turn off "actions inside your apps" in Settings → Search if you don't want them.

### What's new in 1.8
- **Drawer widgets work like home screen widgets.** Long-press a drawer widget, then:
  - drag it anywhere in the drawer
  - drag its handles to resize it
  - tap ✕ to remove it

  Apps reflow around the widget automatically as you move or resize it, and the drawer scrolls when you drag to the edge.
- **Lock drawer layout** (Settings → App drawer). Long-pressing an app then shows only its shortcuts and its widgets. Tabs, folders and widgets can't be changed.
- **Widgets in every app's long-press menu.** Tap a preview to add it, or drag it straight out onto a home screen page.
- **Drag from the widget page**: pull a widget up or down out of the page and drop it on the home screen. Tapping still adds it to the first free spot.

### What's new in 1.7
- **Universal search, Nova-style**: from the search bar, or the "Search apps" gesture. It covers:
  - apps, contacts (tap to open, or the call button), phone settings, calendar events and your shortcuts
  - a calculator
  - one-tap searches in your browser (Google, DuckDuckGo, Bing, Brave or Startpage), YouTube, YouTube Music, Play Store and Maps
  - recent searches

  Canopy still has no internet permission. Web searches open in those apps.
- **Widgets between apps in the drawer**: long-press an app → **Add widget here**, or use **+ → Add widget to drawer**. Long-press a drawer widget to move, resize (handles) or remove it.
- **Multi-select in the drawer**: long-press → **Select multiple apps**, then send them all to the **home screen**, a **tab** or a **folder**, or hide them.
- **Home screen animation options**: app open animation, icon press effect and return-to-home animation.
- The Android home gesture now always returns from the feed to your main home page.
- The feed no longer reloads while you slide to it. The scroll bar track line and the feed's privacy sentence are gone.

### What's new in 1.6
- Home screen grid up to **12 × 12**.
- **App shortcuts and browser shortcuts can live in the app drawer** as well as on the home screen. When a browser adds a page shortcut, Canopy asks Home screen, App drawer or Both. You can set a default in Settings → Home screen.
- **Stronger text contrast**: near-black text on light themes, white on dark.
- Settings, dialogs and switches **follow the phone's dark theme** and use the modern device-default (Material You) style on Android 10+. Settings rows are grouped into rounded cards, like Android 12+ settings.

### What's new in 1.5
- Settings are now organised into **categories, like Nova**, with a search box.
- The **widget page** is now full screen, like Nova: search, widgets grouped by app, previews and sizes.
- **App shortcuts on the home screen**: long-press an app and tap **+** next to a shortcut, or long-press empty space → **App shortcut**.
- **Depth effect**: the wallpaper blurs and dims behind the feed, drawer, folders and widget page (blur on supported Android 12+ devices, dim everywhere). The dock fades away when the feed opens.
- **Material You colours** on Android 12+.
- Apps you add to a drawer tab only show in that tab.
- A visible scroll position bar on the right of the drawer.
- Up and down swipes on the home screen no longer get mistaken for page swipes.

### Main home page
- Long-press an empty spot on any page → **Set as main home page**. Home returns there, and it's marked with a small square in the page dots. You can also set it in Settings → Home screen.

### App drawer
- **Your own tabs**: tap **+** → New tab, or long-press an app → Add to drawer tab. Long-press a tab to choose apps, rename, reorder or delete it.
- **Drawer folders**: tap **+** → New folder, or long-press an app → Move to drawer folder. Folders sit alphabetically among your apps.
- **Wave A–Z scroller** (optional, in Settings): drag along the right edge and the letters bulge out under your finger.
- Search by name, initials or package name. Press Enter to search your books.
- A–Z fast scroll and a row of your frequent apps. **Swipe down to close.**
- **Resizable grid**: columns, rows on screen and icon size in settings, or **pinch inside the drawer** to change columns.
- Sort A–Z, by most used or by newest installed.
- **Personal / Work** tabs when you have a work profile. Hidden apps.

### Feed: swipe right from home (Microsoft Launcher-style)
- Clock, date and greeting, followed by cards: **Agenda**, **To-do**, **Screen time**, **Most used apps**, **Notes**, **Device**.
- **Add widgets** to the feed with "+ Add widget". Resize them with ⋮ → Resize (Small, Medium, Large, Extra large).
- **Rearrange** with **Edit feed**: move cards up or down, show or hide built-in cards, remove widgets. You can also use ⋮ on any card.

### Edit icon & name (Nova-style)
- Long-press any app (home, dock, folder or drawer) → **Edit icon & name**.
- Rename the app, then tap the icon to choose one from **any installed icon pack** (searchable), **an image on your phone** (cropped to a square) or the default.
- **Reset** restores both. Changes apply everywhere.

## Privacy
No accounts, no analytics, no ads. Internet is used only by web tabs you add to the feed. See `PRIVACY.md` for the few optional permissions and what each one does.

## Build

**Android Studio:** open this folder and click Run. The project has Gradle files and no dependencies.

**Command line, no Gradle:** `./build.sh` uses `aapt2`, `javac`, `dx` and an APK signer. Point it at your tools:
```
ANDROID_JAR=…/platforms/android-34/android.jar AAPT2=…/aapt2 DX_JAR=…/dx.jar SIGNER=…/uber-apk-signer.jar ./build.sh
```
`build.sh` creates a local signing key (`release.keystore`) the first time it runs. That file is git-ignored on purpose.

## Install
1. Install the APK. Allow "install unknown apps" for your file manager if Android asks.
2. Press Home and choose **Canopy Launcher**, or open *Canopy Settings → Set Canopy as default home app*.
3. Optional:
   - *Canopy Settings → Notification badges → Notification access* turns on badges.
   - *Canopy Settings → Gestures → Canopy gestures* turns on lock screen and recents gestures.
