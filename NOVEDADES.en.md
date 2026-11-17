# What's new

<!-- English version of NOVEDADES.md. Same sections, with "## Next version" instead of
     "## Próxima versión". The app shows these notes when it's in English. -->

## Next version

- No changes yet since 0.3.3.

## 0.3.3

- **New welcome screens**: they help you set up what keeps the filter always on (notifications,
  battery and autostart) and let you choose how to download and whether to send error reports.
- **Download only over Wi-Fi** (on by default): lists and version checks download on their own as
  soon as you connect to Wi-Fi. If you tap "Update now" on mobile data, it asks you first.
- **Optional anonymous error reports**: if you turn them on, when something fails a report is sent
  with no websites, history, apps or identifiers. You can see the exact report before it's sent in
  Settings → Error reports.
- If the lists can't be downloaded when you turn on the filter, it tries again as soon as there's a
  connection.
- When updating from the app, it explains what to do if Google Play Protect blocks the installation.
- Development versions (Nightly) are always numbered ahead of the latest stable one, so an update
  never looks like a downgrade.

## 0.3.2

- **Xiaomi, Huawei, OPPO and Vivo**: a notice explains how to grant the "Autostart" permission,
  without which the system won't let the filter turn itself back on after restarting or updating.
- If the filter was on and you find it off, it turns on again when you open the app.
- Fixed: if a list can't be downloaded (for example, with no connection), the setting goes back to
  how it was and you're told, instead of staying checked without working.
- Fixed: changing the blocking level without a connection could leave the phone with no list; now the
  previous list is kept until the new one downloads correctly.
- The notes for each update are shown in full, without being cut mid-sentence.

## 0.3.1

- **Fixed: on Android 9 and 10, updates from the app failed** with the message "The download isn't
  Garbillo". If you have 0.3.0 on one of those Android versions, install this version once by hand
  from the website; from then on it updates itself.
- Fixed: in Settings, a section opened from the search could appear cut off.
- **More private history**: when you clear it, nothing is left in the phone's file, and old entries
  are deleted on time even if the filter has been off for days.

## 0.3.0

- **New name: Garbillo**, with its own icon. It updates over the version you have.
- **Threat protection**, on by default: blocks phishing, malware and scam websites, alerts you when it
  does and has its own section in Settings.
- **Report a website**: from the history you can send a domain to be reviewed (ads getting through,
  something that stops working or a dangerous website).
- **Real protection status**: the main screen warns you if no lists are loaded, if your network's DNS
  isn't responding or if Android's private DNS bypasses the filter.
- **Turns on by itself when the phone starts** if you had it on, without having to open the app.
- **No ads at startup**: the filter loads the lists before it starts filtering.
- If your network's DNS stops responding, another one is used automatically.
- More protection against fake DNS answers on public Wi-Fi networks.
- Large DNS answers arrive complete.
- App updates are only installed if they come from GitHub and are signed with the same key as the
  installed app.
- "View on the web" never opens sites marked as dangerous.
- Settings: a guide to keep the VPN always on, and links to the website and the privacy policy.
- Fixed: after adding an exception, a domain could stay blocked for a few minutes.

## 0.2.0

- **Blocks more by default.** The Balanced level uses the Hagezi Pro list and Strict uses Pro++.
- **DNS anti-evasion on by default**, so browsers and apps can't bypass the filter.
- **Your manufacturer's list**: on Samsung, Xiaomi, Oppo/Realme/OnePlus, Huawei/Honor, Vivo and Amazon
  phones, ads and tracking from the system itself are blocked too.
- **History: "View on the web"**: loads the website where a domain appeared and outlines in red what
  it puts there.
- **Settings organized into categories, with search.**
- **Visual blocking removed**: accessibility and screen capture permissions are no longer requested.
- Clear notices about what a blocker of this kind can't remove.

## 0.1.0

- First version: local DNS filter without root, Hagezi lists and extra layers, choice of apps,
  automatic list updates, history and app updates from GitHub.
