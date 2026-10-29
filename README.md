# Geofence

An Android group geofence app using the existing Kotlin Multiplatform project (`androidApp` + `shared`). Android UI, Firebase integration, local notification handling, and location APIs live in `androidApp`; portable Haversine distance calculation remains in `shared`.

The app uses a short Create/Join entry screen, a group dashboard for live members and inside/outside state, a local notification history screen, and an interactive map pin/radius picker. The picker uses OpenStreetMap tiles through osmdroid and displays attribution, so it does not need a Maps API key. Google's Maps SDK requires a billing-enabled Google Cloud project and API key; to keep this project on a no-billing Spark setup, the picker uses OpenStreetMap instead. [Google Maps SDK setup and billing](https://developers.google.com/maps/documentation/android-sdk/get-api-key?setupProd=configure) · [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/)

## Spark/free-plan design

This version does **not** use Cloud Functions, Cloud Functions billing, FCM, or a server notification sender. Firestore stores group settings and the latest member coordinates. Each member's Android foreground service listens to the group's live member data, computes geofence transitions on-device, and displays local Android notifications for other members' exits. Firestore Security Rules limit writes to each user's own member document and restrict group settings to the creator.

This removes the Cloud Functions reason to upgrade to Blaze. Spark still has product and usage limits; check [Firebase pricing](https://firebase.google.com/pricing) for current free quotas. Location sharing and local exit alerts work only while the sender is uploading locations and each recipient's tracking service is running and connected. If the recipient force-stops the app, stops tracking, loses network, or Android/vendor power management stops the service, alerts may be delayed or missed. This design does not provide server-verified or emergency-grade alerts; a modified client can spoof its own location.

## App flow

1. The bottom navigation has **Groups**, **Notifications**, and **Create / Join**. The app signs in anonymously; Firebase assigns a stable UID used to identify the member.
2. In **Create / Join**, a user chooses Create, selects a map pin and radius, enters a display name and group name, and creates a group. The app writes the group, creator membership, and a random 12-character hexadecimal join code to Firestore. Share the code with other people.
3. Another user chooses Join, enters the code, and joins. Firestore rules let authenticated clients look up a code, while group details and members are readable only after membership exists. A device can keep and track multiple joined groups.
4. The creator can adjust the pin and radius later from the group dashboard. Group members can see the latest location and inside/outside status; only the creator can change the boundary.
5. Each participant grants location and notification permissions, then taps **Start tracking** from a group. Android shows an ongoing foreground-service notification. The service periodically writes that user's coordinates to the groups saved on that device and listens for member updates in those groups.
6. Each recipient's device independently calculates member distances. It stores each observed inside/outside state locally. An observed inside-to-outside transition creates one local notification and adds it to that device's **Notifications** dashboard; additional outside updates do not. An observed return inside resets the state so a later exit can notify again. The exiting member is excluded.
7. Tap **Stop tracking** to stop location updates and local exit monitoring.

## Firebase setup

1. Create/select a Firebase project and register an Android app with application ID `com.sarthak.geofencence`.
2. Put the matching `google-services.json` at `androidApp/google-services.json`. A file is already present in this workspace and its package matches; verify it belongs to your project. It is ignored by Git.
3. In Firebase Console → **Authentication → Sign-in method**, enable **Anonymous**. This is the sign-in method the app uses; keep it enabled.
4. Create a **Cloud Firestore** database. To deploy the rules in the console, open **Databases & Storage → Firestore Database → Rules**, replace the editor contents with the repository's `firestore.rules`, and click **Publish**. Firebase's [Firestore rules guide](https://firebase.google.com/docs/firestore/security/get-started) documents this console path. Or deploy from the project root with the CLI:

   ```sh
   npm install -g firebase-tools
   firebase login
   firebase use --add
   firebase deploy --only firestore:rules
   ```

   In `firebase use --add`, choose your Firebase project. No Functions initialization, Node.js runtime setup, service account key, FCM setup, or Blaze upgrade is needed for this implementation. If the project is currently Blaze, use the Firebase Console's **Usage and billing / plan** controls to select **Spark**. Firebase says a project can be downgraded to Spark in the Firebase Console; unlinking its Cloud Billing account also downgrades it, but that affects other Google Cloud services on the same project. Review Firebase's [plan behavior and quotas](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans) first.

## Build and run

Open the folder in Android Studio, allow Gradle sync, and run the `androidApp` configuration. Or run:

```sh
./gradlew :androidApp:assembleDebug
```

Firebase authentication and Firestore require the real matching `google-services.json`, enabled Anonymous Auth, a Firestore database, and deployed rules.

## Two-device test

1. Run the app on device A and B. Give them different names. Both sign in anonymously.
2. On A, enter a group name and geofence settings, then create a group. Share the 12-character code.
3. On B, enter the code and join. Confirm both members appear on each device.
4. Grant precise location and notification permission on both. Start tracking on both; leave both tracking services running.
5. In Android Emulator **Extended controls → Location**, send a point for A inside the circle, then send a point outside. B should show a local “Geofence Alert”; A should not.
6. Send more outside points: B should not repeat the alert. Move A back inside and then outside again: B should show another alert.
7. Stop tracking and confirm location sharing/alerts stop. Restart the app and confirm the anonymous account and group remain.

Use separate AVDs for separate users. Location changes are periodic and alerts depend on both devices having network connectivity and running the tracking service.

## Troubleshooting

- **Gradle sync / Firebase configuration error:** verify `androidApp/google-services.json` exists and the Firebase Android registration uses `com.sarthak.geofencence`.
- **Anonymous sign-in fails:** enable Anonymous in Firebase Authentication and verify the config file belongs to the same project.
- **Firestore permission denied:** deploy `firestore.rules`, sign in, and join via the 12-character code. Group/member creation is limited to the app's permitted flow.
- **Location doesn't update:** enable device Location, grant precise location, start the service while the app is visible, keep its persistent notification, and check vendor battery restrictions.
- **No local alert:** both devices need network access; the exiting user must be uploading locations and the recipient's tracking service must be active. Grant notification permission on Android 13+ and check that the alert channel is enabled in Android settings.
- **Different package ID:** register exactly `com.sarthak.geofencence` in Firebase or update the app ID and Firebase registration together.

## GitHub handoff

The local Firebase config, local SDK config, build outputs, IDE state, and dependencies are ignored. To publish this project, create an empty GitHub repository, then connect it and push the `main` branch:

```sh
git remote add origin <your-GitHub-repository-URL>
git push -u origin main
```

Replace the URL placeholder with the repository URL from GitHub. After cloning on another computer, add your Firebase project's `google-services.json` at `androidApp/google-services.json` before building.

## Loom demo outline

Before recording, configure Firebase Auth/Firestore and deploy the rules. Have two connected devices or AVDs with location and notification permissions. Show anonymous sign-in, create a group on A, share its code, join from B, show settings and member status, start tracking on both, establish inside then outside positions through Emulator Extended Controls, show B's local alert and A's lack of self-alert, demonstrate duplicate suppression and re-entry followed by a new exit, then stop tracking. Avoid showing private account or project credentials.
