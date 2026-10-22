# Sling Slam

An Android game: lob boulders at the hazards rolling toward you before they smash into your shield.

## How to play

- **Aim and fire:** put your finger in the circle at the bottom, pull back, and let go. How far you pull sets the power (distance); pulling left or right aims the other way, like a real sling.
- **Read the side view:** the strip at the top shows the lane side-on — you on the left, the hazard coming from the right — with the arc of every shot and an ✕ where it landed. Use it to correct your distance on the next throw.
- **Reload:** after each throw the next boulder rolls off the pile on the right into the sling.
- **Shield:** anything bad that gets through drains it. Smashing hazards restores some, and it slowly recharges on its own. When it hits zero the game is over.
- **Hazards** come one at a time and get faster every 5:
  - Barrel — rolls straight at you
  - Rock — slower, takes two hits (a cracked rock slows down)
  - Snapping plant — weaves side to side
- **Bonuses** — hit them to collect:
  - Glowing orb: +30 shield
  - Gear: sling upgrade (faster reload, bigger impact)
  - Coin: +500 points
- **Streaks:** every 5 smashes in a row raises your points multiplier. Letting something through resets it.

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
- `GameView.kt` — drawing, touch input, screens
- `SoundFx.kt` — sound effects (`res/raw/*.wav`)
- `MainActivity.kt` — full-screen host activity

The repo includes a throwaway signing key (`app/debug.keystore`) so updates install over each other.
Make your own private key before publishing to the Play Store.
