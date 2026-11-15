<p align="center">
  <img src="assets/banner.svg" alt="WhatsApp Automation: replies for you while you're busy" width="100%">
</p>

# WhatsApp Automation

An Android app that answers WhatsApp messages while you're busy. You set a status such as "At school", and anyone who messages you gets a short reply saying so. Everything stays on your phone: there is no account, no server and no analytics.

[![Latest release](https://img.shields.io/github/v/release/heyitsaadin/whatsapp-automation?label=latest)](https://github.com/heyitsaadin/whatsapp-automation/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/heyitsaadin/whatsapp-automation/ci.yml?label=build)](https://github.com/heyitsaadin/whatsapp-automation/actions/workflows/ci.yml)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
[![MIT license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

[Download the latest APK](https://github.com/heyitsaadin/whatsapp-automation/releases/latest)

## How it works

When a WhatsApp message arrives, Android shows a notification with a **Reply** button. Once you give the app notification access, it can read that notification and press the button for you. WhatsApp itself never opens.

For example, say your status is "At school" and your name is "Alex":

> **A friend:** hey, are you free?  
> **Auto-reply:** Alex is at school, pls message later.

The name is optional. If you leave it empty, the reply says "I'm at school, pls message later."

The app stays quiet when no status is set, when the chat is a group, when the person has already been told, or when you've paused it. Each person gets one reply, and another only if your status changes or 30 minutes have passed.

Other things it does:

- Keeps working with the screen off, using a light background service.
- Lets a status include a place. "Travelling" plus "Goa" becomes "Travelling to Goa". You can type the place, search for it, or tap **Use my location**.
- Has **Start** and **Stop** buttons on the main card to pause and resume.

## Install

1. Download the APK from the [latest release](https://github.com/heyitsaadin/whatsapp-automation/releases/latest) and open it. If Android asks, allow "install unknown apps".
2. Open **WhatsApp Automation** and grant access using the buttons on each row:

   | Row | Needed | Why |
   |---|---|---|
   | Notification access | Yes | This is how it sees your messages. |
   | Run in background | Yes | So replies still go out with the screen off. |
   | Show notification | No | Shows an "auto-reply is on" notice while it runs. |

3. Tap **Set status**, pick one, optionally add a place and your name, then **Save**.
4. The top card should now say **Auto-reply is on**.

On Android 13 or newer, Notification access may be greyed out. Go to Settings → Apps → WhatsApp Automation → ⋮ (three dots) → **Allow restricted settings**, then try again.

## Setting your status

| To do this | Do this |
|---|---|
| Pick a quick status | Tap one of the chips. |
| Write your own | Type it. Your own text is used instead of a chip. |
| Add a place | Type to search, tap a recent place, or tap **Use my location**. |
| Use your name | Enter it in the name field. The reply then reads "Alex is at school..." instead of "I'm at school...". |
| Turn replies off | Tap **Clear status**, or **Stop** on the main card. |

## Customising

You can change the app from a fork without a computer, only a GitHub account.

1. Fork this repository.
2. In your fork, open **Actions** and enable workflows.
3. Run **Actions → CI → Run workflow**. After about two minutes the test APK is attached at the bottom of the run.

Common changes (edit the file on GitHub, commit, and wait for the build):

| What | Where |
|---|---|
| App icon | `ic_launcher_image` in `android-keyboard/app/src/main/res/drawable-nodpi/`. Use a square PNG or JPG of 432 px with the logo in the middle 60%. Keep only one file with that name. |
| Wording of the reply | The `Info` class in `Status.java` |
| Quick status chips | `android-keyboard/app/src/main/res/layout/dialog_tag.xml` |
| App name | `app_name` in `android-keyboard/app/src/main/res/values/strings.xml` |
| App id (to install next to the original) | `applicationId` in `android-keyboard/app/build.gradle` |
| A toast for every skipped message | Set `DEBUG = true` in `ReplyListener.java` |

If you replace the icon, export it properly as a PNG or JPG. Renaming a file's extension doesn't convert it, and the release build fails on a mismatched file.

## Releasing

This section is for whoever publishes the APK (you, if you forked).

To publish a release, use either of these:

- **Actions → Release → Run workflow**, then enter a version such as `1.0.0`. The workflow builds the APK, creates the tag `v1.0.0`, and attaches `WhatsApp-Automation-v1.0.0.apk` with its checksum.
- Push a tag: `git tag v1.0.0 && git push origin v1.0.0`.

A version with a dash, such as `1.1.0-beta`, is published as a pre-release.

### Signing key (one-time setup)

Android only installs an update if it is signed with the same key as the version already on the phone, so keep one key for good. Without the secrets below the workflow still runs, but it signs with a public debug key that anyone could copy. Don't give that APK to other people.

Create a key on any computer (or Termux) that has a JDK:

```bash
keytool -genkeypair -v -keystore release.jks -alias whatsapp-automation \
  -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 release.jks    # copy the output
```

Then go to **Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The output of the `base64` command |
| `KEYSTORE_PASSWORD` | The keystore password you chose |
| `KEY_ALIAS` | `whatsapp-automation` |
| `KEY_PASSWORD` | The key password you chose |

Back up `release.jks` somewhere safe, outside the repository. If you lose it, you can never ship an update that installs over the old version.

## Privacy

- Your status, name and recent places are stored only on your phone.
- There are no accounts, analytics or ads.
- The only network use is place search. Text typed in the Place box (or your rough coordinates, if you tap **Use my location**) is sent to [Photon](https://photon.komoot.io), a free service built on OpenStreetMap. It is optional.
- Location permission is requested only when you tap **Use my location**, and only at coarse (town) level.
- Message text is read on your phone to answer it. It is never stored or sent anywhere.

## Limitations

- It works with WhatsApp (`com.whatsapp`), not WhatsApp Business.
- It can only answer messages that show a notification with a Reply button. Muted chats, and a chat that is open on screen, don't.
- Only one-to-one chats get a reply. Group chats are skipped.
- WhatsApp can change how its notifications work, which could break this.
- It sends real messages as you, and nobody checks them first. Try it with a friend before relying on it.
- Automating WhatsApp may go against its terms of service. Use it on your own account, at your own risk.

## Troubleshooting

**Nobody gets a reply.** The top card must say "Auto-reply is on", a status must be set, and Notification access must be enabled.

**Notification access is greyed out.** On Android 13 or newer, go to Settings → Apps → WhatsApp Automation → ⋮ → **Allow restricted settings**.

**It works with the screen on but not with it off.** Set **Run in background** to unrestricted. Some phone makers (Xiaomi, Oppo, Vivo, Samsung and others) add their own battery savers that stop background apps. Turn off battery optimisation for this app there too, and lock it in the recent apps list.

**It replied once and never again to the same person.** This is intended. Each person gets one reply per 30 minutes, unless your status changes.

**"Use my location" finds nothing.** Turn on location in quick settings and try again, or type the place instead.

**Installing says the app conflicts with an existing one.** The installed copy was signed with a different key. Uninstall it once, then install again.

## Repository layout

```
android-keyboard/   the Android app (Java, Material 3)
.github/workflows/  CI (test build on every push) and Release (publishes the APK)
assets/             the banner image above
```

The app folder is still called `android-keyboard` because the first version was a keyboard.

## License

[MIT](LICENSE). WhatsApp is a trademark of WhatsApp LLC. This is an independent open-source project and is not affiliated with or endorsed by WhatsApp or Meta.
