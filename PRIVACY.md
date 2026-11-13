# Privacy

Canopy Launcher is built so that it does not track you.

* **Internet only for web tabs you add.** Since 1.15 the app has the `INTERNET` permission. It is
  used only by web tabs you open in the feed yourself (**+** in the feed's tab strip, e.g. X/Twitter). Those pages
  load like any browser tab, and their cookies and sign-ins are stored on your phone. The launcher itself
  sends nothing: no analytics, no crash reports, no advertising IDs, no remote config.
  If you never open a web tab, Canopy never connects to anything.
* **No accounts, no sign-in, no cloud sync, no trackers, no ad SDKs, no Firebase, no Google Play Services.**
  The app uses only the Android framework, with no third-party libraries.
* **Everything stays on the device:** home layout, notes, to-dos, custom icons and names, and launch counts (used only for "Most used") are in the app's private storage. Android backup is disabled
  (`allowBackup="false"`), so nothing goes to a cloud backup either. Use *Settings > Back up layout* to
  export a file yourself.

Permissions requested and why:

| Permission | Why |
|---|---|
| `INTERNET` | Only for the web tabs you add to the feed, and for downloading ad-block filter lists from GitHub. |
| `MANAGE_EXTERNAL_STORAGE` (Android 13+) / `READ_EXTERNAL_STORAGE` (Android 12 and older) | Optional, only if you allow it: read your current wallpaper so Canopy can draw it in 3D. Nothing else is read. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` | Keep a web tab's video or music playing in the background, with a notification to stop it. Only while something plays. |
| `EXPAND_STATUS_BAR` | The "swipe down → notifications" gesture. |
| `REQUEST_DELETE_PACKAGES` | Opening the system "Uninstall app?" dialog from an icon's menu. Android always asks you to confirm. |
| `READ_CALENDAR` *(asked only if you tap "Show my agenda")* | Agenda card on the Glance tab. Reads the calendar on the phone; nothing is copied or sent. |
| `READ_CONTACTS` *(asked only if you tap "Search your contacts too")* | Contact results in search. Read on the phone only, never stored. |
| `WRITE_SETTINGS` *(you grant it in system settings)* | Brightness slider and auto-rotate toggle in the expanded dock. |
| `ACCESS_NOTIFICATION_POLICY` *(you grant it)* | Do Not Disturb toggle in the expanded dock. |
| `ACCESS_WIFI_STATE` | Shows whether Wi‑Fi is on in the expanded dock. Cannot connect anywhere. |
| `PACKAGE_USAGE_STATS` *(you grant "Usage access")* | Screen time card. Totals are computed on the phone and never stored. |

Optional services you switch on yourself:

* **Notification access** (badges): only package names and counts are kept, in memory. Notification text is never read or stored.
* **Accessibility – "Canopy gestures"**: only performs lock screen / recents / quick settings / power menu when you use a gesture.
  It has `canRetrieveWindowContent="false"`, so it cannot see what's on your screen.
