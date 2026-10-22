# Sling Slam

An Android game: lob boulders at the hazards rolling toward you before they smash into your shield.

## How to play

- **Aim and fire:** touch inside the circle at the bottom and pull back. Power comes from how far you drag from where you first touched, so your finger can wander off the circle. Pulling left aims right, like a real sling. Drag back to your starting point to cancel.
- **Short and long shots:** short pulls throw low and fast, almost straight out, for hazards that are close. Long pulls lob high and far.
- **Read the side view:** the strip at the top shows the battlefield side-on: you on the left, the hazard coming from the right, the arc of every shot, and an ✕ where it landed. Use it to correct your distance on the next throw.
- **Reload:** after each throw the next boulder rolls off the pile on the right into the sling.
- **Shield:** anything bad that reaches your sandbags drains it. Smashing hazards restores some, and it slowly recharges on its own. When it hits zero the game is over.
- **Hazards** come one at a time and get faster every 6:
  - Oil drum: rolls straight at you
  - Boulder: slower, takes two hits (a cracked one slows down)
  - Thorn bramble: creeps and weaves side to side
- **Bonuses** (hit them to collect):
  - Power cell case: +30 shield
  - Ammo crate: sling upgrade (faster reload, bigger impact)
  - Gold bars: +500 points
- **Streaks:** every 5 smashes in a row raises your points multiplier. Letting something through resets it.
- The bottom strip of the screen is kept clear for a banner ad.

## Install on your phone

Every push to `main` builds the app on GitHub Actions and publishes it to the
[latest release](https://github.com/randaladams/slingslam/releases/tag/latest).
Open that page on your phone, download `SlingSlam.apk`, and open it to install
(Android will ask you to allow installs from your browser the first time).
New builds install over the old one and keep your best score.

## Build it yourself

Open the folder in Android Studio, or run:

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

## Code map

- `World.kt` — game rules and physics (no Android code; easy to test)
- `GameView.kt` — layout, touch input, screens, HUD
- `Sprites.kt` — shaded drawings of boulders, hazards, sandbags, sling
- `Backdrop.kt` — painted battlefield and sky (generated at startup)
- `SoundFx.kt` — sound effects (`res/raw/*.wav`), master volume
- `MainActivity.kt` — full-screen host activity

The repo includes a throwaway signing key (`app/debug.keystore`) so updates install over each other.
Make your own private key before publishing to the Play Store.
