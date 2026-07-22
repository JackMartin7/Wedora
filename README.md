# Wedora — Android (Kotlin, XML Views)

Native Android auth flow for the Wedora app, matching the Figma design pixel-for-pixel
(colors, spacing, typography) using traditional XML layouts + Kotlin + ViewBinding.

## What's included
- **SplashActivity** — gradient splash screen; after 1.8s routes to Onboarding on first launch, or straight to Login once onboarding is complete
- **OnboardingActivity** — 3-slide swipeable intro (ViewPager2) with animated pill page-indicators, Skip, and a Next → Get Started button; marks onboarding complete and continues to Login
- **LoginActivity** — email/password, remember me, forgot password, social login row, sign-up link
- **SignUpActivity** — username/email/password, social sign-up row, login link

## Design tokens (from Figma)
| Token | Value |
|---|---|
| Primary / CTA | `#FF445C` |
| Splash gradient | `#FF8292 → #FF5066 → #FF3953` |
| Input background | `#FFF9FA` |
| Input border | `#FF5167` |
| Body text | `#330E12` |
| Muted text | `#83686B` |
| Social button border | `#FFDADE` |

All defined in `app/src/main/res/values/colors.xml` — reuse these for every screen you add next.

## How to open
1. Open **Android Studio** → File → Open → select the `WedoraApp` folder.
2. Let Gradle sync (it will download the Android Gradle Plugin / Kotlin plugin automatically).
3. Run on an emulator or device — min SDK 24 (Android 7.0+).

## Firebase Setup

Authentication is powered by **Firebase Auth (email/password)**. The `google-services`
Gradle plugin requires a `google-services.json` config file that is tied to *your* Firebase
project, so the app **will not build until you add it**. This file is not committed to the repo.

1. **Create a Firebase project** — go to the [Firebase console](https://console.firebase.google.com),
   click **Add project**, and follow the prompts (Analytics is optional).
2. **Register the Android app** — in the project, click **Add app → Android** and set the
   **Android package name** to exactly:
   ```
   com.wedora.app
   ```
   (SHA-1 / debug signing certificate is optional for email/password auth — you can skip it.)
3. **Enable Email/Password sign-in** — go to **Build → Authentication → Get started**, open the
   **Sign-in method** tab, select **Email/Password**, toggle it **Enabled**, and save.
4. **Download `google-services.json`** — from the app registration step (or **Project settings →
   Your apps**), download the generated `google-services.json`.
5. **Place it in `app/`** — copy the file into the `app/` module directory so the final path is:
   ```
   app/google-services.json
   ```
6. **Sync Gradle** in Android Studio, then build and run.

> If you see a build error like `File google-services.json is missing`, it means step 5 wasn't
> completed or the file landed in the wrong folder (it must be in `app/`, not the project root).

### Firestore security rules

Cloud Firestore stores user profiles (`users/{uid}`), matches (`matches/{matchId}`),
messages (`matches/{matchId}/messages`), reports (`reports/{id}`) and blocks
(`blocks/{uid}/blockedUsers`). Access is controlled by [`firestore.rules`](firestore.rules),
which is deployed from this repo rather than edited in the console — so the live rules and the
committed ones can't drift apart.

> ### ⚠️ Deploy after **every** change to `firestore.rules`
> ```bash
> firebase deploy --only firestore:rules
> ```
> Editing the file does nothing until it's deployed — the live ruleset is what Firestore
> enforces. An undeployed change leaves any newly-referenced collection denied by default,
> which shows up in-app as `PERMISSION_DENIED`. This has already bitten three features
> (matches, the `likedBy`/`seenByRecipient` fields, and reports/blocks), so it's worth making
> a reflex: **touch `firestore.rules` → run the deploy.**

First-time setup:

```bash
npm install -g firebase-tools     # once
firebase login                    # once
firebase deploy --only firestore:rules
```

`firebase.json` points the CLI at `firestore.rules`, and `.firebaserc` pins the target project,
so the deploy command needs no extra arguments. To deploy to a different project:

```bash
firebase use --add                # register another project alias
firebase deploy --only firestore:rules --project <alias>
```

> **The app will not work until the rules are deployed.** A project left on the default locked
> ruleset rejects every read and write, which surfaces in-app as matches failing to save and
> empty feeds — `PERMISSION_DENIED` in logcat.

`firestore.indexes.json` is intentionally empty: the queries are all deliberately shaped to
avoid composite indexes (self-filtering and sorting happen client-side). If a query ever does
need one, Firestore's error message links to a page that generates the definition — paste it in
there and it deploys with `firebase deploy --only firestore:indexes`.

## Project structure
```
app/src/main/
├── java/com/wedora/app/
│   ├── SplashActivity.kt
│   ├── OnboardingActivity.kt
│   ├── OnboardingAdapter.kt   ← ViewPager2 adapter binding each slide
│   ├── OnboardingPage.kt      ← data model for a single slide (illustration + title + description)
│   ├── OnboardingPrefs.kt     ← SharedPreferences flag tracking whether onboarding is complete
│   ├── LoginActivity.kt
│   └── SignUpActivity.kt
├── res/layout/
│   ├── activity_splash.xml
│   ├── activity_onboarding.xml   ← ViewPager2 + indicator dots + Next/Get Started
│   ├── item_onboarding_page.xml  ← single slide: illustration, title, description
│   ├── activity_login.xml
│   └── activity_signup.xml
├── res/values/
│   ├── colors.xml
│   ├── dimens.xml      ← onboarding page-indicator dot sizes/spacing
│   ├── strings.xml
│   └── styles.xml      ← reusable styles: WedoraInputField, WedoraPrimaryButton, WedoraSocialButton
└── res/drawable/
    ├── bg_splash_gradient.xml
    ├── bg_input_field.xml
    ├── bg_button_primary.xml
    ├── bg_social_button.xml
    ├── ic_onboarding_venue / ic_onboarding_plan / ic_onboarding_celebrate   ← slide illustrations
    ├── indicator_dot_active.xml / indicator_dot_inactive.xml                 ← page-indicator dots
    └── ic_back / ic_hide / ic_google / ic_facebook / ic_apple
```

## Notes / next steps
- Password field uses `PasswordTransformationMethod` with a show/hide toggle wired to the eye icon.
- `Sign Up` / `Login` links use `SpannableString` to color just the linked word, matching the design.
- `btnLogin` / `btnSignUp` are wired to **Firebase Auth** email/password (see **Firebase Setup**
  above), with inline validation and mapped error messages (wrong password, email-already-in-use,
  weak password, network). On success they currently show a `Toast` — point them at your home
  screen once it exists (`// TODO` markers are in place in `LoginActivity.kt` / `SignUpActivity.kt`).
- Social login buttons (Google/Facebook/Apple) are stubbed — hook up the respective SDKs.
- Reuse `WedoraInputField`, `WedoraPrimaryButton`, `WedoraSocialButton`, and the color palette
  in `colors.xml` when building out the remaining ~50 screens (profile setup, discover, chat, etc.)
  so the whole app stays visually consistent.
