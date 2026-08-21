# Privacy policy for AI Caller ID

Package: `com.pcdeni.aicallerid`
Applies to version 1.0.0 and later unless superseded by a newer version of this file.

## Summary

AI Caller ID has no server of its own and collects nothing. The only data that leaves your phone is the phone number of an incoming call, and it goes directly to the AI provider you chose (Groq or Google Gemini), authenticated with your own API key. Everything else stays on the device.

## Data processed

- **Phone number of an incoming call.** Android passes the number to the app through the call screening role (`CallScreeningService`). In **Auto** mode the number is looked up as soon as the call rings. In **On demand** mode the number is only displayed; nothing is sent until you tap "Scan caller". Withheld or private numbers are never looked up because there is no number to send.
- **Numbers you type into Manual lookup.** Treated exactly like an incoming number.
- **Your API key.** Entered by you, used only to authenticate the request to the provider you chose.

The app does not read your contacts, your call log or your phone state, and does not request those permissions. It does not process the call audio in any way and cannot block, reject or delay a call.

## Where the data goes

A lookup is one HTTPS request from the phone directly to the provider you selected:

- **Groq** (`api.groq.com`), model `groq/compound-mini` with `groq/compound` as fallback.
- **Google Gemini** (`generativelanguage.googleapis.com`), model `gemini-3.7-flash` with Google Search grounding, retried without grounding if the key is not allowed to use it.

The request contains the phone number and a prompt asking for caller information. Your API key is sent in the request header. There is no intermediate server, proxy or relay operated by the author, so the author never receives your numbers, your key or the results.

If a number is already in the local history, it is answered from the cache and no network request is made.

## What is stored on the device

- **API keys**, in `EncryptedSharedPreferences` backed by the Android Keystore. They are excluded from Android cloud backup and device-to-device transfer, and are never written to logs.
- **Lookup history**, a JSON file in the app's private storage containing the number, the AI result (name, category, risk, location, summary, sources) and a timestamp, limited to the 200 most recent entries. This file is private to the app. It may be included in Android's standard app backup if you have device backup enabled.
- **Settings**, such as the chosen provider and scan mode.

## What is not collected

- No server operated by the author; no account, sign-up or login.
- No analytics, crash reporting, advertising or tracking SDKs.
- No contacts, call log, phone state, location or device identifiers.
- No Google Play Services dependency.
- The app never writes phone numbers or API keys to the system log.

## Third parties

When you run a lookup, the provider you selected receives the phone number and your API key and processes them under its own terms of service and privacy policy. The author has no control over, and no visibility into, what the provider does with that data. Please review the terms of the provider you choose (Groq or Google) before entering a key. Web search performed by the provider may in turn contact search engines and the websites it cites.

## Your control

- **On demand mode** ensures nothing is sent for a call unless you tap "Scan caller".
- **History screen, Clear** deletes the entire local history file.
- **Clear** in the API key dialog deletes the stored API key. You can also revoke the key in the provider's console at any time.
- **Uninstalling** the app deletes all of its local data.
- Revoking the call screening role or the overlay permission in Android settings stops the app from seeing incoming numbers or showing the card, respectively.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | The HTTPS request to the provider you chose |
| `SYSTEM_ALERT_WINDOW` | The floating card over the incoming-call screen |
| `POST_NOTIFICATIONS` | Fallback notification when the overlay permission is missing |
| Call screening role | How Android hands the incoming number to the app |

## Changes

Changes to this policy are made in this file in the source repository and take effect with the release that includes them.

## Contact

Questions or concerns: open an issue at <https://github.com/pcdeni/ai-caller-id/issues>.
