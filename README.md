# Data Controller

A consent-based Android companion app for pairing two phones and sending remote requests through Firebase.

## Important Android limitation

A regular Android app cannot silently enable or disable airplane mode on all brands/Android versions. This project therefore sends a remote request and opens the target phone's airplane-mode/network settings when an owner-confirmed action is needed. Firebase delivery is not the same as successfully changing device state. When airplane mode disables all network connectivity, the target cannot receive new cloud commands until it reconnects.

## Build an APK

1. Open the **Actions** tab in this repository.
2. Select **Build Android APK** and click **Run workflow**, or push a commit.
3. Download the **data-controller-debug-apk** artifact when the workflow completes.
4. Extract the ZIP and install the APK on both Android phones.

The app uses `app/google-services.json` to connect to Firebase Authentication and Cloud Firestore. Pairing and command requests require the repository Firestore rules to be published and Anonymous sign-in to be enabled.

## Connect Firebase (free Spark plan to start)

1. Open https://console.firebase.google.com/ and create a project.
2. Add an Android app with package name `com.tharul.datacontroller`.
3. Download `google-services.json` and upload it to `app/google-services.json` in this repository. **Do not commit service-account private keys or Admin SDK credentials.**
4. In Firebase Authentication, open **Sign-in method** and enable **Anonymous** sign-in (this app uses Firebase anonymous authentication).
5. Create a Cloud Firestore database.
6. In Firestore Database → Rules, publish the repository's `firestore.rules`. Do not use public read/write rules. The app uses four-digit pairing codes that expire after five minutes.

For a production design, use an authenticated pairing handshake with a short-lived code, attempt limits, expiration, a cryptographically random pairing token, and device-owner authorization. A four-digit code alone has only 10,000 possibilities: enforce server-side rate limits and never let clients enumerate codes.

## Background operation

On the target phone, the app can run a foreground service with a persistent notification, request exemption from battery optimizations, and attempt to restart after reboot. The owner must approve system prompts. Android and manufacturers can still stop background work; force-stop, revoked notifications/permissions, power saving, or network loss can interrupt delivery. A foreground service requires a visible persistent notification and the correct Android service permissions. Firebase Cloud Messaging is generally preferable to maintaining a permanent socket.

## First-run pairing flow

On first launch the user chooses one role:
- **This is the controller** — sends a request to the paired phone.
- **This is the target phone** — displays a short-lived four-digit pairing code.

Enter the code on the controller phone. Pairing is represented in this starter UI; cloud-backed secure pairing must be configured as above.

## Cloud server: what you actually need

You do not need to rent a separate server just to deliver commands. Firebase Authentication + Firestore + Firebase Cloud Messaging can provide the cloud pieces. For a secure deployment, implement pairing-code creation/redemption in a trusted backend (for example, a Firebase Cloud Function), use Firestore Security Rules to enforce ownership, and use FCM for notifications. Never put Firebase Admin credentials in the Android app or repository.

## Scope

- Android-native Kotlin + Jetpack Compose.
- GitHub Actions builds a debug APK.
- Firebase configuration is intentionally supplied by the repository owner.
- No root access is required for pairing or remote request delivery.
- Automatic airplane-mode switching is not implemented because Android does not offer a universal API for ordinary applications.
