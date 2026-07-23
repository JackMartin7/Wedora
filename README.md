# Wedora — Android (Kotlin, XML Views)

Native Android auth flow for the Wedora app, matching the Figma design pixel-for-pixel
(colors, spacing, typography) using traditional XML layouts + Kotlin + ViewBinding.

## What's included
- **SplashActivity** — gradient splash screen; after 1.8s routes to Onboarding on first launch, or restores a persisted session
- **OnboardingActivity** — 3-slide swipeable intro (ViewPager2) with animated pill page-indicators, Skip, and a Next → Get Started button; marks onboarding complete and continues to Login
- **LoginActivity** — email/password, remember me, forgot password, sign-up link, Continue as Guest
- **SignUpActivity** — email and password only; creates the account, sends the verification email, and hands back to Login

## Sign-up and profile setup

Signing up asks for two things: email and password. It writes **no** Firestore
document. Everything a profile needs is collected afterwards, one question per
screen, on the first verified login:

| Step | Screen | Collects | Written to `users/{uid}` |
|---|---|---|---|
| 1 | `ProfileStep1NameActivity` | Full name | `displayName`, `email` |
| 2 | `ProfileStep2GenderActivity` | Gender, Interested In | `gender`, `interestedIn` |
| 3 | `ProfileStep3StatusActivity` | Status, Looking For | `myStatus`, `lookingFor` |
| 4 | `ProfileStep4DetailsActivity` | Age (18+), location | `age`, `city`, `country`, `createdAt` |
| 5 | `ProfileStep5PhotoActivity` | Photo *(skippable)* | nothing — the photo is device-local |

Each step merges its own answer as it's given, so the flow is resumable.
`AuthRouting.resolveSignedInDestination` reads the document and sends the user
to the **first step they haven't answered**, which is also how accounts created
before these fields existed are brought up to date — no migration needed.

Step 5 is not gated on: the photo is optional and stored only on the device
(`LocalProfilePrefs`), so there is nothing server-side to check.

Because the document is built up in stages, the `users` security rule allows a
write with no `age` yet — but still rejects any write that sets an age below
18, so the 18+ policy holds throughout.

## Design tokens (from Figma)
| Token | Name | Light | Dark |
|---|---|---|---|
| Primary / CTA | `wedora_accent` | `#FA4659` | `#FA4659` |
| Brand gradient | `wedora_gradient_start` → `_end` | `#FF93A8 → #FE4667` | same |
| Background | `wedora_bg` | `#FAF7F7` | `#1C1417` |
| Surface | `wedora_surface` | `#FFFFFF` | `#2A2124` |
| Input background | `wedora_input_bg` | `#FDEFF1` | `#241A1D` |
| Input border | `wedora_input_border` | `#FBD3DA` | `#3A2E32` |
| Body text | `wedora_text` | `#241A1D` | `#F5EEEF` |
| Muted text | `wedora_text_secondary` | `#8C8589` | `#B8ABAE` |

Defined in `res/values/colors.xml` with dark-mode counterparts in
`res/values-night/colors.xml` — the two files use the same names, so the system
swaps them and there is no separate dark theme to maintain. Reuse these names
rather than raw hex on any screen you add.

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
│   ├── SignUpActivity.kt
│   ├── AuthRouting.kt          ← shared gate: which setup step (if any) a user still needs
│   ├── ProfileStepActivity.kt  ← shared chrome for the 5 setup steps
│   └── ProfileStep1..5*.kt     ← name / gender / status / details / photo
├── res/layout/
│   ├── activity_splash.xml
│   ├── activity_onboarding.xml   ← ViewPager2 + indicator dots + Next/Get Started
│   ├── item_onboarding_page.xml  ← single slide: illustration, title, description
│   ├── activity_login.xml
│   ├── activity_signup.xml
│   ├── activity_profile_step.xml ← progress + title + content slot + Continue/Skip
│   └── view_step_*.xml           ← each step's own fields, inflated into that slot
├── res/values/
│   ├── colors.xml
│   ├── dimens.xml      ← onboarding page-indicator dot sizes/spacing
│   ├── strings.xml
│   └── styles.xml      ← reusable styles: WedoraInputField, WedoraPrimaryButton, WedoraChip, WedoraSwitch
└── res/drawable/
    ├── bg_splash_gradient.xml
    ├── bg_input_field.xml
    ├── bg_button_primary.xml
    ├── ic_onboarding_venue / ic_onboarding_plan / ic_onboarding_celebrate   ← slide illustrations
    ├── indicator_dot_active.xml / indicator_dot_inactive.xml                 ← page-indicator dots
    └── ic_back / ic_hide
```

## Notes / next steps
- Password field uses `PasswordTransformationMethod` with a show/hide toggle wired to the eye icon.
- `Sign Up` / `Login` links use `SpannableString` to color just the linked word, matching the design.
- `btnLogin` / `btnSignUp` are wired to **Firebase Auth** email/password (see **Firebase Setup**
  above), with inline validation and mapped error messages (wrong password, email-already-in-use,
  weak password, network). Login routes through `AuthRouting.resolveSignedInDestination`, which
  is shared with `SplashActivity` so both entry points apply the same gate.
- Google / Facebook sign-in were removed — they were never implemented, and the buttons advertised
  methods the app doesn't have. `Continue as Guest` remains.
- Reuse `WedoraInputField`, `WedoraPrimaryButton`, `WedoraChip`, `WedoraSwitch` and the color
  palette in `colors.xml` when adding screens, so the whole app stays visually consistent.
- This README covers the launch and auth flow. The rest of the app — discover feed, likes, chat,
  profile, settings, filters — has grown well past what's listed here and isn't documented yet.
